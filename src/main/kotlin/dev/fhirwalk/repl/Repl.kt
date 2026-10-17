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
import org.jline.reader.EndOfFileException
import org.jline.reader.LineReader
import org.jline.reader.LineReaderBuilder
import org.jline.reader.Reference
import org.jline.reader.UserInterruptException
import org.hl7.fhir.instance.model.api.IBase
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import java.io.PrintWriter
import java.nio.file.Paths

/**
 * @param log where pool-wide expressions are recorded for `:history` and `:view`.
 */
class Repl(
    startVersion: FhirVersion,
    private val initialPaths: List<String>,
    private val log: ExpressionLog = ExpressionLog.default(),
) {

    private var session = Session(startVersion)

    private val commands = listOf(
        FhirPathCompleter.CommandInfo(":load", "<path>", "import a file, bundle, NDJSON or directory tree"),
        FhirPathCompleter.CommandInfo(":pool", "", "what is in the pool"),
        FhirPathCompleter.CommandInfo(":focus", "[label|#index|expr|[n]|..]", "evaluate against one resource or element"),
        FhirPathCompleter.CommandInfo(":all", "<expr>", "evaluate across the whole pool"),
        FhirPathCompleter.CommandInfo(":why", "[expr]", "find the step where the expression goes empty"),
        FhirPathCompleter.CommandInfo(":ext", "[path]", "extension URLs observed in the pool"),
        FhirPathCompleter.CommandInfo(":history", "[filter]", "recorded pool-wide expressions, newest first"),
        FhirPathCompleter.CommandInfo(":view", "#n [#n …]", "a table: one row per resource, a column per recorded expression"),
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

    private var columns = DEFAULT_COLUMNS

    /** Plain until [run] has a terminal to ask; see [Style.detect]. */
    private var style = Style.PLAIN

    fun run() {
        val terminal: Terminal = TerminalBuilder.builder().dumb(true).build()
        val out = terminal.writer()
        val reader = LineReaderBuilder.builder()
            .terminal(terminal)
            .parser(FhirPathParser())
            .completer(
                FhirPathCompleter({ session.navigator }, { session.pool }, { commands }, { session.focus }, log::entries),
            )
            // AUTO_LIST prints a passive list that arrow keys cannot touch, so it
            // costs an extra TAB before anything is navigable -- press an arrow at a
            // passive list and you get command history instead of the next candidate.
            // The menu shows the same grouped list and does accept arrows, so skip
            // the passive stage and let the first TAB open the menu.
            .option(LineReader.Option.AUTO_LIST, false)
            .option(LineReader.Option.AUTO_MENU, true)
            .option(LineReader.Option.AUTO_GROUP, true)
            .option(LineReader.Option.GROUP_PERSIST, true)
            .option(LineReader.Option.CASE_INSENSITIVE, true)
            .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true)
            .build()
        bindArrowKeys(reader)
        style = Style.detect(terminal)

        // Fill the window and keep the rest for :more, rather than a fixed guess.
        if (terminal.height > MIN_ROW_LIMIT + 8) rowLimit = terminal.height - 8
        if (terminal.width > MIN_VALUE_WIDTH) columns = terminal.width

        out.println("fhir-walk — FHIRPath over a local resource pool.  :help for commands")
        out.println("FHIR ${session.version.id.uppercase()}")
        initialPaths.forEach { load(it, out) }
        if (initialPaths.isEmpty()) {
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
            out.flush()
        }
        out.println("bye")
        out.flush()
        terminal.close()
    }

    private fun prompt(): String {
        val scope = session.focus?.label?.let { abbreviate(it, PROMPT_WIDTH) } ?: "pool(${session.pool.size})"
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
            // beats handing the whole line to the FHIRPath parser.
            val firstWord = input.substringBefore(' ')
            if (":$firstWord" in commandNames) {
                out.println(style.error("! not a FHIRPath expression — did you mean  :$input"))
                return true
            }
            lastExpression = input
            evaluate(input, out)
            return true
        }
        val command = input.substringBefore(' ')
        val argument = input.substringAfter(' ', "").trim()
        when (command) {
            ":quit", ":exit", ":q" -> return false
            ":help", ":?" -> help(out)
            ":load" -> if (argument.isBlank()) out.println("usage: :load <path>") else load(argument, out)
            ":pool" -> poolSummary(out)
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
            ":more" -> more(out)
            ":limit" -> limit(argument, out)
            ":fhir" -> switchVersion(argument, out)
            else -> out.println("unknown command $command — :help for the list")
        }
        return true
    }

    // ------------------------------------------------------------------ evaluate

    private fun evaluate(expression: String, out: PrintWriter) {
        lastPicks = null
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
                out.println("row limit off — listings print in full and the terminal scrolls")
            }
            else -> {
                val rows = argument.toIntOrNull()
                if (rows == null || rows < 1) {
                    out.println("usage: :limit <rows|off>")
                } else {
                    rowLimit = rows
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
        if (reportSyntaxError(expression, out)) return
        val result = session.evaluator.evaluateAll(expression)
        val header = "${result.hits.size} of ${result.considered} resource(s) matched" +
            if (result.errors > 0) style.error("; ${result.errors} error(s): ${result.firstError}") else ""
        // Matching something is the bar: a misspelt element parses fine and only
        // ever comes back empty, and would otherwise clutter the log.
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

    private var logErrorReported = false

    private fun record(expression: String, out: PrintWriter) {
        log.record(expression)
        val error = log.writeError
        if (error != null && !logErrorReported) {
            logErrorReported = true
            out.println(style.error("! $error — expressions are kept for this session only"))
        }
    }

    private fun history(argument: String, out: PrintWriter) {
        val numbered = log.entries().mapIndexed { idx, expression -> idx + 1 to expression }
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
        emit(
            out,
            header = null,
            preview = numbered.map { (number, expression) ->
                "  ${style.index("#$number".padEnd(width))}  " + abbreviate(expression, valueWidth(width + 4))
            },
            full = numbered.map { (number, expression) -> "  ${style.index("#$number".padEnd(width))}  $expression" },
        )
    }

    /** @param argument recorded expressions by number, `#3 #5` or `3, 5`. */
    private fun view(argument: String, out: PrintWriter) {
        val refs = argument.split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
        if (refs.isEmpty()) {
            out.println("usage: :view #n [#n …]  —  numbers from :history, one column each")
            return
        }
        val picked = refs.map { ref ->
            val number = ref.removePrefix("#").toIntOrNull()
            val expression = number?.let { log.get(it) } ?: run {
                out.println(
                    if (number == null) "not a :history number: $ref" else "nothing recorded as #$number — see :history",
                )
                return
            }
            ViewBuilder.Column(number, expression)
        }
        val table = when (val result = session.viewBuilder.build(picked)) {
            is ViewBuilder.Result.Refused -> {
                out.println(style.error("! ${result.message}"))
                return
            }
            is ViewBuilder.Result.Table -> result
        }
        val titles = table.columns.map { "#${it.number} ${it.title(table.type)}" }
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

    // ---------------------------------------------------------------------- pool

    private fun load(path: String, out: PrintWriter) {
        val report = session.importer.load(Paths.get(expandHome(path)))
        val header = "loaded ${report.summary()}; pool now ${session.pool.size}"
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
        emit(
            out,
            "${session.pool.size} resource(s), FHIR ${session.version.id.uppercase()}",
            session.pool.typeCounts().map { (type, count) ->
                "  ${style.type(type.padEnd(LABEL_WIDTH))} ${style.count(count.toString())}"
            } +
                listOf(
                    "census: ${census.extensions.size} extension url/path pair(s), " +
                        "${census.pathCounts.size} populated path(s)",
                ),
        )
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
            session = Session(version)
            // Rows from the old pool; a pool-wide pick would otherwise still look valid.
            lastPicks = null
            out.println("switched to FHIR ${version.id.uppercase()} — pool reset")
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
        out.println("~/.fhir-walk/expressions; :history lists them by number. :view #3 #5")
        out.println("tabulates them: one row per resource of the type they start from,")
        out.println("one column each. Columns must yield primitives, e.g. name.family")
        out.println("rather than name; several values in one cell are joined with \", \".")
        out.println()
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

        /**
         * The two encodings an arrow key arrives in, depending on whether
         * application-cursor-keys mode took effect: CSI (ESC [ A) or SS3 (ESC O A).
         */
        const val ESC_CSI = "\u001b["
        const val ESC_SS3 = "\u001bO"
    }
}
