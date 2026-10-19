package dev.fhirwalk.repl

import dev.fhirwalk.complete.FhirPathCompleter
import dev.fhirwalk.eval.FhirPathTokenizer
import dev.fhirwalk.eval.Focus
import dev.fhirwalk.eval.FocusResolver
import dev.fhirwalk.eval.FocusResolver.Resolution
import dev.fhirwalk.eval.Outcome
import dev.fhirwalk.eval.ViewBuilder
import dev.fhirwalk.model.FhirVersion
import dev.fhirwalk.model.FhirVersionUnavailable
import dev.fhirwalk.pool.GitSource
import dev.fhirwalk.pool.GitSourceException
import dev.fhirwalk.pool.ResourceHistory
import org.jline.reader.EndOfFileException
import org.jline.reader.LineReader
import org.jline.reader.Reference
import org.jline.reader.UserInterruptException
import org.hl7.fhir.instance.model.api.IBase
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * @param explicitVersion the version asked for on the command line, which wins over
 *   the one the workspace was left on; null to take the workspace's.
 * @param initialPaths paths from the command line, loaded on top of the workspace's.
 * @param workspaces where workspaces live; `Workspaces(null)` keeps everything in memory.
 * @param fresh start with an empty pool and no focus, leaving the saved state as it
 *   is until something changes.
 */
