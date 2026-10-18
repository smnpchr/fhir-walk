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

    /** The columns of the last `:view`, for `:save`. */
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
        FhirPathCompleter.CommandInfo(":log", "[n]", "a git pool's line, newest first, with what each commit changed"),
        FhirPathCompleter.CommandInfo(":at", "<rev>", "move a git pool to a revision: a branch, tag, id or branch~2"),
        FhirPathCompleter.CommandInfo(":step", "[-n|+n]", "move a git pool along its line; alone, one commit back"),
        FhirPathCompleter.CommandInfo(":delta", "", "what this revision added, modified and deleted"),
        FhirPathCompleter.CommandInfo(":scope", "[delta|all]", "run pool-wide over the delta only, or over everything"),
        FhirPathCompleter.CommandInfo(":focus", "[label|#index|expr|[n]|..]", "evaluate against one resource or element"),
        FhirPathCompleter.CommandInfo(":all", "<expr>", "evaluate across the whole pool"),
        FhirPathCompleter.CommandInfo(":why", "[expr]", "find the step where the expression goes empty"),
        FhirPathCompleter.CommandInfo(":ext", "[path]", "extension URLs observed in the pool"),
        FhirPathCompleter.CommandInfo(":history", "[filter]", "recorded pool-wide expressions, newest first"),
        FhirPathCompleter.CommandInfo(":view", "#n|name|@view …", "a table: one row per resource, a column per recorded expression"),
        FhirPathCompleter.CommandInfo(":name", "[name [#n]]", "name the expression just run, or #n, for :view; alone, list"),
        FhirPathCompleter.CommandInfo(":unname", "<name>", "forget a name"),
        FhirPathCompleter.CommandInfo(":save", "[view]", "save the last :view, to rerun as :view @view; alone, list"),
        FhirPathCompleter.CommandInfo(":unsave", "<view>", "forget a saved view"),
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
     * A printed listing, kept so `:more` can show it in full.
     *
     * [preview] is trimmed to fit the screen, both in rows and in the width of each
     * rendered value; [full] holds every row with nothing shortened. Truncating
     * without a way back to the whole thing is worse than printing it all.
     * [fullHeader] goes with [full], for a header whose layout follows the rows.
     */
    private class Listing(
        val header: String?,
        val preview: List<String>,
        val full: List<String>,
        val fullHeader: String? = header,
    )

    private var lastListing: Listing? = null

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
     * What `:name <name>` with no number names: the expression just run, if it was
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
                { dir -> GitSource.refNamesAt(Paths.get(expandHome(dir))) },
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
        useLineHistory(reader, out)
        bindArrowKeys(reader)
        style = Style.detect(terminal)

        // Fill the window and keep the rest for :more, rather than a fixed guess.
        if (terminal.height > MIN_ROW_LIMIT + 8) rowLimit = terminal.height - 8
        windowRowLimit = rowLimit
        if (terminal.width > MIN_VALUE_WIDTH) columns = terminal.width

        out.println("fhir-walk — FHIRPath over a local resource pool.  :help for commands")
        open(out)
        if (session.pool.size == 0) {
            out.println("pool is empty — :load <path> to import")
        } else {
            out.println("TAB on an empty line lists the resource types in the pool")
        }
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
            attachPendingHistory(reader, out)
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
    internal fun open(out: PrintWriter) {
        startupNotes.forEach { out.println(style.error("! $it")) }
        startupNotes.clear()
        val where = if (workspace.name == Workspaces.DEFAULT) "" else "workspace ${workspace.name}, "
        out.println("${where}FHIR ${session.version.id.uppercase()}")
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
        if (!input.startsWith(":")) {
            // `all Specimen.subject` is a dropped colon, not an expression. Saying so
            // beats handing the whole line to the FHIRPath parser -- unless the word is
            // an element where the expression runs: `name` is a command and, far more
            // often, Patient.name.
            val firstWord = input.substringBefore(' ')
            if (":$firstWord" in commandNames && !(isElementHere(firstWord) && parses(input))) {
                out.println(style.error("! not a FHIRPath expression — did you mean  :$input"))
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
    private fun command(command: String, argument: String, out: PrintWriter): Boolean {
        when (command) {
            ":quit", ":exit", ":q" -> return false
            ":help", ":?" -> help(out)
            ":load" -> if (argument.isBlank()) out.println("usage: :load <path>") else load(argument, out)
            ":pool" -> if (argument.isBlank()) poolSummary(out) else switchPool(argument, out)
            ":pools" -> listPools(out)
            ":open" -> openPool(argument, out)
            ":close" -> closePool(argument, out)
            ":log" -> gitLog(argument, out)
            ":at" -> if (argument.isBlank()) out.println("usage: :at <rev>") else at(argument, out)
            ":step" -> step(argument, out)
            ":delta" -> delta(out)
            ":scope" -> scope(argument, out)
            ":focus" -> focus(argument, out)
            ":all" -> if (argument.isBlank()) {
                out.println("usage: :all <expr>")
            } else {
                lastExpression = argument
                evaluateAll(argument, out)
            }
            ":why" -> why(argument, out)
            ":ext" -> extensions(argument, out)
            ":history" -> history(argument, out)
            ":view" -> view(argument, out)
            ":name" -> name(argument, out)
            ":unname" -> unname(argument, out)
            ":save" -> saveView(argument, out)
            ":unsave" -> unsaveView(argument, out)
            ":workspace" -> switchWorkspace(argument, out)
            ":more" -> more(out)
            ":limit" -> limit(argument, out)
            ":fhir" -> switchVersion(argument, out)
            else -> out.println("unknown command $command — :help for the list")
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

    private fun evaluate(expression: String, out: PrintWriter) {
        lastPicks = null
        nameable = null
        if (reportSyntaxError(expression, out)) return
        val focus = session.focus
        if (focus == null) {
            if (session.pool.size == 0) {
                out.println("pool is empty — :load <path> first")
                return
            }
            // No focus: the pool is the subject. This is the default on purpose.
            evaluateAll(expression, out)
            return
        }
        when (val outcome = session.evaluator.evaluate(focus.node, expression)) {
            is Outcome.Failure -> out.println(style.error("! ${outcome.message}"))
            is Outcome.Values -> {
                if (outcome.values.isEmpty()) {
                    out.println("{} empty — :why")
                    return
                }
                lastPicks = session.focusResolver.picksOf(expression, focus, outcome.values)
                emit(
                    out,
                    header = null,
                    preview = outcome.values.mapIndexed { idx, value -> valueRow(value, idx) },
                    full = outcome.values.flatMapIndexed { idx, value ->
                        expanded("  [$idx] ", value)
                    },
                )
            }
        }
    }

    // ------------------------------------------------------------------- listings

    /**
     * Prints a listing, holding back whatever does not fit for `:more`.
     *
     * @param full every row with nothing shortened; defaults to [preview] for
     *   listings whose rows were never width-trimmed in the first place.
     * @param fullHeader what `:more` prints above [full]; a table's column titles
     *   are as wide as its columns, which differ between the two.
     */
    private fun emit(
        out: PrintWriter,
        header: String?,
        preview: List<String>,
        full: List<String> = preview,
        fullHeader: String? = header,
    ) {
        lastListing = Listing(header, preview, full, fullHeader)
        header?.let { out.println(it) }
        val shown = if (rowLimit <= 0) preview.size else minOf(rowLimit, preview.size)
        for (index in 0 until shown) out.println(preview[index])
        val hidden = preview.size - shown
        when {
            hidden > 0 -> out.println(style.hint("  … $hidden more of ${preview.size} — :more"))
            // Nothing was held back by row count, but a value may still have been
            // shortened to fit the window; say so rather than leaving a dangling "…".
            preview != full && preview.any { it.contains('…') } ->
                out.println(style.hint("  … shortened to fit — :more"))
        }
    }

    private fun more(out: PrintWriter) {
        val listing = lastListing ?: run {
            out.println("nothing listed yet")
            return
        }
        listing.fullHeader?.let { out.println(it) }
        listing.full.forEach { out.println(it) }
    }

    private fun limit(argument: String, out: PrintWriter) {
        when {
            argument.isBlank() ->
                out.println(
                    "rows per listing: " + if (rowLimit <= 0) "unlimited" else rowLimit.toString(),
                )
            argument.equals("off", ignoreCase = true) || argument == "0" -> {
                rowLimit = UNLIMITED
                limitSet = UNLIMITED
                out.println("row limit off — listings print in full and the terminal scrolls")
            }
            else -> {
                val rows = argument.toIntOrNull()
                if (rows == null || rows < 1) {
                    out.println("usage: :limit <rows|off>")
                } else {
                    rowLimit = rows
                    limitSet = rows
                    out.println("rows per listing: $rows")
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

    /** Width left for a rendered value after its label, or unlimited if unknown. */
    private fun valueWidth(indent: Int): Int = (columns - indent).coerceAtLeast(MIN_VALUE_WIDTH)

    /**
     * One value on one line, shortened to fit: `[idx] Type  value`.
     *
     * @param label adds a resource column, for rows drawn from several resources.
     */
    private fun valueRow(value: IBase, index: Int? = null, label: String? = null): String {
        val idx = index?.let { style.index("[$it]") + " " }.orEmpty()
        val owner = label?.let { style.label(it.padEnd(LABEL_WIDTH)) + " " }.orEmpty()
        val indent = VALUE_INDENT + if (label != null) LABEL_WIDTH else 0
        return "  $idx$owner${style.type(session.render.typeOf(value))}  " +
            style.value(session.render.value(value, valueWidth(indent)), keysOnly = true)
    }

    /**
     * One value in full: a primitive stays on the label's line, while anything
     * structured gets pretty-printed JSON underneath it.
     */
    private fun expanded(label: String, value: IBase): List<String> {
        val head = style.index(label) + style.type(session.render.typeOf(value))
        val lines = session.render.pretty(value)
        if (lines.size == 1) return listOf("$head  ${style.value(lines.first())}")
        val indent = " ".repeat(label.length + 2)
        return listOf(head) + lines.map { indent + style.json(it) }
    }

    /**
     * A syntax error belongs to the expression, not to the data: report it once,
     * rather than once per resource in the pool.
     *
     * @return true when the expression could not be parsed and nothing should run.
     */
    private fun reportSyntaxError(expression: String, out: PrintWriter): Boolean {
        val error = session.evaluator.validate(expression) ?: return false
        out.println(style.error("! $error"))
        // FHIRPath strings are single-quoted; " delimits an identifier, so a
        // double-quoted URL fails with a message that never mentions quoting.
        if (expression.contains('"')) {
            out.println("  FHIRPath string literals use single quotes — try '…' rather than \"…\"")
        }
        return true
    }

    /** Not numbered: rows are resources, and an indexer would not pick between them. */
    private fun evaluateAll(expression: String, out: PrintWriter) {
        lastPicks = null
        // Before the syntax check, so a failed :all leaves nothing behind to name.
        nameable = null
        if (reportSyntaxError(expression, out)) return
        val result = session.evaluator.evaluateAll(expression)
        val header = "${result.hits.size} of ${result.considered} resource(s) matched" +
            if (result.errors > 0) style.error("; ${result.errors} error(s): ${result.firstError}") else ""
        // Matching something is the bar: a misspelt element parses fine and only
        // ever comes back empty, and would otherwise clutter the log.
        nameable = expression.takeIf { result.hits.isNotEmpty() }
        if (result.hits.isNotEmpty()) record(expression, out)
        if (result.hits.isEmpty()) {
            out.println(header)
            if (result.errors == 0) {
                // Nothing matched anywhere, and it parsed: the question is which step
                // goes empty, which is exactly what :why answers.
                out.println("  nothing matched — :why")
            }
            lastListing = null
            return
        }
        emit(
            out,
            header,
            preview = result.hits.map { hit ->
                val first = hit.values.firstOrNull()
                    ?.let { session.render.value(it, valueWidth(LABEL_WIDTH + VALUE_INDENT)) }
                    ?.let { style.value(it, keysOnly = true) }
                    ?: ""
                val extra = if (hit.values.size > 1) style.hint(" (+${hit.values.size - 1})") else ""
                "  ${style.label(hit.resource.label.padEnd(LABEL_WIDTH))} $first$extra"
            },
            // The full form keeps every value whole and adds the origin, so one
            // listing answers both "what matched" and "where did it come from".
            full = result.hits.flatMap { hit ->
                listOf("  ${style.label(hit.resource.label)}  ${style.hint("(${hit.resource.origin})")}") +
                    hit.values.flatMap { expanded("      ", it) }
            },
        )
    }

    /** @param argument the expression, or blank to re-diagnose [lastExpression]. */
    private fun why(argument: String, out: PrintWriter) {
        val expression = argument.ifBlank { lastExpression ?: "" }
        if (expression.isBlank()) {
            out.println("usage: :why <expr>  —  or :why alone, to re-run the last expression")
            return
        }
        lastExpression = expression
        if (reportSyntaxError(expression, out)) return
        if (session.pool.size == 0) {
            out.println("pool is empty")
            return
        }
        val root = FhirPathTokenizer.segments(expression)
            .firstOrNull()?.name?.takeIf { it.isNotBlank() }
        // Diagnosing a Specimen expression against a Patient reports "empty at step
        // one" and teaches nothing, so the subject must match the expression's root.
        if (root != null && session.navigator.resourceTypes().contains(root) &&
            session.pool.ofType(root).isEmpty()
        ) {
            out.println("no $root in the pool — it holds ${session.pool.types().joinToString(", ")}")
            return
        }
        val focus = session.focus
            ?: (session.prefixWalker.diagnosisTarget(expression) ?: session.pool.all().first()).let { Focus.of(it) }
        if (session.focus == null) out.println("(no focus; diagnosing against ${focus.label})")
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
            // Colour after padding, so the escape codes do not count towards the width.
            val cell = count.padEnd(5).let { if (step.error != null) style.error(it) else it }
            out.println("  ${abbreviate(label, STEP_WIDTH).padEnd(STEP_WIDTH)} $cell$marker")
        }
        if (diagnosis.firstEmpty == null) {
            out.println("  path resolves fully")
            return
        }
        diagnosis.poolCoverage?.let { coverage ->
            out.println(
                "  ${abbreviate(coverage.expression, 60)} is populated in ${coverage.matching} of " +
                    "${coverage.considered} resource(s) of that type",
            )
            if (coverage.matching == 0) {
                out.println("  nothing in the pool populates it — check the element name or the data")
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
    private fun reportStorageProblems(out: PrintWriter) {
        log.entries()
        names.all()
        views.all()
        workspace.problems.filter { reportedProblems.add(it) }.forEach {
            out.println(style.error("! $it — changes are kept for this session only"))
        }
    }

    private fun record(expression: String, out: PrintWriter) {
        log.record(expression)
        reportStorageProblems(out)
    }

    private fun history(argument: String, out: PrintWriter) {
        val entries = log.entries()
        reportStorageProblems(out)
        val numbered = entries.mapIndexed { idx, expression -> idx + 1 to expression }
            .filter { (_, expression) -> expression.contains(argument, ignoreCase = true) }
            .asReversed()
        if (numbered.isEmpty()) {
            out.println(
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
        fun row(number: Int, expression: String, shorten: Boolean): String {
            val named = namesOf[expression].orEmpty().joinToString(", ").let { if (it.isEmpty()) "" else "  ($it)" }
            val text = if (shorten) abbreviate(expression, valueWidth(width + 4 + named.length)) else expression
            return "  ${style.index("#$number".padEnd(width))}  $text${style.hint(named)}"
        }
        emit(
            out,
            header = null,
            preview = numbered.map { (number, expression) -> row(number, expression, shorten = true) },
            full = numbered.map { (number, expression) -> row(number, expression, shorten = false) },
        )
    }

    /**
     * `:name` lists, `:name family` names the expression just run, `:name family #1`
     * names a recorded one. A name may hold any expression; only `:view` asks for
     * primitives, and says so when it is used.
     */
    private fun name(argument: String, out: PrintWriter) {
        val parts = argument.split(Regex("\\s+")).filter { it.isNotEmpty() }
        reportStorageProblems(out)
        when (parts.size) {
            0 -> {
                val all = names.all()
                if (all.isEmpty()) {
                    out.println("no names yet — :name <name> #n names a recorded expression")
                    return
                }
                val width = all.keys.maxOf { it.length }
                emit(
                    out,
                    header = null,
                    preview = all.map { (name, expression) ->
                        "  ${style.label(name.padEnd(width))}  " + abbreviate(expression, valueWidth(width + 4))
                    },
                    full = all.map { (name, expression) -> "  ${style.label(name.padEnd(width))}  $expression" },
                )
            }
            1, 2 -> {
                val name = parts[0]
                if (!NameStore.valid(name)) {
                    out.println("not a valid name: $name — ${KeyedStore.RULE}")
                    return
                }
                val expression = if (parts.size == 1) {
                    nameable ?: run {
                        out.println(
                            "nothing to name — run a pool-wide expression that matches something first, " +
                                "or :name $name #n for one from :history",
                        )
                        return
                    }
                } else {
                    val number = parts[1].removePrefix("#").toIntOrNull() ?: run {
                        out.println("usage: :name <name> [#n]  —  #n from :history")
                        return
                    }
                    log.get(number) ?: run {
                        out.println("nothing recorded as #$number — see :history")
                        return
                    }
                }
                val before = names.set(name, expression)
                reportStorageProblems(out)
                out.println(
                    when (before) {
                        null -> "$name  $expression"
                        expression -> "$name already names $expression"
                        else -> "$name  $expression  (was $before)"
                    },
                )
            }
            else -> out.println("usage: :name [name [#n]]")
        }
    }

    private fun unname(argument: String, out: PrintWriter) {
        if (argument.isBlank()) {
            out.println("usage: :unname <name>")
            return
        }
        val before = names.remove(argument)
        reportStorageProblems(out)
        out.println(if (before == null) "no name $argument — :name lists them" else "forgot $argument  ($before)")
    }

    /** @param argument recorded expressions by number or name: `#3 family`, `3, 5`. */
    private fun view(argument: String, out: PrintWriter) {
        val refs = argument.split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
        if (refs.isEmpty()) {
            out.println("usage: :view #n|name|@view …  —  numbers from :history, names from :name, views from :save")
            return
        }
        reportStorageProblems(out)
        val picked = refs.flatMap { ref ->
            if (ref.startsWith("@")) {
                return@flatMap savedColumns(ref.drop(1)) ?: run {
                    out.println("no saved view ${ref.drop(1)} — :save lists them")
                    return
                }
            }
            val number = ref.removePrefix("#").toIntOrNull()
            if (number != null) {
                val expression = log.get(number) ?: run {
                    out.println("nothing recorded as #$number — see :history")
                    return
                }
                listOf(ViewBuilder.Column(expression, number = number))
            } else {
                val expression = names.get(ref) ?: run {
                    out.println("no :history number or name $ref — see :history and :name")
                    return
                }
                listOf(ViewBuilder.Column(expression, name = ref))
            }
        }
        val table = when (val result = session.viewBuilder.build(picked)) {
            is ViewBuilder.Result.Refused -> {
                out.println(style.error("! ${result.message}"))
                return
            }
            is ViewBuilder.Result.Table -> result
        }
        lastView = picked
        val titles = table.columns.map { it.title(table.type) }
        val labelWidth = table.rows.maxOf { it.resource.label.length }
        // In full each column is as wide as its widest cell; the preview shares out
        // what the window leaves after the labels.
        val natural = titles.indices.map { idx -> maxOf(titles[idx].length, table.rows.maxOf { it.cells[idx].length }) }
        val share = ((columns - 2 - labelWidth) / titles.size - COLUMN_GAP).coerceAtLeast(MIN_CELL_WIDTH)
        fun line(label: String, cells: List<String>, widths: List<Int>, styled: Boolean): String {
            val body = cells.zip(widths).joinToString(" ".repeat(COLUMN_GAP)) { (cell, width) ->
                // Pad after shortening and before colouring, so escape codes do not count.
                val text = abbreviate(cell, width).padEnd(width)
                if (styled && cell == ViewBuilder.ERROR_CELL) style.error(text) else text
            }
            val owner = label.padEnd(labelWidth).let { if (styled) style.label(it) else it }
            return "  $owner" + " ".repeat(COLUMN_GAP) + body.trimEnd()
        }
        fun rows(widths: List<Int>) = table.rows.map { line(it.resource.label, it.cells, widths, styled = true) }
        val previewWidths = natural.map { minOf(it, share) }
        val summary = "${table.rows.size} ${table.type} row(s)" +
            if (table.errors > 0) style.error("; ${table.errors} error(s): ${table.firstError}") else ""
        fun header(widths: List<Int>) = summary + "\n" + style.hint(line("", titles, widths, styled = false))
        emit(
            out,
            header(previewWidths),
            preview = rows(previewWidths),
            full = rows(natural),
            fullHeader = header(natural),
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

    /** `:save` lists, `:save patients` saves the last `:view` under that name. */
    private fun saveView(argument: String, out: PrintWriter) {
        reportStorageProblems(out)
        if (argument.isBlank()) {
            val all = views.all()
            if (all.isEmpty()) {
                out.println("no saved views — :save <name> after a :view saves it")
                return
            }
            val width = all.keys.maxOf { it.length } + 1
            emit(
                out,
                header = null,
                preview = all.map { (name, columns) ->
                    "  ${style.label("@$name".padEnd(width))}  " +
                        abbreviate(columns.joinToString("  ") { it.ref }, valueWidth(width + 4))
                },
                full = all.flatMap { (name, columns) ->
                    listOf("  ${style.label("@$name")}") + columns.map { "      ${it.described}" }
                },
            )
            return
        }
        // `@people` is how a view is written everywhere else, so it is taken here too.
        val name = argument.removePrefix("@")
        if (!KeyedStore.valid(name)) {
            out.println("not a valid view name: $name — ${KeyedStore.RULE}")
            return
        }
        val columns = lastView ?: run {
            out.println("nothing to save — run a :view first")
            return
        }
        val before = views.set(name, columns)
        reportStorageProblems(out)
        val replaced = if (before != null && before != columns) "  (replaced ${before.size} column(s))" else ""
        out.println("saved @$name: ${columns.size} column(s) — :view @$name reruns it$replaced")
    }

    private fun unsaveView(argument: String, out: PrintWriter) {
        val name = argument.removePrefix("@")
        if (name.isBlank()) {
            out.println("usage: :unsave <view>")
            return
        }
        val before = views.remove(name)
        reportStorageProblems(out)
        out.println(if (before == null) "no saved view $name — :save lists them" else "forgot @$name")
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
    private fun restore(saved: SavedState, out: PrintWriter) {
        val leftOn = saved.fhir?.takeIf { it != session.version.id }
        val paths = saved.pools.sumOf { it.loads.size }
        val state = if (leftOn != null && saved.pools.any { it.loads.isNotEmpty() || it.focus.isNotEmpty() }) {
            out.println(
                style.error(
                    "! the workspace was left on ${leftOn.uppercase()}, so its $paths path(s) and focus " +
                        "are not restored in ${session.version.id.uppercase()} — saved until something changes",
                ),
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
                    out.println(style.error("  kept in the workspace, to open once it is back"))
                }
                continue
            }
            for (path in pool.loads) {
                if (path !in session.current.loadedPaths) session.current.loadedPaths += path
                if (Files.exists(Paths.get(path))) {
                    load(path, out)
                } else {
                    val file = workspace.dir?.resolve("state")?.let { " (or drop it from $it)" }.orEmpty()
                    out.println(style.error("! $path is missing${inPool()} — kept in the workspace, to load once it is back$file"))
                }
            }
            restoreFocus(pool.focus, out)
        }
        session.switchTo(state.current ?: session.pools.first().name)
        // Whatever could not be restored stays saved: nothing is written until something changes.
        lastSaved = stateNow()
    }

    private fun restoreFocus(steps: List<String>, out: PrintWriter) {
        if (steps.isEmpty()) return
        var current: Focus? = null
        for (step in steps) {
            when (val resolution = session.focusResolver.resolve(step, current)) {
                is Resolution.Focused -> current = resolution.focus
                else -> {
                    out.println(
                        style.error("! focus restored only as far as ${current?.label ?: "the pool"} — $step no longer reaches one value"),
                    )
                    break
                }
            }
        }
        session.focus = current
        current?.let { out.println("focus ${it.label}${inPool()}") }
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
    private fun saveState(out: PrintWriter) {
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
    private fun useLineHistory(reader: LineReader, out: PrintWriter) {
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
            out.println(style.error("! cannot use $file for line history: ${e.message}"))
        }
    }

    /**
     * Once the workspace's directory exists, moves the lines typed so far into its
     * history file: they were typed in this workspace, so they belong to it.
     */
    private fun attachPendingHistory(reader: LineReader, out: PrintWriter) {
        if (!historyPending || workspace.lineHistory?.parent?.let(Files::isDirectory) != true) return
        val typed = reader.history.map { it.line() }
        useLineHistory(reader, out)
        typed.forEach { reader.history.add(it) }
    }

    /** `:workspace` shows the current one and lists the rest; `:workspace biobank` switches. */
    private fun switchWorkspace(argument: String, out: PrintWriter) {
        if (argument.isBlank()) {
            val where = workspace.dir?.let { "  ($it)" }.orEmpty()
            out.println("workspace ${workspace.name}$where")
            workspaces.available().filter { it != workspace.name }.takeIf { it.isNotEmpty() }?.let {
                out.println("  also: ${it.joinToString(", ")}")
            }
            return
        }
        if (!Workspaces.valid(argument)) {
            out.println("not a valid workspace name: $argument — ${KeyedStore.RULE}")
            return
        }
        if (argument == workspace.name) {
            out.println("already in workspace $argument")
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
        out.println(
            "workspace $argument, FHIR ${session.version.id.uppercase()}" +
                if (isNew) " — new, created once something is saved" else "",
        )
        notes.forEach { out.println(style.error("! $it")) }
        reportStorageProblems(out)
        restore(saved, out)
    }

    // ---------------------------------------------------------------------- pool

    private fun load(path: String, out: PrintWriter) {
        session.current.git?.let {
            out.println("${poolName()} is ${it.spec} — :at <rev> moves it; :open another pool to load files")
            return
        }
        val report = session.importer.load(Paths.get(expandHome(path)))
        // Absolute, so the next session finds it from wherever it is started.
        val absolute = Paths.get(expandHome(path)).toAbsolutePath().normalize().toString()
        val loaded = session.current.loadedPaths
        if (report.files > 0 && absolute !in loaded) loaded += absolute
        val header = "loaded ${report.summary()}; ${poolName()} now ${session.pool.size}"
        if (report.problems.isEmpty()) {
            out.println(header)
            return
        }
        emit(out, header, report.problems.map { "  " + style.error("! $it") })
    }

    private fun poolSummary(out: PrintWriter) {
        if (session.pool.size == 0) {
            out.println("pool is empty")
            return
        }
        val census = session.pool.census
        val git = session.current.git
        val which = listOfNotNull(
            "pool ${session.current.name}".takeIf { session.pools.size > 1 },
            git?.let { "at ${it.commit.short} on ${it.line}" },
        ).joinToString(" ").let { if (it.isEmpty()) "" else "$it: " }
        val scoped = if (git?.deltaScope == true) "; scope: the delta's ${session.pool.inScope().size}" else ""
        emit(
            out,
            "$which${session.pool.size} resource(s), FHIR ${session.version.id.uppercase()}$scoped",
            session.pool.typeCounts().map { (type, count) ->
                "  ${style.type(type.padEnd(LABEL_WIDTH))} ${style.count(count.toString())}"
            } +
                listOf(
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

    private fun listPools(out: PrintWriter) {
        val width = session.pools.maxOf { it.name.length }
        emit(
            out,
            header = null,
            preview = session.pools.map { pool ->
                val marker = if (pool === session.current) "*" else " "
                val focus = pool.focus?.let { style.hint("  focus ${abbreviate(it.label, PROMPT_WIDTH)}") }.orEmpty()
                val paths = sourceOf(pool)
                "$marker ${style.label(pool.name.padEnd(width))}  ${style.count(pool.pool.size.toString().padStart(6))}  " +
                    abbreviate(paths, valueWidth(width + 12)) + focus
            },
            full = session.pools.flatMap { pool ->
                val marker = if (pool === session.current) "*" else " "
                listOf("$marker ${style.label(pool.name)}  ${style.count(pool.pool.size.toString())} resource(s)") +
                    (if (pool.git != null || pool.unavailable != null) listOf("      ${sourceOf(pool)}") else pool.loadedPaths.map { "      $it" }) +
                    listOfNotNull(pool.focus?.let { "      focus ${it.label}" })
            },
        )
    }

    /** Rows from another pool cannot be picked here, so a switch forgets them. */
    private fun switchPool(name: String, out: PrintWriter) {
        when {
            session.pool(name) == null ->
                out.println("no pool $name — :pools lists them, :open $name <path> adds one")
            name == session.current.name -> out.println("already in pool $name")
            else -> {
                session.switchTo(name)
                lastPicks = null
                val focus = session.focus?.let { ", focus ${it.label}" }.orEmpty()
                out.println("pool $name: ${session.pool.size} resource(s)$focus")
            }
        }
    }

    /** `:open specimen` adds an empty pool; `:open specimen <path>` loads into it too. */
    private fun openPool(argument: String, out: PrintWriter) {
        val name = argument.substringBefore(' ').trim()
        val path = argument.substringAfter(' ', "").trim()
        when {
            name.isEmpty() -> out.println("usage: :open <name> [path]")
            !KeyedStore.valid(name) -> out.println("not a valid pool name: $name — ${KeyedStore.RULE}")
            session.pool(name) != null -> out.println("pool $name is already open — :pool $name switches to it")
            else -> {
                val before = session.current.name
                session.open(name)
                session.switchTo(name)
                lastPicks = null
                when {
                    path.isEmpty() -> out.println("pool $name — empty, :load <path> to import")
                    // A path that exists as written is a path, even with an @ in it.
                    GitSource.split(path) != null && !Files.exists(Paths.get(expandHome(path))) ->
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

    private fun closePool(name: String, out: PrintWriter) {
        when {
            name.isBlank() -> out.println("usage: :close <name>")
            session.pool(name) == null -> out.println("no pool $name — :pools lists them")
            session.pools.size == 1 -> out.println("$name is the only pool — there is always one to evaluate against")
            else -> {
                val wasCurrent = name == session.current.name
                session.close(name)
                if (wasCurrent) lastPicks = null
                out.println("closed pool $name" + if (wasCurrent) " — now in pool ${session.current.name}" else "")
            }
        }
    }

    /**
     * A directory loads from disk as it is, uncommitted changes included, even inside a
     * repository: switching to git silently would change what the pool holds. So the
     * history is only pointed out.
     */
    private fun gitHint(path: String, out: PrintWriter) {
        val dir = Paths.get(expandHome(path))
        if (!Files.isDirectory(dir)) return
        val branch = GitSource.headLineAt(dir) ?: return
        out.println(style.hint("  in a git repository on $branch — :open <name> ${path.trimEnd('/')}@ opens its history; TAB after the @ lists revisions"))
    }

    private fun sourceOf(pool: Pool): String {
        pool.git?.let { return "${it.spec} at ${it.commit.short}" }
        pool.unavailable?.let { return "unavailable: ${it.source}" }
        return pool.loadedPaths.joinToString(", ").ifEmpty { "nothing loaded" }
    }

    /**
     * Makes the current pool [spec]'s directory at a revision: [at] when it still
     * exists, else the tip of the line [spec] names.
     *
     * @return false when it could not be opened; what went wrong has been said.
     */
    private fun openGit(spec: String, at: String?, deltaScope: Boolean, out: PrintWriter): Boolean {
        val (path, revision) = GitSource.split(spec) ?: run {
            out.println(style.error("! $spec names no revision — dir@rev, e.g. registry/in/import@branch/out"))
            return false
        }
        val source = try {
            GitSource.open(Paths.get(expandHome(path)).toAbsolutePath().normalize())
        } catch (e: GitSourceException) {
            out.println(style.error("! ${e.message}"))
            return false
        }
        // `dir@` follows whatever branch HEAD is on; the line saved is that branch, so
        // the pool keeps following it even after HEAD moves elsewhere.
        val line = revision.ifEmpty {
            source.headLine() ?: run {
                source.close()
                out.println(style.error("! ${source.path} is in a repository with no commits yet"))
                return false
            }
        }
        val left = at?.let { id ->
            source.resolve(id) ?: null.also {
                out.println(style.error("! ${id.take(7)} is gone from ${source.path} — opening the tip of $line instead"))
            }
        }
        val start = left ?: source.resolve(line) ?: run {
            source.close()
            out.println(style.error("! no commit $line in ${source.path}"))
            return false
        }
        val git = GitPool(source, line, session.importer, session.pool)
        // Attached only once loaded: a pool whose revision could not be read must not
        // look like a git pool to the prompt or to the saved state.
        val report = try {
            git.load(start)
        } catch (e: Exception) {
            source.close()
            out.println(style.error("! cannot read ${source.path} at ${start.short}: ${e.message ?: e.javaClass.simpleName}"))
            return false
        }
        session.current.git = git
        session.current.unavailable = null
        git.deltaScope = deltaScope
        val header = "${poolName()} at ${revision(git)}: ${report.summary()}"
        if (report.problems.isEmpty()) out.println(header) else emit(out, header, report.problems.map { "  " + style.error("! $it") })
        return true
    }

    /** `3f2a1c0 on branch/out, 2026-10-17 14:02 sync`. */
    private fun revision(git: GitPool): String =
        "${git.commit.short} on ${git.line}, ${COMMIT_TIME.format(git.commit.time)} ${git.commit.subject}"

    /** `Δ12: 2 added, 10 modified`. */
    private fun deltaSummary(git: GitPool): String {
        val delta = git.delta
        val parts = listOf(delta.added.size to "added", delta.modified.size to "modified", delta.deleted.size to "deleted")
            .filter { it.first > 0 }
            .joinToString(", ") { (count, what) -> "$count $what" }
        return "Δ${delta.size}" + if (parts.isEmpty()) ": nothing changed here" else ": $parts"
    }

    /** The current pool's git side, or null after saying why there is none. */
    private fun gitPool(out: PrintWriter): GitPool? = session.current.git ?: run {
        out.println("${poolName()} is not a git pool — :open <name> <dir>@<rev> opens one")
        null
    }

    private fun gitLog(argument: String, out: PrintWriter) {
        val git = gitPool(out) ?: return
        val limit = if (argument.isBlank()) DEFAULT_LOG else argument.toIntOrNull()?.takeIf { it > 0 } ?: run {
            out.println("usage: :log [n]")
            return
        }
        // A damaged repository throws from any read, as in a move: a history that
        // cannot be walked is an error, a commit whose changes cannot be read a Δ?.
        val commits = try {
            git.history(limit)
        } catch (e: Exception) {
            out.println(style.error("! cannot read ${git.source.path}: ${e.message ?: e.javaClass.simpleName}"))
            return
        }
        if (commits.isEmpty()) {
            out.println("${git.line} names no commit any more")
            return
        }
        var unreadable = 0
        emit(
            out,
            header = "${git.line} in ${git.source.path}, newest first",
            preview = commits.map { commit ->
                val marker = if (commit.id == git.commit.id) "*" else " "
                val count = runCatching { git.changedFiles(commit, session.importer::reads) }.getOrNull()
                if (count == null) unreadable++
                val changed = "Δ${count ?: "?"}"
                "$marker ${style.index(commit.short)}  ${COMMIT_TIME.format(commit.time)}  " +
                    "${style.count(changed.padStart(6))}  ${abbreviate(commit.subject, valueWidth(LOG_INDENT))}"
            },
        )
        if (unreadable > 0) {
            out.println(style.error("  Δ? — what $unreadable commit(s) changed cannot be read from ${git.source.path}"))
        }
        if (commits.none { it.id == git.commit.id }) {
            out.println(style.hint("  (at ${git.commit.short}, which is not among these)"))
        }
    }

    private fun at(revision: String, out: PrintWriter) {
        val git = gitPool(out) ?: return
        moved(git, git.at(revision), out)
    }

    private fun step(argument: String, out: PrintWriter) {
        val git = gitPool(out) ?: return
        val by = when {
            argument.isBlank() -> -1
            Regex("[+-]\\d{1,6}").matches(argument) -> argument.removePrefix("+").toInt()
            else -> {
                out.println("usage: :step [-n|+n]  —  -1 is one commit back, +1 one forward along ${git.line}")
                return
            }
        }
        if (by == 0) {
            out.println("at ${git.commit.short}")
            return
        }
        moved(git, git.step(by), out)
    }

    /**
     * Moving is how you ask what a run changed, so the scope goes to the delta. The
     * focus is found again in the new revision by the steps that reached it.
     */
    private fun moved(git: GitPool, move: GitPool.Move, out: PrintWriter) {
        when (move) {
            is GitPool.Move.Refused -> out.println(move.message)
            is GitPool.Move.Failed -> out.println(style.error("! ${move.message}"))
            is GitPool.Move.Moved -> {
                val steps = focusSteps(session.focus)
                session.focus = null
                lastPicks = null
                git.deltaScope = true
                val header = "${poolName()} at ${revision(git)} — ${deltaSummary(git)}"
                val problems = move.report.problems
                if (problems.isEmpty()) out.println(header) else emit(out, header, problems.map { "  " + style.error("! $it") })
                restoreFocus(steps, out)
            }
        }
    }

    private fun delta(out: PrintWriter) {
        val git = gitPool(out) ?: return
        val delta = git.delta
        val against = delta.parent?.let { "against ${it.short}" } ?: "against nothing: the first commit"
        val header = "${git.commit.short} $against — ${deltaSummary(git)}"
        if (delta.size == 0) {
            out.println(header)
            return
        }
        fun row(sign: String, label: String, origin: String?) =
            "  $sign ${style.label(label.padEnd(LABEL_WIDTH))}" + origin?.let { " " + style.hint("($it)") }.orEmpty()
        emit(
            out,
            header,
            delta.added.map { row("+", it.label, it.origin) } +
                delta.modified.map { row("~", it.label, it.origin) } +
                delta.deleted.map { row("-", it, null) },
        )
    }

    private fun scope(argument: String, out: PrintWriter) {
        val git = session.current.git
        when (argument.lowercase()) {
            "" -> out.println(
                if (git?.deltaScope == true) {
                    "scope: the delta, ${session.pool.inScope().size} of ${session.pool.size} resource(s) — :scope all for everything"
                } else {
                    "scope: all ${session.pool.size} resource(s)" + if (git != null) " — :scope delta for what ${git.commit.short} changed" else ""
                },
            )
            "delta" -> {
                val pool = gitPool(out) ?: return
                pool.deltaScope = true
                out.println("scope: the delta, ${session.pool.inScope().size} resource(s) — ${deltaSummary(pool)}")
            }
            "all" -> {
                git?.deltaScope = false
                out.println("scope: all ${session.pool.size} resource(s)")
            }
            else -> out.println("usage: :scope [delta|all]")
        }
    }

    /**
     * A label or `#index` picks a resource; anything else is an expression, run
     * against the current focus (or across the pool when there is none), whose one
     * result becomes the focus. Each new focus remembers the last, for `:focus ..`.
     * The rules live in [FocusResolver]; this only reports.
     */
    private fun focus(argument: String, out: PrintWriter) {
        val current = session.focus
        if (argument.isBlank()) {
            session.focus = null
            // Clears the lot: pool-wide rows would otherwise come back into play.
            lastPicks = null
            out.println("focus cleared — expressions now run across the pool")
            return
        }
        if (argument == "..") {
            session.focus = current?.parent
            when {
                current == null -> out.println("nothing in focus")
                current.parent == null -> out.println("focus cleared — expressions now run across the pool")
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
            is Resolution.NotInPool -> out.println("not in pool: ${resolution.argument}")
            is Resolution.CannotPick -> out.println(resolution.reason)
            is Resolution.Invalid ->
                out.println(style.error("! not in pool, and not a valid expression: ${resolution.message}"))
            is Resolution.Failed -> out.println(style.error("! ${resolution.message}"))
            is Resolution.Nothing -> {
                // The hint points at :why, which should not need the expression retyped.
                lastExpression = argument
                val label = if (resolution.couldBeLabel) "not a pool label, and " else ""
                out.println("$label$argument yields nothing — :why")
            }
            is Resolution.Several -> {
                val results = resolution.picks.results
                val byResource = resolution.owners > 1
                val counted = if (byResource) "from ${resolution.owners} resources" else ""
                emit(
                    out,
                    header = listOf("${results.size} values", counted).filter { it.isNotEmpty() }.joinToString(" ") +
                        "; focus needs one — pick it with  :focus [n]",
                    preview = results.mapIndexed { idx, found ->
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
    private fun announceFocus(focus: Focus, out: PrintWriter) {
        val what = if (focus.isResource) focus.resource.origin else "${focus.typeName} in ${focus.resource.origin}"
        emit(
            out,
            header = "focus ${focus.label}  ($what)",
            preview = listOf(valueRow(focus.node)),
            full = expanded("  ", focus.node),
        )
    }

    private fun extensions(argument: String, out: PrintWriter) {
        val census = session.pool.census
        if (census.extensions.isEmpty()) {
            out.println("no extensions observed in the pool")
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
            out.println("no extensions observed at $argument")
            return
        }
        emit(
            out,
            header = null,
            preview = rows.map { (path, url, stat) ->
                "  ${path.padEnd(34)} ${style.count(stat.count.toString().padStart(5))} ×  " +
                    "${style.url(url)}  ${style.type(stat.valueTypes.joinToString("|"))}"
            },
        )
    }

    private fun switchVersion(argument: String, out: PrintWriter) {
        if (argument.isBlank()) {
            out.println("FHIR ${session.version.id} — available: ${FhirVersion.ids()}")
            return
        }
        val version = FhirVersion.parse(argument) ?: run {
            out.println("unknown version $argument — available: ${FhirVersion.ids()}")
            return
        }
        if (version == session.version) {
            out.println("already on ${version.id}")
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
            out.println("switched to FHIR ${version.id.uppercase()} — $reset")
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
            out.println(style.error("! ${e.message}"))
        }
    }

    private fun help(out: PrintWriter) {
        out.println("Type a FHIRPath expression, or:")
        val usages = commands.map { "  ${it.name} ${it.args}" }
        val width = usages.maxOf { it.length } + 2
        commands.zip(usages).forEach { (command, usage) -> out.println(usage.padEnd(width) + command.doc) }
        out.println()
        out.println("With a focus set, an expression runs against that resource;")
        out.println("with no focus it runs across the whole pool.")
        out.println()
        out.println(":focus also takes an expression: it runs against the current focus")
        out.println("(or the pool) and its one result becomes the focus, which may be an")
        out.println("element. Expressions then start from that element — coding.code,")
        out.println("not Observation.code.coding — and :focus .. steps back up.")
        out.println(":more right after :focus prints the focused value in full.")
        out.println(":focus [n] picks row n of the last numbered listing, e.g. when")
        out.println("an expression matched several values.")
        out.println()
        out.println(":why with no expression re-diagnoses the last one you ran, so the")
        out.println("hint printed after an empty result can be followed by just :why.")
        out.println()
        out.println("A pool-wide expression that matches something is recorded, once, in")
        out.println("the workspace; :history lists them by number. :view #3 #5")
        out.println("tabulates them: one row per resource of the type they start from,")
        out.println("one column each. Columns must yield primitives, e.g. name.family")
        out.println("rather than name; several values in one cell are joined with \", \".")
        out.println(":name family right after a pool-wide expression names it; :name family #1")
        out.println("names a recorded one. Then :view family birth #3 works and the column is")
        out.println("titled family. A name holds the expression itself, not its number.")
        out.println(":save people saves the last :view; :view @people reruns it.")
        out.println()
        out.println("Several pools can be open at once, each with its own census and focus;")
        out.println("resolve() stays inside the pool. :open specimen <path> adds one and")
        out.println("switches to it, :pool <name> switches, :pools lists them. Recorded")
        out.println("expressions, names and views are shared, so :view @people runs in any.")
        out.println()
        out.println("A pool can be a directory in a git repository at a revision, read")
        out.println("without checking anything out: :open in registry/in/import@branch/out.")
        out.println("A bare @ opens the branch HEAD is on; TAB after the @ lists revisions.")
        out.println(":log lists its line, :step -1 and :step +1 move along it, :at <rev>")
        out.println("jumps. Moving sets the scope to the delta, so pool-wide expressions and")
        out.println(":view run over what that commit changed, while resolve() still sees")
        out.println("everything; :delta lists it, :scope all goes back to the whole pool.")
        out.println()
        out.println("A workspace keeps all of that, the up-arrow history, and where the")
        out.println("session was left: FHIR version, pools, :limit and focus, all")
        out.println("restored at the next start. The default is ~/.fhir-walk; start with")
        out.println("--workspace <name>, or :workspace <name> to switch. --fresh starts")
        out.println("empty and keeps the saved state until something changes.")
        out.println()
        out.println("A listing fills the window and keeps the rest for :more, which")
        out.println("reprints it whole with no value shortened. :limit off prints")
        out.println("everything up front and lets the terminal scroll.")
        out.println()
        out.println("TAB completes, including after :all and :why. On an empty line it")
        out.println("lists the pool's resource types; after a '.' the elements and")
        out.println("functions available there, with the ones present in the pool first;")
        out.println("inside extension('…') the URLs observed on that element.")
        out.println("Up/Down or further TABs walk the candidates; Enter takes the one shown.")
    }

    private fun expandHome(path: String): String =
        if (path.startsWith("~")) System.getProperty("user.home") + path.drop(1) else path

    /** Elides the middle, since the informative parts of a URL are both ends. */
    private fun abbreviate(text: String, width: Int): String {
        if (text.length <= width) return text
        val keepTail = (width - 1) / 2
        val keepHead = width - 1 - keepTail
        return text.take(keepHead) + "…" + text.takeLast(keepTail)
    }

    private companion object {
        const val UNLIMITED = 0
        const val STEP_WIDTH = 48
        const val LABEL_WIDTH = 28
        const val PROMPT_WIDTH = 48
        const val VALUE_INDENT = 14
        const val MIN_VALUE_WIDTH = 40
        const val MIN_ROW_LIMIT = 8
        const val COLUMN_GAP = 2
        const val MIN_CELL_WIDTH = 12
        /** Used until the terminal reports its size; a dumb terminal never does. */
        const val DEFAULT_ROW_LIMIT = 20
        const val DEFAULT_COLUMNS = 100
        const val DEFAULT_LOG = 20
        const val LOG_INDENT = 38

        val COMMIT_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

        /**
         * The two encodings an arrow key arrives in, depending on whether
         * application-cursor-keys mode took effect: CSI (ESC [ A) or SS3 (ESC O A).
         */
        const val ESC_CSI = "\u001b["
        const val ESC_SS3 = "\u001bO"
    }
}