class Repl(
    private val explicitVersion: FhirVersion?,
    private val initialPaths: List<String>,
    private val workspaces: Workspaces = Workspaces.inHome(),
    workspaceName: String = Workspaces.DEFAULT,
    private val fresh: Boolean = false,
) {

    private var workspace = workspaces.open(workspaceName)

    /** Read before the session exists, because it decides the session's version. */
    private var restoring: SavedState = if (fresh) SavedState() else workspace.state.read()

    /** Said once [open] has somewhere to say it. */
    private val startupNotes = mutableListOf<String>()

    private val commitTimes = CommitTimes()

    private var session = startSession(
        explicitVersion ?: restoring.fhir?.let(FhirVersion::parse),
        explicit = explicitVersion != null,
    ) { startupNotes += it }

    private val log: ExpressionLog get() = workspace.log
    private val names: NameStore get() = workspace.names
    private val views: ViewStore get() = workspace.views

    /** The workspace in use, for tests. */
    internal val currentWorkspace: Workspace get() = workspace

    /** Set by `:limit`, and saved; null leaves the limit to the window size. */
    private var limitSet: Int? = null

    /** What the state file was last written with, or found holding, so unchanged state is not rewritten. */
    private var lastSaved: SavedState? = null

    /** The columns of the last `:view`, for `:name-view`. */
    private var lastView: List<ViewBuilder.Column>? = null

    /** Null until [run] has built it; the tests drive [dispatch] without one. */
    private var reader: LineReader? = null

    /**
     * Set while the workspace has no directory yet, so its up-arrow history is kept
     * in memory; [attachPendingHistory] moves it to the file once something saved
     * has created the directory.
     */
    private var historyPending = false

    private val commands = listOf(
        FhirPathCompleter.CommandInfo(":load", "<path>", "import a file, bundle, NDJSON or directory tree"),
        FhirPathCompleter.CommandInfo(":pool", "[name]", "switch pool; alone, what is in the current one"),
        FhirPathCompleter.CommandInfo(":pools", "", "the open pools, their sizes and paths"),
        FhirPathCompleter.CommandInfo(":open", "<name> [path|dir@[rev]]", "add a pool and switch to it: path loads, dir@rev is a git revision, dir@ its current branch"),
        FhirPathCompleter.CommandInfo(":close", "<name>", "drop a pool"),
        FhirPathCompleter.CommandInfo(":log", "[label] [n] [+skip]", "a git pool's line, newest first, with what each commit changed; with a label, the commits that changed that resource"),
        FhirPathCompleter.CommandInfo(":at", "<rev|date>", "move a git pool to a revision, or to its line as of a date (2026-03-11); TAB lists commits by date"),
        FhirPathCompleter.CommandInfo(":step", "[-n|+n]", "move a git pool along its line; alone, one commit back"),
        FhirPathCompleter.CommandInfo(":trace", "[label] <expr>", "how an expression's answer for one resource changed along a git pool's line; with a focus, the focused one"),
        FhirPathCompleter.CommandInfo(":delta", "", "what this revision added, modified and deleted"),
        FhirPathCompleter.CommandInfo(":scope", "[delta|all]", "run pool-wide over the delta only, or over everything"),
        FhirPathCompleter.CommandInfo(":focus", "[label|#index|expr|[n]|..]", "evaluate against one resource or element"),
        FhirPathCompleter.CommandInfo(":all", "<expr>", "evaluate across the whole pool"),
        FhirPathCompleter.CommandInfo(":why", "[expr]", "find the step where the expression goes empty"),
        FhirPathCompleter.CommandInfo(":ext", "[path]", "extension URLs observed in the pool"),
        FhirPathCompleter.CommandInfo(":history", "[filter]", "recorded pool-wide expressions, newest first"),
        FhirPathCompleter.CommandInfo(":view", "#n|name|@view …", "a table: one row per resource, a column per recorded expression"),
        FhirPathCompleter.CommandInfo(":name-expr", "[name [#n]]", "name the expression just run, or #n, for :view; alone, list"),
        FhirPathCompleter.CommandInfo(":unname-expr", "<name>", "forget an expression's name"),
        FhirPathCompleter.CommandInfo(":name-view", "[view]", "name and save the last :view, to rerun as :view @view; alone, list"),
        FhirPathCompleter.CommandInfo(":unname-view", "<view>", "forget a saved view"),
        FhirPathCompleter.CommandInfo(":workspace", "[name]", "switch workspace; alone, show it and list the others"),
        FhirPathCompleter.CommandInfo(":more", "", "reprint the last listing in full, nothing shortened"),
        FhirPathCompleter.CommandInfo(":limit", "[rows|off]", "rows per listing before the rest waits for :more"),
        FhirPathCompleter.CommandInfo(":fhir", "[version]", "switch FHIR version (resets the pool)"),
        FhirPathCompleter.CommandInfo(":help", "", "this list"),
        FhirPathCompleter.CommandInfo(":quit", "", "exit"),
    )

    /** Command names including the undocumented aliases, for the dropped-colon hint. */
    private val commandNames: Set<String> =
        commands.map { it.name }.toSet() + setOf(":exit", ":q", ":?")

    /**
     * The last listing printed, so `:more` can print it whole: trimmed to the window
     * when it was shown, both in rows and in the width of each part. Truncating
     * without a way back to the whole thing is worse than printing it all.
     */
    private var lastListing: Output.Listed? = null

    /**
     * The last expression that was run, so a bare `:why` can re-diagnose it.
     *
     * The hint that leads to `:why` is printed the moment an expression comes back
     * empty, which is exactly when retyping a long path — or a path with an extension
     * URL in it — is most annoying. Only expressions are remembered, so `:pool`
     * between the two does not lose it.
     */
    private var lastExpression: String? = null

    /**
     * What `:name-expr <name>` with no number names: the expression just run, if it was
     * pool-wide and matched -- the ones that are recorded. Every evaluation replaces
     * it, so a focus-relative or empty one in between leaves nothing to name rather
     * than an older expression the user has stopped looking at.
     */
    private var nameable: String? = null

    /**
     * The rows of the last result, so `:focus [n]` can pick one. Every result
     * replaces it -- a numbered one with its rows, any other (`:all`, an empty or
     * failed one, a `:focus` that did not pick) with nothing -- so `[n]` never
     * reaches past what is on screen. Command output (`:pool`, `:more`, `:why`, a
     * focus announcement) is not a result and leaves it alone, which is what lets
     * `:focus [0]`, `:focus ..`, `:focus [1]` work. A pick made under a different
     * focus is refused by the resolver.
     */
    private var lastPicks: FocusResolver.Picks? = null

    /** Rows printed per listing before the rest is held for `:more`; 0 = unlimited. */
    private var rowLimit = DEFAULT_ROW_LIMIT

    /** What [rowLimit] is without a `:limit`: the window's height, once [run] knows it. */
    private var windowRowLimit = DEFAULT_ROW_LIMIT

    private var columns = DEFAULT_COLUMNS

    /** Plain until [run] has a terminal to ask; see [Style.detect]. */
    private var style = Style.PLAIN

    fun run() {
        val terminal: Terminal = TerminalBuilder.builder().dumb(true).build()
        val out = terminal.writer()
        // Built directly rather than through LineReaderBuilder, which can only make a
        // plain LineReaderImpl: the menu's group order needs the subclass.
        val reader = FhirWalkLineReader(terminal).apply {
            parser = FhirPathParser()
            completer = FhirPathCompleter(
                { session.navigator }, { session.pool }, { commands }, { session.focus },
                { log.entries() }, { names.all() }, { views.all().keys }, { workspaces.available() },
                { session.pools.map { it.name } }, { session.current.git?.source?.refNames().orEmpty() },
                { dir -> runCatching { GitSource.refNamesAt(Paths.get(expandHome(dir))) }.getOrDefault(emptyList()) },
                ::gitSpecOf,
                ::commitChoices,
            )
            // AUTO_LIST prints a passive list that arrow keys cannot touch, so it
            // costs an extra TAB before anything is navigable -- press an arrow at a
            // passive list and you get command history instead of the next candidate.
            // The menu shows the same grouped list and does accept arrows, so skip
            // the passive stage and let the first TAB open the menu.
            option(LineReader.Option.AUTO_LIST, false)
            option(LineReader.Option.AUTO_MENU, true)
            option(LineReader.Option.AUTO_GROUP, true)
            option(LineReader.Option.GROUP_PERSIST, true)
            option(LineReader.Option.CASE_INSENSITIVE, true)
            option(LineReader.Option.DISABLE_EVENT_EXPANSION, true)
        }
        this.reader = reader
        show(out) { useLineHistory(reader, it) }
        bindArrowKeys(reader)
        style = Style.detect(terminal)

        // Fill the window and keep the rest for :more, rather than a fixed guess.
        if (terminal.height > MIN_ROW_LIMIT + 8) rowLimit = terminal.height - 8
        windowRowLimit = rowLimit
        if (terminal.width > MIN_VALUE_WIDTH) columns = terminal.width

        out.println("fhir-walk — FHIRPath over a local resource pool.  :help for commands")
        open(out)
        out.println(if (session.pool.size == 0) "pool is empty — :load <path> to import" else "TAB on an empty line lists the resource types in the pool")
        out.flush()

        while (true) {
            val line = try {
                reader.readLine(prompt())
            } catch (e: UserInterruptException) {
                continue
            } catch (e: EndOfFileException) {
                break
            }
            val input = line?.trim().orEmpty()
            if (input.isEmpty()) continue
            try {
                if (!dispatch(input, out)) break
            } catch (e: Exception) {
                out.println(style.error("! ${e.message ?: e.javaClass.simpleName}"))
            }
            show(out) { attachPendingHistory(reader, it) }
            out.flush()
        }
        out.println("bye")
        out.flush()
        reader.history.save()
        terminal.close()
    }

    /**
     * Restores the workspace, then loads the command line's paths on top. Internal,
     * like [dispatch], so tests can start a session without a terminal.
     */
    internal fun open(out: PrintWriter) = show(out) { open(it) }

    private fun open(out: Output) {
        startupNotes.forEach { out.error(it) }
        startupNotes.clear()
        val where = if (workspace.name == Workspaces.DEFAULT) "" else "workspace ${workspace.name}, "
        out.text("${where}FHIR ${session.version.id.uppercase()}")
        reportStorageProblems(out)
        restore(restoring, out)
        initialPaths.forEach { load(it, out) }
        saveState(out)
    }

    /**
     * The pool's name goes before a focus once there are several to tell apart, or
     * when it is a git pool, whose revision always shows. With the scope on the
     * delta, the delta's size stands where the pool's would.
     */
    private fun prompt(): String {
        val git = session.current.git
        val where = session.current.name + git?.let { "@${it.commit.short}" }.orEmpty()
        val focus = session.focus?.label?.let { abbreviate(it, PROMPT_WIDTH) }
        val scope = when {
            focus == null && git?.deltaScope == true -> "$where Δ${git.delta.size}"
            focus == null -> "$where(${session.pool.size})"
            session.pools.size > 1 || git != null -> "$where:$focus"
            else -> focus
        }
        return "${session.version.id}:$scope> "
    }

    /**
     * One line of input, as [run] hands it over. Internal so tests can drive the
     * REPL without a terminal: none of this needs one.
     *
     * @return false to exit.
     */
    internal fun dispatch(input: String, out: PrintWriter): Boolean {
        val output = Output()
        try {
            return dispatch(input, output)
        } finally {
            show(output, out)
        }
    }

    /**
     * As [dispatch], but what the line had to say is returned rather than printed:
     * what a command found, apart from the window it would be shown in. For tests.
     */
    internal fun outputOf(input: String): Output = Output().also {
        dispatch(input, it)
        remember(it)
    }

    /** Prints what [say] says, as everything is printed: fitted, coloured, and kept for `:more`. */
    private fun show(out: PrintWriter, say: (Output) -> Unit) = show(Output().also(say), out)

    private fun show(output: Output, out: PrintWriter) {
        remember(output)
        Printer(style, columns, rowLimit).print(output, out)
    }

    private fun remember(output: Output) {
        output.listing?.let { lastListing = it }
    }

    private fun dispatch(input: String, out: Output): Boolean {
        if (!input.startsWith(":")) {
            // `all Specimen.subject` is a dropped colon, not an expression. Saying so
            // beats handing the whole line to the FHIRPath parser -- unless the word is
            // an element where the expression runs: `name` is a command and, far more
            // often, Patient.name.
            val firstWord = input.substringBefore(' ')
            val command = RENAMED[":$firstWord"] ?: ":$firstWord"
            if (command in commandNames && !(isElementHere(firstWord) && parses(input))) {
                out.error("not a FHIRPath expression — did you mean  $command${input.drop(firstWord.length)}")
                return true
            }
            lastExpression = input
            evaluate(input, out)
            return true
        }
        val command = input.substringBefore(' ')
        val argument = input.substringAfter(' ', "").trim()
        // Saved even when the command throws part-way: a path already in the pool
        // must not be missing from the workspace. Expressions change nothing saved.
        try {
            return command(command, argument, out)
        } finally {
            saveState(out)
        }
    }

    /** @return false to exit. */
    private fun command(command: String, argument: String, out: Output): Boolean {
        when (command) {
            ":quit", ":exit", ":q" -> return false
            ":help", ":?" -> help(out)
            ":load" -> if (argument.isBlank()) out.text("usage: :load <path>") else load(argument, out)
            ":pool" -> if (argument.isBlank()) poolSummary(out) else switchPool(argument, out)
            ":pools" -> listPools(out)
            ":open" -> openPool(argument, out)
            ":close" -> closePool(argument, out)
            ":log" -> gitLog(argument, out)
            ":at" -> if (argument.isBlank()) out.text("usage: :at <rev|date>  —  a branch, tag, id, branch~2, or 2026-03-11 for the line as of then") else at(argument, out)
            ":step" -> step(argument, out)
            ":trace" -> trace(argument, out)
            ":delta" -> delta(out)
            ":scope" -> scope(argument, out)
            ":focus" -> focus(argument, out)
            ":all" -> if (argument.isBlank()) {
                out.text("usage: :all <expr>")
            } else {
                lastExpression = argument
                evaluateAll(argument, out)
            }
            ":why" -> why(argument, out)
            ":ext" -> extensions(argument, out)
            ":history" -> history(argument, out)
            ":view" -> view(argument, out)
            ":name-expr" -> name(argument, out)
            ":unname-expr" -> unname(argument, out)
            ":name-view" -> saveView(argument, out)
            ":unname-view" -> unsaveView(argument, out)
            ":workspace" -> switchWorkspace(argument, out)
            ":more" -> more(out)
            ":limit" -> limit(argument, out)
            ":fhir" -> switchVersion(argument, out)
            in RENAMED -> {
                val now = RENAMED.getValue(command)
                out.error("$command is now $now — did you mean  $now${argument.let { if (it.isEmpty()) "" else " $it" }}")
            }
            else -> out.text("unknown command $command — :help for the list")
        }
        return true
    }

    /**
     * Whether the line is FHIRPath at all. `name family #1` starts with an element
     * name too, but no expression continues that way, so it is a dropped colon.
     */
    private fun parses(input: String): Boolean = session.evaluator.validate(input) == null

    /** Whether [word] is a child of the focus, or with none, of a resource type in the pool. */
    private fun isElementHere(word: String): Boolean {
        val paths = session.focus?.let { listOf(it.path) } ?: session.pool.types()
        return paths.any { path -> session.navigator.childrenAt(path).any { it.name == word } }
    }

    // ------------------------------------------------------------------ evaluate

    private fun evaluate(expression: String, out: Output) {
        lastPicks = null
        nameable = null
        if (reportSyntaxError(expression, out)) return
        val focus = session.focus
        if (focus == null) {
            if (session.pool.size == 0) {
                out.text("pool is empty — :load <path> first")
                return
            }
            // No focus: the pool is the subject. This is the default on purpose.
            evaluateAll(expression, out)
            return
        }
        when (val outcome = session.evaluator.evaluate(focus.node, expression)) {
            is Outcome.Failure -> out.error("${outcome.message}")
            is Outcome.Values -> {
                if (outcome.values.isEmpty()) {
                    out.text("{} empty — :why")
                    return
                }
                lastPicks = session.focusResolver.picksOf(expression, focus, outcome.values)
                out.listing(
                    header = emptyList(),
                    rows = outcome.values.mapIndexed { idx, value -> valueRow(value, idx) },
                    full = outcome.values.flatMapIndexed { idx, value -> expanded("  [$idx] ", value) },
                )
            }
        }
    }

    // ------------------------------------------------------------------- listings

    private fun more(out: Output) {
        val listing = lastListing ?: run {
            out.text("nothing listed yet")
            return
        }
        out.whole(listing)
    }

    private fun limit(argument: String, out: Output) {
        when {
            argument.isBlank() ->
                out.text(
                    "rows per listing: " + if (rowLimit <= 0) "unlimited" else rowLimit.toString(),
                )
            argument.equals("off", ignoreCase = true) || argument == "0" -> {
                rowLimit = UNLIMITED
                limitSet = UNLIMITED
                out.text("row limit off — listings print in full and the terminal scrolls")
            }
            else -> {
                val rows = argument.toIntOrNull()
                if (rows == null || rows < 1) {
                    out.text("usage: :limit <rows|off>")
                } else {
                    rowLimit = rows
                    limitSet = rows
                    out.text("rows per listing: $rows")
                }
            }
        }
    }

    /**
     * Binds both encodings of the arrow keys, in the line editor and in the
     * completion menu.
     *
     * JLine asks the terminal for application-cursor-keys mode (DECCKM, the
     * `ESC [ ? 1 h` it writes with every prompt) and then binds only the SS3 form
     * (`ESC O A`) that the mode implies. Terminals and multiplexers that keep sending
     * the CSI form (`ESC [ A`) are left with arrow keys that type a literal "[A" --
     * which is why navigation worked in some terminals and not in others. Binding
     * both forms makes it independent of whether the mode took effect.
     *
     * In the menu the same keys walk the candidates, so a long extension URL can be
     * picked with Up/Down rather than by counting TAB presses.
     */
    private fun bindArrowKeys(reader: LineReader) {
        val keyMaps = reader.keyMaps
        keyMaps[LineReader.MAIN]?.apply {
            bind(Reference(LineReader.UP_LINE_OR_SEARCH), ESC_CSI + "A", ESC_SS3 + "A")
            bind(Reference(LineReader.DOWN_LINE_OR_SEARCH), ESC_CSI + "B", ESC_SS3 + "B")
            bind(Reference(LineReader.FORWARD_CHAR), ESC_CSI + "C", ESC_SS3 + "C")
            bind(Reference(LineReader.BACKWARD_CHAR), ESC_CSI + "D", ESC_SS3 + "D")
        }
        keyMaps[LineReader.MENU]?.apply {
            bind(Reference(LineReader.MENU_COMPLETE), ESC_CSI + "B", ESC_SS3 + "B")
            bind(Reference(LineReader.REVERSE_MENU_COMPLETE), ESC_CSI + "A", ESC_SS3 + "A")
        }
    }

    /**
     * One value on one line, shortened to fit: `[idx] Type  value`.
     *
     * @param label adds a resource column, for rows drawn from several resources.
     */
    private fun valueRow(value: IBase, index: Int? = null, label: String? = null): Output.Line = Output.line {
        +"  "
        index?.let {
            index("[$it]")
            +" "
        }
        label?.let {
            label(it.padEnd(LABEL_WIDTH))
            +" "
        }
        type(session.render.typeOf(value))
        +"  "
        shrink(session.render.value(value), Output.Kind.PREVIEW, cutsEnd = true)
    }

    /**
     * One value in full: a primitive stays on the label's line, while anything
     * structured gets pretty-printed JSON underneath it.
     */
    private fun expanded(label: String, value: IBase): List<Output.Line> {
        val type = session.render.typeOf(value)
        val lines = session.render.pretty(value)
        if (lines.size == 1) {
            return listOf(
                Output.line {
                    index(label)
                    type(type)
                    +"  "
                    value(lines.first())
                },
            )
        }
        val indent = " ".repeat(label.length + 2)
        return listOf(Output.line { index(label); type(type) }) + lines.map { line -> Output.line { +indent; json(line) } }
    }

    /**
     * A syntax error belongs to the expression, not to the data: report it once,
     * rather than once per resource in the pool.
     *
     * @return true when the expression could not be parsed and nothing should run.
     */
    private fun reportSyntaxError(expression: String, out: Output): Boolean {
        val error = session.evaluator.validate(expression) ?: return false
        out.error("$error")
        // FHIRPath strings are single-quoted; " delimits an identifier, so a
        // double-quoted URL fails with a message that never mentions quoting.
        if (expression.contains('"')) {
            out.text("  FHIRPath string literals use single quotes — try '…' rather than \"…\"")
        }
        return true
    }

    /** Not numbered: rows are resources, and an indexer would not pick between them. */
    private fun evaluateAll(expression: String, out: Output) {
        lastPicks = null
        // Before the syntax check, so a failed :all leaves nothing behind to name.
        nameable = null
        if (reportSyntaxError(expression, out)) return
        val result = session.evaluator.evaluateAll(expression)
        val header = Output.line {
            +"${result.hits.size} of ${result.considered} resource(s) matched"
            if (result.errors > 0) error("; ${result.errors} error(s): ${result.firstError}")
        }
        // Matching something is the bar: a misspelt element parses fine and only
        // ever comes back empty, and would otherwise clutter the log.
        nameable = expression.takeIf { result.hits.isNotEmpty() }
        if (result.hits.isNotEmpty()) record(expression, out)
        if (result.hits.isEmpty()) {
            out.add(header)
            if (result.errors == 0) {
                // Nothing matched anywhere, and it parsed: the question is which step
                // goes empty, which is exactly what :why answers.
                out.text("  nothing matched — :why")
            }
            lastListing = null
            return
        }
        out.listing(
            listOf(header),
            rows = result.hits.map { hit ->
                Output.line {
                    +"  "
                    label(hit.resource.label.padEnd(LABEL_WIDTH))
                    +" "
                    shrink(hit.values.firstOrNull()?.let { session.render.value(it) }.orEmpty(), Output.Kind.PREVIEW, cutsEnd = true)
                    if (hit.values.size > 1) hint(" (+${hit.values.size - 1})")
                }
            },
            // The full form keeps every value whole and adds the origin, so one
            // listing answers both "what matched" and "where did it come from".
            full = result.hits.flatMap { hit ->
                listOf(Output.line { +"  "; label(hit.resource.label); +"  "; hint("(${hit.resource.origin})") }) +
                    hit.values.flatMap { expanded("      ", it) }
            },
        )
    }

    /** @param argument the expression, or blank to re-diagnose [lastExpression]. */
    private fun why(argument: String, out: Output) {
        val expression = argument.ifBlank { lastExpression ?: "" }
        if (expression.isBlank()) {
            out.text("usage: :why <expr>  —  or :why alone, to re-run the last expression")
            return
        }
        lastExpression = expression
        if (reportSyntaxError(expression, out)) return
        if (session.pool.size == 0) {
            out.text("pool is empty")
            return
        }
        val root = FhirPathTokenizer.segments(expression)
            .firstOrNull()?.name?.takeIf { it.isNotBlank() }
        // Diagnosing a Specimen expression against a Patient reports "empty at step
        // one" and teaches nothing, so the subject must match the expression's root.
        if (root != null && session.navigator.resourceTypes().contains(root) &&
            session.pool.ofType(root).isEmpty()
        ) {
            out.text("no $root in the pool — it holds ${session.pool.types().joinToString(", ")}")
            return
        }
        val focus = session.focus
            ?: (session.prefixWalker.diagnosisTarget(expression) ?: session.pool.all().first()).let { Focus.of(it) }
        if (session.focus == null) out.text("(no focus; diagnosing against ${focus.label})")
        // A relative step is rooted at the focus, then stripped of where()/[n] the way
        // the census spells it: "how many Observations populate code.coding.foo" is
        // the question, not how many share this one's indexes.
        val diagnosis = session.prefixWalker.diagnose(focus.node, expression) { prefix ->
            val rooted = session.focus?.absolute(prefix)
            if (rooted == null || rooted == prefix) prefix else session.navigator.censusPath(rooted)
        }
        // Each row shows only the step it adds, not the whole prefix: with long
        // extension URLs in the path, repeating the prefix makes the table unreadable.
        val segments = FhirPathTokenizer.segments(expression)
            .filter { it.text.isNotBlank() }
        for ((idx, step) in diagnosis.steps.withIndex()) {
            val marker = if (idx == diagnosis.firstEmpty) "  <-- empty here" else ""
            val count = when {
                !step.reached -> "—"
                step.error != null -> "! ${step.error}"
                else -> "${step.count}"
            }
            val label = segments.getOrNull(idx)?.text?.let { if (idx == 0) it else ".$it" }
                ?: step.expression
            out.line {
                +"  ${abbreviate(label, STEP_WIDTH).padEnd(STEP_WIDTH)} "
                if (step.error != null) error(count.padEnd(5)) else +count.padEnd(5)
                +marker
            }
        }
        if (diagnosis.firstEmpty == null) {
            out.text("  path resolves fully")
            return
        }
        diagnosis.poolCoverage?.let { coverage ->
            out.text(
                "  ${abbreviate(coverage.expression, 60)} is populated in ${coverage.matching} of " +
                    "${coverage.considered} resource(s) of that type",
            )
            if (coverage.matching == 0) {
                out.text("  nothing in the pool populates it — check the element name or the data")
            }
        }
    }

    // -------------------------------------------------------------- recorded

    private val reportedProblems = mutableSetOf<String>()

    /**
     * Each problem once, wherever the log or the names are touched first: at
     * startup, or when a write fails later. Asking for the entries is what reads
     * the files, so a problem is known by the time this looks.
     */
    private fun reportStorageProblems(out: Output) {
        log.entries()
        names.all()
        views.all()
        workspace.problems.filter { reportedProblems.add(it) }.forEach {
            out.error("$it — changes are kept for this session only")
        }
    }

    private fun record(expression: String, out: Output) {
        log.record(expression)
        reportStorageProblems(out)
    }

    private fun history(argument: String, out: Output) {
        val entries = log.entries()
        reportStorageProblems(out)
        val numbered = entries.mapIndexed { idx, expression -> idx + 1 to expression }
            .filter { (_, expression) -> expression.contains(argument, ignoreCase = true) }
            .asReversed()
        if (numbered.isEmpty()) {
            out.text(
                if (argument.isBlank()) {
                    "nothing recorded yet — pool-wide expressions that match something are kept here"
                } else {
                    "no recorded expression contains $argument"
                },
            )
            return
        }
        val width = "#${numbered.first().first}".length
        // Once per listing, not per row: each lookup checks the names file.
        val namesOf = names.all().entries.groupBy({ it.value }, { it.key })
        out.listing(
            header = emptyList(),
            rows = numbered.map { (number, expression) ->
                Output.line {
                    +"  "
                    index("#$number".padEnd(width))
                    +"  "
                    shrink(expression)
                    namesOf[expression]?.let { hint("  (${it.joinToString(", ")})") }
                }
            },
        )
    }

    /**
     * `:name-expr` lists, `:name-expr family` names the expression just run,
     * `:name-expr family #1` names a recorded one. A name may hold any expression; only `:view` asks for
     * primitives, and says so when it is used.
     */
    private fun name(argument: String, out: Output) {
        val parts = argument.split(Regex("\\s+")).filter { it.isNotEmpty() }
        reportStorageProblems(out)
        when (parts.size) {
            0 -> {
                val all = names.all()
                if (all.isEmpty()) {
                    out.text("no names yet — :name-expr <name> #n names a recorded expression")
                    return
                }
                val width = all.keys.maxOf { it.length }
                out.listing(
                    header = emptyList(),
                    rows = all.map { (name, expression) ->
                        Output.line {
                            +"  "
                            label(name.padEnd(width))
                            +"  "
                            shrink(expression)
                        }
                    },
                )
            }
            1, 2 -> {
                val name = parts[0]
                if (!NameStore.valid(name)) {
                    out.text("not a valid name: $name — ${KeyedStore.RULE}")
                    return
                }
                val expression = if (parts.size == 1) {
                    nameable ?: run {
                        out.text(
                            "nothing to name — run a pool-wide expression that matches something first, " +
                                "or :name-expr $name #n for one from :history",
                        )
                        return
                    }
                } else {
                    val number = parts[1].removePrefix("#").toIntOrNull() ?: run {
                        out.text("usage: :name-expr <name> [#n]  —  #n from :history")
                        return
                    }
                    log.get(number) ?: run {
                        out.text("nothing recorded as #$number — see :history")
                        return
                    }
                }
                val before = names.set(name, expression)
                reportStorageProblems(out)
                out.text(
                    when (before) {
                        null -> "$name  $expression"
                        expression -> "$name already names $expression"
                        else -> "$name  $expression  (was $before)"
                    },
                )
            }
            else -> out.text("usage: :name-expr [name [#n]]")
        }
    }

    private fun unname(argument: String, out: Output) {
        if (argument.isBlank()) {
            out.text("usage: :unname-expr <name>")
            return
        }
        val before = names.remove(argument)
        reportStorageProblems(out)
        out.text(if (before == null) "no name $argument — :name-expr lists them" else "forgot $argument  ($before)")
    }

    /** @param argument recorded expressions by number or name: `#3 family`, `3, 5`. */
    private fun view(argument: String, out: Output) {
        val refs = argument.split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
        if (refs.isEmpty()) {
            out.text("usage: :view #n|name|@view …  —  numbers from :history, names from :name-expr, views from :name-view")
            return
        }
        reportStorageProblems(out)
        val picked = refs.flatMap { ref ->
            if (ref.startsWith("@")) {
                return@flatMap savedColumns(ref.drop(1)) ?: run {
                    out.text("no saved view ${ref.drop(1)} — :name-view lists them")
                    return
                }
            }
            val number = ref.removePrefix("#").toIntOrNull()
            if (number != null) {
                val expression = log.get(number) ?: run {
                    out.text("nothing recorded as #$number — see :history")
                    return
                }
                listOf(ViewBuilder.Column(expression, number = number))
            } else {
                val expression = names.get(ref) ?: run {
                    out.text("no :history number or name $ref — see :history and :name-expr")
                    return
                }
                listOf(ViewBuilder.Column(expression, name = ref))
            }
        }
        val table = when (val result = session.viewBuilder.build(picked)) {
            is ViewBuilder.Result.Refused -> {
                out.error("${result.message}")
                return
            }
            is ViewBuilder.Result.Table -> result
        }
        lastView = picked
        out.table(
            header = listOf(
                Output.line {
                    +"${table.rows.size} ${table.type} row(s)"
                    if (table.errors > 0) error("; ${table.errors} error(s): ${table.firstError}")
                },
            ),
            titles = table.columns.map { it.title(table.type) },
            rows = table.rows.map { row ->
                Output.Table.Row(
                    row.resource.label,
                    row.cells,
                    errors = row.cells.indices.filter { row.cells[it] == ViewBuilder.ERROR_CELL }.toSet(),
                )
            },
        )
    }

    /**
     * A saved view's columns as they stand now. A named column follows its name while
     * the name exists, so its title and what it computes cannot disagree; once the
     * name is gone it keeps the expression it was saved with. Every other column is
     * renumbered against the log: `#3` in a title must be its number today, and one
     * no longer in the log has no number rather than a wrong one.
     */
    private fun savedColumns(view: String): List<ViewBuilder.Column>? {
        val saved = views.get(view) ?: return null
        val entries = log.entries()
        val named = names.all()
        return saved.map { column ->
            column.name?.let { name -> named[name]?.let { return@map ViewBuilder.Column(it, name = name) } }
            val number = entries.indexOf(column.expression) + 1
            ViewBuilder.Column(column.expression, number = number.takeIf { it > 0 })
        }
    }

    /** `:name-view` lists, `:name-view patients` saves the last `:view` under that name. */
    private fun saveView(argument: String, out: Output) {
        reportStorageProblems(out)
        if (argument.isBlank()) {
            val all = views.all()
            if (all.isEmpty()) {
                out.text("no saved views — :name-view <name> after a :view saves it")
                return
            }
            val width = all.keys.maxOf { it.length } + 1
            out.listing(
                header = emptyList(),
                rows = all.map { (name, columns) ->
                    Output.line {
                        +"  "
                        label("@$name".padEnd(width))
                        +"  "
                        shrink(columns.joinToString("  ") { it.ref })
                    }
                },
                full = all.flatMap { (name, columns) ->
                    listOf(Output.line { +"  "; label("@$name") }) + columns.map { Output.text("      ${it.described}") }
                },
            )
            return
        }
        // `@people` is how a view is written everywhere else, so it is taken here too.
        val name = argument.removePrefix("@")
        if (!KeyedStore.valid(name)) {
            out.text("not a valid view name: $name — ${KeyedStore.RULE}")
            return
        }
        val columns = lastView ?: run {
            out.text("nothing to save — run a :view first")
            return
        }
        val before = views.set(name, columns)
        reportStorageProblems(out)
        val replaced = if (before != null && before != columns) "  (replaced ${before.size} column(s))" else ""
        out.text("saved @$name: ${columns.size} column(s) — :view @$name reruns it$replaced")
    }

    private fun unsaveView(argument: String, out: Output) {
        val name = argument.removePrefix("@")
        if (name.isBlank()) {
            out.text("usage: :unname-view <view>")
            return
        }
        val before = views.remove(name)
        reportStorageProblems(out)
        out.text(if (before == null) "no saved view $name — :name-view lists them" else "forgot @$name")
    }

    // ----------------------------------------------------------------- workspace

    /**
     * The session for [version], or the default one when the workspace was left on
     * a version this build cannot open. A version asked for on the command line is
     * not second-guessed: that failure is the caller's to report.
     */
    private fun startSession(version: FhirVersion?, explicit: Boolean, note: (String) -> Unit): Session {
        if (version == null || explicit) return Session(version ?: FhirVersion.DEFAULT)
        return try {
            Session(version)
        } catch (e: FhirVersionUnavailable) {
            note("${e.message} — starting on ${FhirVersion.DEFAULT.id} instead")
            Session(FhirVersion.DEFAULT)
        }
    }

    /**
     * Brings back what [state] describes, each part on its own: a path that has gone
     * is reported and kept, so it loads again once it is back; a focus step that no
     * longer resolves ends the focus where it got to.
     *
     * A session on another version than the one the workspace was left on gets
     * neither its pools nor its focus: a pool is one version, so they belong to the
     * other one, just as `:fhir` empties the pools. They stay saved until something
     * changes.
     */
    private fun restore(saved: SavedState, out: Output) {
        val leftOn = saved.fhir?.takeIf { it != session.version.id }
        val paths = saved.pools.sumOf { it.loads.size }
        val state = if (leftOn != null && saved.pools.any { it.loads.isNotEmpty() || it.focus.isNotEmpty() }) {
            out.error(
                "the workspace was left on ${leftOn.uppercase()}, so its $paths path(s) and focus " +
                    "are not restored in ${session.version.id.uppercase()} — saved until something changes",
            )
            saved.copy(pools = emptyList(), current = null)
        } else {
            saved
        }
        state.limit?.let {
            limitSet = it
            rowLimit = it
        }
        // All of them first, so what each one reports says which pool it is about.
        state.pools.forEach { session.open(it.name) }
        if (state.pools.isNotEmpty() && state.pools.none { it.name == Pool.DEFAULT }) session.close(Pool.DEFAULT)
        for (pool in state.pools) {
            session.switchTo(pool.name)
            if (pool.source != null) {
                if (openGit(pool.source, pool.at, pool.deltaScope, out)) {
                    restoreFocus(pool.focus, out)
                } else {
                    session.current.unavailable = pool
                    out.line { error("  kept in the workspace, to open once it is back") }
                }
                continue
            }
            for (path in pool.loads) {
                if (path !in session.current.loadedPaths) session.current.loadedPaths += path
                if (Files.exists(Paths.get(path))) {
                    load(path, out)
                } else {
                    val file = workspace.dir?.resolve("state")?.let { " (or drop it from $it)" }.orEmpty()
                    out.error("$path is missing${inPool()} — kept in the workspace, to load once it is back$file")
                }
            }
            restoreFocus(pool.focus, out)
        }
        session.switchTo(state.current ?: session.pools.first().name)
        // Whatever could not be restored stays saved: nothing is written until something changes.
        lastSaved = stateNow()
    }

    private fun restoreFocus(steps: List<String>, out: Output) {
        if (steps.isEmpty()) return
        var current: Focus? = null
        for (step in steps) {
            when (val resolution = session.focusResolver.resolve(step, current)) {
                is Resolution.Focused -> current = resolution.focus
                else -> {
                    out.error("focus restored only as far as ${current?.label ?: "the pool"} — $step no longer reaches one value")
                    break
                }
            }
        }
        session.focus = current
        current?.let { out.text("focus ${it.label}${inPool()}") }
    }

    /**
     * The `:focus` arguments that reach [focus] again, outermost first. A label is
     * its parent's plus the step that reached it, indexer included, so the step is
     * what follows the parent's label. A pool-wide element focus has no parent; it
     * goes by way of its resource, which `:focus ..` then steps back to.
     */
    private fun focusSteps(focus: Focus?): List<String> =
        generateSequence(focus) { it.parent }.toList().asReversed().flatMap { step ->
            val parent = step.parent
            when {
                step.isResource -> listOf(step.resource.label)
                parent == null -> listOf(step.resource.label, stepAfter(step.resource.label, step.label))
                else -> listOf(stepAfter(parent.label, step.label))
            }
        }

    /**
     * What [label] adds to [base]. A step that adds nothing, such as `:focus $this`,
     * leaves the label as it was; saving the label itself would replay as an
     * expression, so the step is saved as the one that changes nothing.
     */
    private fun stepAfter(base: String, label: String): String =
        if (label.startsWith("$base.")) label.removePrefix("$base.") else "\$this"

    private fun stateNow(): SavedState =
        SavedState(
            session.version.id,
            limitSet,
            session.pools.map { pool ->
                val git = pool.git
                pool.unavailable ?: SavedPool(
                    pool.name,
                    pool.loadedPaths.toList(),
                    focusSteps(pool.focus),
                    source = git?.spec,
                    at = git?.commit?.id?.name,
                    deltaScope = git?.deltaScope == true,
                )
            },
            session.current.name,
        )

    /** Writes the state when it has changed since it was last written or restored. */
    private fun saveState(out: Output) {
        val now = stateNow()
        if (now == lastSaved) return
        workspace.state.write(now)
        lastSaved = now
        reportStorageProblems(out)
    }

    /**
     * Points the line editor's up-arrow history at the workspace's file.
     *
     * JLine's `load()` only clears what it holds when the file exists, so a new
     * workspace would inherit the old one's lines -- and save them as its own. An
     * empty file first makes every switch start from what the workspace has. It is
     * created owner-only, as JLine warns on every start about one others can read.
     */
    private fun useLineHistory(reader: LineReader, out: Output) {
        val file = workspace.lineHistory ?: return
        if (file.parent?.let(Files::isDirectory) != true) {
            // Switching to a workspace does not create it, or a typo would stay as
            // one. Until something saved creates it, its lines are kept in memory;
            // purge() clears the old workspace's from memory, and with no file set
            // touches none on disk.
            reader.variables.remove(LineReader.HISTORY_FILE)
            reader.history.purge()
            historyPending = true
            return
        }
        historyPending = false
        reader.setVariable(LineReader.HISTORY_FILE, file)
        try {
            if (!Files.exists(file)) {
                if (file.fileSystem.supportedFileAttributeViews().contains("posix")) {
                    Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                } else {
                    Files.createFile(file)
                }
            }
            reader.history.load()
        } catch (e: java.io.IOException) {
            out.error("cannot use $file for line history: ${e.message}")
        }
    }

    /**
     * Once the workspace's directory exists, moves the lines typed so far into its
     * history file: they were typed in this workspace, so they belong to it.
     */
    private fun attachPendingHistory(reader: LineReader, out: Output) {
        if (!historyPending || workspace.lineHistory?.parent?.let(Files::isDirectory) != true) return
        val typed = reader.history.map { it.line() }
        useLineHistory(reader, out)
        typed.forEach { reader.history.add(it) }
    }

    /** `:workspace` shows the current one and lists the rest; `:workspace biobank` switches. */
    private fun switchWorkspace(argument: String, out: Output) {
        if (argument.isBlank()) {
            val where = workspace.dir?.let { "  ($it)" }.orEmpty()
            out.text("workspace ${workspace.name}$where")
            workspaces.available().filter { it != workspace.name }.takeIf { it.isNotEmpty() }?.let {
                out.text("  also: ${it.joinToString(", ")}")
            }
            return
        }
        if (!Workspaces.valid(argument)) {
            out.text("not a valid workspace name: $argument — ${KeyedStore.RULE}")
            return
        }
        if (argument == workspace.name) {
            out.text("already in workspace $argument")
            return
        }
        val next = workspaces.open(argument)
        // Before anything can create it.
        val isNew = argument !in workspaces.available()
        val saved = next.state.read()
        // As at startup: a version this build cannot open falls back to the default.
        val notes = mutableListOf<String>()
        val nextSession = startSession(saved.fhir?.let(FhirVersion::parse), explicit = false) { notes += it }
        // The old workspace's state was saved after the last command that changed it.
        reader?.history?.save()
        workspace = next
        session.close()
        session = nextSession
        lastPicks = null
        nameable = null
        lastListing = null
        lastExpression = null
        lastView = null
        limitSet = null
        rowLimit = windowRowLimit
        reader?.let { useLineHistory(it, out) }
        out.text(
            "workspace $argument, FHIR ${session.version.id.uppercase()}" +
                if (isNew) " — new, created once something is saved" else "",
        )
        notes.forEach { out.error(it) }
        reportStorageProblems(out)
        restore(saved, out)
    }

    // ---------------------------------------------------------------------- pool

    private fun load(path: String, out: Output) {
        session.current.git?.let {
            out.text("${poolName()} is ${it.source.path}@${it.lineLabel} — :at <rev> moves it; :open another pool to load files")
            return
        }
        val report = session.importer.load(Paths.get(expandHome(path)))
        // Absolute, so the next session finds it from wherever it is started.
        val absolute = Paths.get(expandHome(path)).toAbsolutePath().normalize().toString()
        val loaded = session.current.loadedPaths
        if (report.files > 0 && absolute !in loaded) loaded += absolute
        withProblems(out, "loaded ${report.summary()}; ${poolName()} now ${session.pool.size}", report.problems)
    }

    private fun poolSummary(out: Output) {
        if (session.pool.size == 0) {
            out.text("pool is empty")
            return
        }
        val census = session.pool.census
        val git = session.current.git
        val which = listOfNotNull(
            "pool ${session.current.name}".takeIf { session.pools.size > 1 },
            git?.let { "at ${it.commit.short} on ${it.lineLabel}" },
        ).joinToString(" ").let { if (it.isEmpty()) "" else "$it: " }
        val scoped = if (git?.deltaScope == true) "; scope: the delta's ${session.pool.inScope().size}" else ""
        out.listing(
            listOf(Output.text("$which${session.pool.size} resource(s), FHIR ${session.version.id.uppercase()}$scoped")),
            session.pool.typeCounts().map { (type, count) ->
                Output.line {
                    +"  "
                    type(type.padEnd(LABEL_WIDTH))
                    +" "
                    count(count.toString())
                }
            } +
                Output.text(
                    "census: ${census.extensions.size} extension url/path pair(s), " +
                        "${census.pathCounts.size} populated path(s)",
                ),
        )
    }

    /** `pool`, or `pool specimen` once there are several to tell apart. */
    private fun poolName(): String =
        if (session.pools.size > 1) "pool ${session.current.name}" else "pool"

    /** ` (pool specimen)`, once there are several pools to tell apart. */
    private fun inPool(): String =
        if (session.pools.size > 1) " (pool ${session.current.name})" else ""

    private fun listPools(out: Output) {
        val width = session.pools.maxOf { it.name.length }
        out.listing(
            header = emptyList(),
            rows = session.pools.map { pool ->
                Output.line {
                    +(if (pool === session.current) "* " else "  ")
                    label(pool.name.padEnd(width))
                    +"  "
                    count(pool.pool.size.toString().padStart(6))
                    +"  "
                    shrink(sourceOf(pool))
                    pool.focus?.let { hint("  focus ${abbreviate(it.label, PROMPT_WIDTH)}") }
                }
            },
            full = session.pools.flatMap { pool ->
                val paths = if (pool.git != null || pool.unavailable != null) listOf(sourceOf(pool)) else pool.loadedPaths
                listOf(
                    Output.line {
                        +(if (pool === session.current) "* " else "  ")
                        label(pool.name)
                        +"  "
                        count(pool.pool.size.toString())
                        +" resource(s)"
                    },
                ) + paths.map { Output.text("      $it") } + listOfNotNull(pool.focus?.let { Output.text("      focus ${it.label}") })
            },
        )
    }

    /** Rows from another pool cannot be picked here, so a switch forgets them. */
    private fun switchPool(name: String, out: Output) {
        when {
            session.pool(name) == null ->
                out.text("no pool $name — :pools lists them, :open $name <path> adds one")
            name == session.current.name -> out.text("already in pool $name")
            else -> {
                session.switchTo(name)
                lastPicks = null
                val focus = session.focus?.let { ", focus ${it.label}" }.orEmpty()
                out.text("pool $name: ${session.pool.size} resource(s)$focus")
            }
        }
    }

    /** `:open specimen` adds an empty pool; `:open specimen <path>` loads into it too. */
    private fun openPool(argument: String, out: Output) {
        val name = argument.substringBefore(' ').trim()
        val path = argument.substringAfter(' ', "").trim()
        when {
            name.isEmpty() -> out.text("usage: :open <name> [path]")
            !KeyedStore.valid(name) -> out.text("not a valid pool name: $name — ${KeyedStore.RULE}")
            session.pool(name) != null -> out.text("pool $name is already open — :pool $name switches to it")
            else -> {
                val before = session.current.name
                session.open(name)
                session.switchTo(name)
                lastPicks = null
                when {
                    path.isEmpty() -> out.text("pool $name — empty, :load <path> to import")
                    gitSpecOf(path) != null ->
                        if (!openGit(path, null, false, out)) {
                            session.close(name)
                            session.switchTo(before)
                        }
                    else -> {
                        load(path, out)
                        gitHint(path, out)
                    }
                }
            }
        }
    }

    private fun closePool(name: String, out: Output) {
        when {
            name.isBlank() -> out.text("usage: :close <name>")
            session.pool(name) == null -> out.text("no pool $name — :pools lists them")
            session.pools.size == 1 -> out.text("$name is the only pool — there is always one to evaluate against")
            else -> {
                val wasCurrent = name == session.current.name
                session.close(name)
                if (wasCurrent) lastPicks = null
                out.text("closed pool $name" + if (wasCurrent) " — now in pool ${session.current.name}" else "")
            }
        }
    }

    /**
     * A directory loads from disk as it is, uncommitted changes included, even inside a
     * repository: switching to git silently would change what the pool holds. So the
     * history is only pointed out.
     */
    private fun gitHint(path: String, out: Output) {
        val dir = runCatching { Paths.get(expandHome(path)) }.getOrNull() ?: return
        if (!Files.isDirectory(dir)) return
        val head = GitSource.historyAt(dir) ?: return
        val where = if (head.startsWith("HEAD ")) head else "on $head"
        out.hint("  in a git repository, $where — :open <name> ${path.trimEnd('/')}@ opens its history; TAB after the @ lists revisions")
    }

    /**
     * The current git pool's line, newest first, for TAB after `:at`: the time that
     * names each commit and its short id, the commit it sits at marked as `:log`
     * marks it, what it changed and its subject alongside. Internal so tests can
     * check what the menu would offer.
     */
    internal fun commitChoices(): List<FhirPathCompleter.CommitChoice> {
        val git = session.current.git ?: return emptyList()
        val commits = runCatching { git.history(MAX_CHOICES) }.getOrDefault(emptyList())
        val names = commitTimes.names(commits)
        val width = names.maxOfOrNull { it.length } ?: 0
        return commits.zip(names) { commit, name ->
            val marker = if (commit.id == git.commit.id) "*" else " "
            FhirPathCompleter.CommitChoice(commit.short, name, "$marker ${name.padEnd(width)}  ${commit.short}", commit.subject) {
                runCatching { git.changedFiles(commit) }.getOrNull()
            }
        }
    }

    /** How `:open` and its completion read a path argument; see [GitSource.revisionSpec]. */
    private fun gitSpecOf(token: String): Pair<String, String>? =
        GitSource.revisionSpec(token) { Files.exists(Paths.get(expandHome(it))) }

    private fun sourceOf(pool: Pool): String {
        pool.git?.let { return "${it.source.path}@${it.lineLabel} at ${it.commit.short}" }
        pool.unavailable?.let { return "unavailable: ${it.source}" }
        return pool.loadedPaths.joinToString(", ").ifEmpty { "nothing loaded" }
    }

    /**
     * Makes the current pool [spec]'s directory at a revision: [at] when it still
     * exists, else the tip of the line [spec] names.
     *
     * @return false when it could not be opened; what went wrong has been said.
     */
    private fun openGit(spec: String, at: String?, deltaScope: Boolean, out: Output): Boolean {
        val (path, revision) = GitSource.split(spec) ?: run {
            out.error("$spec names no revision — dir@rev, e.g. registry/in/import@branch/out")
            return false
        }
        val source = try {
            GitSource.open(Paths.get(expandHome(path)).toAbsolutePath().normalize())
        } catch (e: GitSourceException) {
            out.error("${e.message}")
            return false
        }
        // `dir@` follows whatever branch HEAD is on; the line saved is that branch, so
        // the pool keeps following it even after HEAD moves elsewhere.
        val line = revision.ifEmpty {
            source.headLine()?.also { head ->
                // A detached HEAD has no branch to follow, so the pool stays at its commit.
                if (!source.isBranch(head)) {
                    out.hint("  HEAD is detached — pinned at ${GitSource.label(head)}; :at <branch> to follow one")
                }
            } ?: run {
                source.close()
                out.error("${source.path} is in a repository with no commits yet")
                return false
            }
        }
        val left = at?.let { id ->
            source.resolve(id) ?: null.also {
                out.error("${id.take(7)} is gone from ${source.path} — opening the tip of $line instead")
            }
        }
        val start = left ?: source.resolve(line) ?: run {
            source.close()
            out.error("no commit $line in ${source.path}")
            return false
        }
        val git = GitPool(source, line, session.importer, session.pool)
        // Attached only once loaded: a pool whose revision could not be read must not
        // look like a git pool to the prompt or to the saved state.
        val report = try {
            git.load(start)
        } catch (e: Exception) {
            source.close()
            out.error("cannot read ${source.path} at ${start.short}: ${e.message ?: e.javaClass.simpleName}")
            return false
        }
        session.current.git = git
        session.current.unavailable = null
        git.deltaScope = deltaScope
        withProblems(out, "${poolName()} at ${revision(git)}: ${report.summary()}", report.problems)
        return true
    }

    /** [header], and under it any files that could not be read, held back past the row limit. */
    private fun withProblems(out: Output, header: String, problems: List<String>) {
        if (problems.isEmpty()) {
            out.text(header)
            return
        }
        out.listing(listOf(Output.text(header)), problems.map { problem -> Output.line { +"  "; error("! $problem") } })
    }

    /** `3f2a1c0 on branch/out, 2026-10-17 14:02 sync`. */
    private fun revision(git: GitPool): String =
        "${git.commit.short} on ${git.lineLabel}, ${COMMIT_TIME.format(git.commit.time)} ${git.commit.subject}"

    /** `Δ12: 2 added, 10 modified`. */
    private fun deltaSummary(git: GitPool): String {
        val delta = git.delta
        val parts = listOf(delta.added.size to "added", delta.modified.size to "modified", delta.deleted.size to "deleted")
            .filter { it.first > 0 }
            .joinToString(", ") { (count, what) -> "$count $what" }
        return "Δ${delta.size}" + if (parts.isEmpty()) ": nothing changed here" else ": $parts"
    }

    /** The current pool's git side, or null after saying why there is none. */
    private fun gitPool(out: Output): GitPool? = session.current.git ?: run {
        out.text("${poolName()} is not a git pool — :open <name> <dir>@<rev> opens one")
        null
    }

    private fun gitLog(argument: String, out: Output) {
        val git = gitPool(out) ?: return
        val words = argument.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val (skips, rest) = words.partition { it.startsWith("+") }
        val (limits, labels) = rest.partition { it.toIntOrNull() != null }
        val skip = skips.singleOrNull()?.removePrefix("+")?.toIntOrNull()?.takeIf { it >= 0 }
        val limit = limits.singleOrNull()?.toIntOrNull()?.takeIf { it > 0 }
        if (skips.size > 1 || limits.size > 1 || labels.size > 1 ||
            (skips.isNotEmpty() && skip == null) || (limits.isNotEmpty() && limit == null)
        ) {
            out.text(LOG_USAGE)
            return
        }
        val page = limit ?: DEFAULT_LOG
        val skipped = skip ?: 0
        labels.singleOrNull()?.let { label ->
            resourceLog(git, label, page, skipped, out)
            return
        }
        // A damaged repository throws from any read, as in a move: a history that
        // cannot be walked is an error, a commit whose changes cannot be read a Δ?.
        // One more than the page, to know whether there is an older one.
        val read = try {
            git.history(minOf(Int.MAX_VALUE.toLong(), skipped.toLong() + page + 1).toInt())
        } catch (e: Exception) {
            out.error("cannot read ${git.source.path}: ${e.message ?: e.javaClass.simpleName}")
            return
        }
        if (read.isEmpty()) {
            out.text("${git.lineLabel} names no commit any more")
            return
        }
        val commits = read.drop(skipped).take(page)
        if (commits.isEmpty()) {
            out.text("only ${read.size} commit(s) on ${git.lineLabel}")
            return
        }
        var unreadable = 0
        out.listing(
            header = listOf(Output.text("${git.lineLabel} in ${git.source.path}, newest first")),
            rows = commits.map { commit ->
                val count = runCatching { git.changedFiles(commit) }.getOrNull()
                if (count == null) unreadable++
                commitRow(git, commit) {
                    count("Δ${count ?: "?"}".padStart(6))
                    +"  "
                    shrink(commit.subject)
                }
            },
        )
        if (unreadable > 0) {
            out.line { error("  Δ? — what $unreadable commit(s) changed cannot be read from ${git.source.path}") }
        }
        if (commits.none { it.id == git.commit.id }) {
            out.hint("  (at ${git.commit.short}, which is not among these)")
        }
        if (read.size.toLong() > skipped.toLong() + page) {
            val size = if (page == DEFAULT_LOG) "" else "$page "
            out.hint("  older: :log $size+${skipped + page}")
        }
    }

    /** `* 3f2a1c0  2026-10-17 14:02  ` and then [rest], marked when the pool is at the commit. */
    private fun commitRow(git: GitPool, commit: GitSource.Commit, rest: Output.LineBuilder.() -> Unit): Output.Line = Output.line {
        +(if (commit.id == git.commit.id) "* " else "  ")
        index(commit.short)
        +"  ${COMMIT_TIME.format(commit.time)}  "
        rest()
    }

    /** What a resource's history could not be read for, or null when it can be. */
    private fun untraceable(label: String): String? = when {
        ResourceHistory.isLabel(label) -> null
        else -> "$label is not a label a history can follow — Type/id, as :pool and :delta show them"
    }

    /** `:log Specimen/s1`: the commits of the line that added, changed or deleted one resource. */
    private fun resourceLog(git: GitPool, label: String, page: Int, skipped: Int, out: Output) {
        untraceable(label)?.let {
            out.text(it)
            return
        }
        // One more than the page, to know whether there is an older one.
        val problems = mutableListOf<String>()
        val read = try {
            git.history(label) { problems += it }.take(minOf(Int.MAX_VALUE.toLong(), skipped.toLong() + page + 1).toInt()).toList()
        } catch (e: Exception) {
            out.error("cannot read ${git.source.path}: ${e.message ?: e.javaClass.simpleName}")
            return
        }
        problems.forEach { out.error(it) }
        if (read.isEmpty()) {
            out.text("no commit on ${git.lineLabel} added, changed or deleted $label")
            return
        }
        val changes = read.drop(skipped).take(page)
        if (changes.isEmpty()) {
            out.text("only ${read.size} commit(s) on ${git.lineLabel} touched $label")
            return
        }
        out.listing(
            header = listOf(Output.text("$label on ${git.lineLabel}, newest first")),
            rows = changes.map { change ->
                commitRow(git, change.commit) {
                    count(change.kind.name.lowercase().padEnd(8))
                    +"  "
                    shrink(change.commit.subject)
                }
            },
        )
        if (read.size.toLong() > skipped.toLong() + page) {
            val size = if (page == DEFAULT_LOG) "" else "$page "
            out.hint("  older: :log $label $size+${skipped + page}")
        }
    }

    /**
     * `:trace Specimen/s1 type.coding.code`: the expression's answer after each commit
     * that changed the resource, newest first, with what it was before -- a row only
     * where the answer changed, or the resource came or went. Read from the history
     * alone, so the pool stays where it is. With a focus, `:trace <expr>` traces the
     * focused resource, or the focused element by the steps that reached it.
     */
    private fun trace(argument: String, out: Output) {
        val git = gitPool(out) ?: return
        val first = argument.substringBefore(' ')
        val (label, expression) = if (ResourceHistory.isLabel(first)) {
            first to argument.substringAfter(' ', "").trim()
        } else {
            val focus = session.focus ?: run {
                out.text(TRACE_USAGE)
                return
            }
            focus.resource.label to (focusedPath(focus, argument) ?: run {
                out.text("${focus.label} was not reached by steps from ${focus.resource.label} — :trace ${focus.resource.label} <expr>")
                return
            })
        }
        untraceable(label)?.let {
            out.text(it)
            return
        }
        if (expression.isBlank()) {
            out.text(TRACE_USAGE)
            return
        }
        if (reportSyntaxError(expression, out)) return
        // The history is of one resource; what it references is read from the pool,
        // which is at another revision.
        if (Regex("""\bresolve\s*\(""").containsMatchIn(expression)) {
            out.text("resolve() would read the pool at ${git.commit.short}, not the revision traced — trace the reference itself")
            return
        }
        val problems = mutableListOf<String>()
        val changes = try {
            git.history(label) { problems += it }.toList()
        } catch (e: Exception) {
            out.error("cannot read ${git.source.path}: ${e.message ?: e.javaClass.simpleName}")
            return
        }
        // Before the rows: a resource that seems to come and go may be a file that broke.
        problems.forEach { out.error(it) }
        if (changes.isEmpty()) {
            out.text("no commit on ${git.lineLabel} added, changed or deleted $label")
            return
        }
        // Oldest first, so each answer can say what it replaced.
        var before: String? = null
        val rows = mutableListOf<Output.Line>()
        for (change in changes.asReversed()) {
            val resource = change.resource
            val outcome = resource?.let { session.evaluator.evaluate(it, expression) }
            val answer = when (outcome) {
                null -> "deleted"
                is Outcome.Failure -> "! ${outcome.message}"
                is Outcome.Values -> outcome.values.joinToString(", ") { session.render.value(it) }.ifEmpty { "{}" }
            }
            val came = change.kind == GitSource.Kind.ADDED
            if (answer == before && !came) continue
            val was = before
            rows += commitRow(git, change.commit) {
                when (outcome) {
                    null -> hint(answer)
                    is Outcome.Failure -> shrink(answer, Output.Kind.ERROR)
                    else -> shrink(answer)
                }
                if (came) hint("  (added)") else was?.let { shrink("  (was $it)", Output.Kind.HINT) }
            }
            before = answer
        }
        out.listing(
            header = listOf(Output.text("$label  $expression on ${git.lineLabel}, newest first — ${rows.size} change(s) in ${changes.size} commit(s) that touched it")),
            rows = rows.asReversed(),
        )
    }

    /**
     * [expression] as the focused resource would take it: the focus's own steps, and
     * the expression run on what they reach -- through `select()`, so a union or a
     * literal applies to the element rather than binding to the last step. Null for
     * a focus not reached by steps from its resource.
     */
    private fun focusedPath(focus: Focus, expression: String): String? {
        if (focus.isResource) return expression
        val steps = focus.label.takeIf { it.startsWith("${focus.resource.label}.") }
            ?.removePrefix("${focus.resource.label}.") ?: return null
        val relative = focus.step(expression)
        return if (relative.isEmpty()) steps else "($steps).select($relative)"
    }

    private fun at(revision: String, out: Output) {
        val git = gitPool(out) ?: return
        moved(git, git.at(revision), out)
    }

    private fun step(argument: String, out: Output) {
        val git = gitPool(out) ?: return
        val by = when {
            argument.isBlank() -> -1
            Regex("[+-]\\d{1,6}").matches(argument) -> argument.removePrefix("+").toInt()
            else -> {
                out.text("usage: :step [-n|+n]  —  -1 is one commit back, +1 one forward along ${git.lineLabel}")
                return
            }
        }
        if (by == 0) {
            out.text("at ${git.commit.short}")
            return
        }
        moved(git, git.step(by), out)
    }

    /**
     * Moving is how you ask what a run changed, so the scope goes to the delta. The
     * focus is found again in the new revision by the steps that reached it.
     */
    private fun moved(git: GitPool, move: GitPool.Move, out: Output) {
        when (move) {
            is GitPool.Move.Refused -> out.text(move.message)
            is GitPool.Move.Failed -> out.error(move.message)
            is GitPool.Move.Moved -> {
                val steps = focusSteps(session.focus)
                session.focus = null
                lastPicks = null
                git.deltaScope = true
                move.note?.let { out.text(it) }
                withProblems(out, "${poolName()} at ${revision(git)} — ${deltaSummary(git)}", move.report.problems)
                restoreFocus(steps, out)
            }
        }
    }

    private fun delta(out: Output) {
        val git = gitPool(out) ?: return
        val delta = git.delta
        val against = delta.parent?.let { "against ${it.short}" } ?: "against nothing: the first commit"
        val header = "${git.commit.short} $against — ${deltaSummary(git)}"
        if (delta.size == 0) {
            out.text(header)
            return
        }
        fun row(sign: String, label: String, origin: String?) = Output.line {
            +"  $sign "
            label(label.padEnd(LABEL_WIDTH))
            origin?.let { hint(" ($it)") }
        }
        out.listing(
            listOf(Output.text(header)),
            delta.added.map { row("+", it.label, it.origin) } +
                delta.modified.map { row("~", it.label, it.origin) } +
                delta.deleted.map { row("-", it, null) },
        )
    }

    private fun scope(argument: String, out: Output) {
        val git = session.current.git
        when (argument.lowercase()) {
            "" -> out.text(
                if (git?.deltaScope == true) {
                    "scope: the delta, ${session.pool.inScope().size} of ${session.pool.size} resource(s) — :scope all for everything"
                } else {
                    "scope: all ${session.pool.size} resource(s)" + if (git != null) " — :scope delta for what ${git.commit.short} changed" else ""
                },
            )
            "delta" -> {
                val pool = gitPool(out) ?: return
                pool.deltaScope = true
                out.text("scope: the delta, ${session.pool.inScope().size} resource(s) — ${deltaSummary(pool)}")
            }
            "all" -> {
                git?.deltaScope = false
                out.text("scope: all ${session.pool.size} resource(s)")
            }
            else -> out.text("usage: :scope [delta|all]")
        }
    }

    /**
     * A label or `#index` picks a resource; anything else is an expression, run
     * against the current focus (or across the pool when there is none), whose one
     * result becomes the focus. Each new focus remembers the last, for `:focus ..`.
     * The rules live in [FocusResolver]; this only reports.
     */
    private fun focus(argument: String, out: Output) {
        val current = session.focus
        if (argument.isBlank()) {
            session.focus = null
            // Clears the lot: pool-wide rows would otherwise come back into play.
            lastPicks = null
            out.text("focus cleared — expressions now run across the pool")
            return
        }
        if (argument == "..") {
            session.focus = current?.parent
            when {
                current == null -> out.text("nothing in focus")
                current.parent == null -> out.text("focus cleared — expressions now run across the pool")
                else -> announceFocus(current.parent, out)
            }
            return
        }
        val resolution = session.focusResolver.resolve(argument, current, lastPicks)
        // An expression here is an evaluation too, and not a recorded one; a label,
        // #index or pick runs nothing and leaves the last expression nameable.
        if (resolution.evaluated) nameable = null
        lastPicks = when (resolution) {
            is Resolution.Several -> resolution.picks
            // Picking, or failing to, leaves the same rows on offer for the next pick.
            is Resolution.CannotPick -> lastPicks
            is Resolution.Focused -> lastPicks.takeIf { resolution.picked }
            else -> null
        }
        when (resolution) {
            is Resolution.Focused -> {
                session.focus = resolution.focus
                announceFocus(resolution.focus, out)
            }
            is Resolution.NotInPool -> out.text("not in pool: ${resolution.argument}")
            is Resolution.CannotPick -> out.text(resolution.reason)
            is Resolution.Invalid ->
                out.error("not in pool, and not a valid expression: ${resolution.message}")
            is Resolution.Failed -> out.error("${resolution.message}")
            is Resolution.Nothing -> {
                // The hint points at :why, which should not need the expression retyped.
                lastExpression = argument
                val label = if (resolution.couldBeLabel) "not a pool label, and " else ""
                out.text("$label$argument yields nothing — :why")
            }
            is Resolution.Several -> {
                val results = resolution.picks.results
                val byResource = resolution.owners > 1
                val counted = if (byResource) "from ${resolution.owners} resources" else ""
                out.listing(
                    header = listOf(
                        Output.text(
                            listOf("${results.size} values", counted).filter { it.isNotEmpty() }.joinToString(" ") +
                                "; focus needs one — pick it with  :focus [n]",
                        ),
                    ),
                    rows = results.mapIndexed { idx, found ->
                        valueRow(found.value, idx, found.owner.label.takeIf { byResource })
                    },
                    full = results.flatMapIndexed { idx, found ->
                        expanded("  [$idx] ${if (byResource) found.owner.label + "  " else ""}", found.value)
                    },
                )
            }
        }
    }

    /**
     * A listing of one, so `:more` straight after `:focus` shows the focused value
     * whole. A long element path is exactly when you want to see what it landed on.
     */
    private fun announceFocus(focus: Focus, out: Output) {
        val what = if (focus.isResource) focus.resource.origin else "${focus.typeName} in ${focus.resource.origin}"
        out.listing(
            header = listOf(Output.text("focus ${focus.label}  ($what)")),
            rows = listOf(valueRow(focus.node)),
            full = expanded("  ", focus.node),
        )
    }

    private fun extensions(argument: String, out: Output) {
        val census = session.pool.census
        if (census.extensions.isEmpty()) {
            out.text("no extensions observed in the pool")
            return
        }
        val rows = if (argument.isBlank()) {
            census.extensions.entries.sortedByDescending { it.value.count }
                .map { Triple(it.key.path, it.key.url, it.value) }
        } else {
            census.extensions.entries.filter { it.key.path.startsWith(argument) }
                .sortedByDescending { it.value.count }
                .map { Triple(it.key.path, it.key.url, it.value) }
        }
        if (rows.isEmpty()) {
            out.text("no extensions observed at $argument")
            return
        }
        out.listing(
            header = emptyList(),
            rows = rows.map { (path, url, stat) ->
                Output.line {
                    +"  ${path.padEnd(34)} "
                    count(stat.count.toString().padStart(5))
                    +" ×  "
                    url(url)
                    +"  "
                    type(stat.valueTypes.joinToString("|"))
                }
            },
        )
    }

    private fun switchVersion(argument: String, out: Output) {
        if (argument.isBlank()) {
            out.text("FHIR ${session.version.id} — available: ${FhirVersion.ids()}")
            return
        }
        val version = FhirVersion.parse(argument) ?: run {
            out.text("unknown version $argument — available: ${FhirVersion.ids()}")
            return
        }
        if (version == session.version) {
            out.text("already on ${version.id}")
            return
        }
        try {
            // The pools keep their names and lose their contents, and with them the
            // paths the workspace would load.
            val old = session
            session = Session(version, old.pools.map { it.name })
            // Rows from the old pool; a pool-wide pick would otherwise still look valid.
            lastPicks = null
            val reset = when {
                old.pools.none { it.git != null } && session.pools.size > 1 -> "${session.pools.size} pools emptied"
                old.pools.none { it.git != null } -> "pool reset"
                else -> "loaded pools emptied, git pools read again"
            }
            out.text("switched to FHIR ${version.id.uppercase()} — $reset")
            // A git pool's contents are its revision, so it reads that again in the new version.
            for (pool in old.pools) {
                session.switchTo(pool.name)
                session.current.unavailable = pool.unavailable
                val git = pool.git ?: continue
                if (!openGit(git.spec, git.commit.id.name, git.deltaScope, out)) {
                    // Kept, as at startup: saved again rather than lost, to open once it can.
                    session.current.unavailable =
                        SavedPool(pool.name, source = git.spec, at = git.commit.id.name, deltaScope = git.deltaScope)
                }
            }
            session.switchTo(old.current.name)
            old.close()
        } catch (e: FhirVersionUnavailable) {
            out.error("${e.message}")
        }
    }

    private fun help(out: Output) {
        out.text("Type a FHIRPath expression, or:")
        val usages = commands.map { "  ${it.name} ${it.args}" }
        val width = usages.maxOf { it.length } + 2
        commands.zip(usages).forEach { (command, usage) -> out.text(usage.padEnd(width) + command.doc) }
        out.text("")
        out.text("With a focus set, an expression runs against that resource;")
        out.text("with no focus it runs across the whole pool.")
        out.text("")
        out.text(":focus also takes an expression: it runs against the current focus")
        out.text("(or the pool) and its one result becomes the focus, which may be an")
        out.text("element. Expressions then start from that element — coding.code,")
        out.text("not Observation.code.coding — and :focus .. steps back up.")
        out.text(":more right after :focus prints the focused value in full.")
        out.text(":focus [n] picks row n of the last numbered listing, e.g. when")
        out.text("an expression matched several values.")
        out.text("")
        out.text(":why with no expression re-diagnoses the last one you ran, so the")
        out.text("hint printed after an empty result can be followed by just :why.")
        out.text("")
        out.text("A pool-wide expression that matches something is recorded, once, in")
        out.text("the workspace; :history lists them by number. :view #3 #5")
        out.text("tabulates them: one row per resource of the type they start from,")
        out.text("one column each. Columns must yield primitives, e.g. name.family")
        out.text("rather than name; several values in one cell are joined with \", \".")
        out.text(":name-expr family right after a pool-wide expression names it;")
        out.text(":name-expr family #1 names a recorded one. Then :view family birth #3")
        out.text("works and the column is titled family. A name holds the expression")
        out.text("itself, not its number.")
        out.text(":name-view people saves the last :view; :view @people reruns it.")
        out.text("")
        out.text("Several pools can be open at once, each with its own census and focus;")
        out.text("resolve() stays inside the pool. :open specimen <path> adds one and")
        out.text("switches to it, :pool <name> switches, :pools lists them. Recorded")
        out.text("expressions, names and views are shared, so :view @people runs in any.")
        out.text("")
        out.text("A pool can be a directory in a git repository at a revision, read")
        out.text("without checking anything out: :open in registry/in/import@branch/out.")
        out.text("A bare @ opens the branch HEAD is on; TAB after the @ lists revisions.")
        out.text("TAB after :at lists the line's commits by date, newest first.")
        out.text(":log lists its line, :step -1 and :step +1 move along it, :at <rev>")
        out.text("jumps. Moving sets the scope to the delta, so pool-wide expressions and")
        out.text(":view run over what that commit changed, while resolve() still sees")
        out.text("everything; :delta lists it, :scope all goes back to the whole pool.")
        out.text(":log Specimen/s1 lists the commits that changed one resource, and")
        out.text(":trace Specimen/s1 type.coding.code how an answer changed across them.")
        out.text("")
        out.text("A workspace keeps all of that, the up-arrow history, and where the")
        out.text("session was left: FHIR version, pools, :limit and focus, all")
        out.text("restored at the next start. The default is ~/.fhir-walk; start with")
        out.text("--workspace <name>, or :workspace <name> to switch. --fresh starts")
        out.text("empty and keeps the saved state until something changes.")
        out.text("")
        out.text("A listing fills the window and keeps the rest for :more, which")
        out.text("reprints it whole with no value shortened. :limit off prints")
        out.text("everything up front and lets the terminal scroll.")
        out.text("")
        out.text("TAB completes, including after :all and :why. On an empty line it")
        out.text("lists the pool's resource types; after a '.' the elements and")
        out.text("functions available there, with the ones present in the pool first;")
        out.text("inside extension('…') the URLs observed on that element.")
        out.text("Up/Down or further TABs walk the candidates; Enter takes the one shown.")
    }

    private fun expandHome(path: String): String =
        if (path.startsWith("~")) System.getProperty("user.home") + path.drop(1) else path

    private companion object {
        const val UNLIMITED = 0
        const val STEP_WIDTH = 48
        const val LABEL_WIDTH = 28
        const val PROMPT_WIDTH = 48
        const val MIN_VALUE_WIDTH = Printer.MIN_VALUE_WIDTH
        const val MIN_ROW_LIMIT = 8
        /** Used until the terminal reports its size; a dumb terminal never does. */
        const val DEFAULT_ROW_LIMIT = 20
        const val DEFAULT_COLUMNS = 100
        const val DEFAULT_LOG = 20
        /** Commits searched for TAB after `:at`: enough to narrow by id in a long history. */
        const val MAX_CHOICES = 2000
        const val LOG_USAGE = "usage: :log [label] [n] [+skip]  —  :log 10 +20 is the 21st to 30th commit back; :log Specimen/s1 those that changed it"
        const val TRACE_USAGE = "usage: :trace <label> <expr>  —  or :trace <expr> with a focus, e.g. :trace Specimen/s1 type.coding.code"

        /** The naming commands' old spellings, before each said what it names (#29). */
        val RENAMED = mapOf(
            ":name" to ":name-expr",
            ":unname" to ":unname-expr",
            ":save" to ":name-view",
            ":unsave" to ":unname-view",
        )

        val COMMIT_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

        /**
         * The two encodings an arrow key arrives in, depending on whether
         * application-cursor-keys mode took effect: CSI (ESC [ A) or SS3 (ESC O A).
         */
        const val ESC_CSI = "\u001b["
        const val ESC_SS3 = "\u001bO"
    }
}
