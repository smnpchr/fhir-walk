package dev.fhirwalk.repl

import dev.fhirwalk.complete.FhirPathCompleter
import dev.fhirwalk.eval.Outcome
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

class Repl(startVersion: FhirVersion, private val initialPaths: List<String>) {

    private var session = Session(startVersion)

    private val commands = listOf(
        FhirPathCompleter.CommandInfo(":load", "<path>", "import a file, bundle, NDJSON or directory tree"),
        FhirPathCompleter.CommandInfo(":pool", "", "what is in the pool"),
        FhirPathCompleter.CommandInfo(":focus", "[label|#index]", "evaluate against one resource (blank to clear)"),
        FhirPathCompleter.CommandInfo(":all", "<expr>", "evaluate across the whole pool"),
        FhirPathCompleter.CommandInfo(":why", "<expr>", "find the step where the expression goes empty"),
        FhirPathCompleter.CommandInfo(":ext", "[path]", "extension URLs observed in the pool"),
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
     */
    private class Listing(val header: String?, val preview: List<String>, val full: List<String>)

    private var lastListing: Listing? = null

    /** Rows printed per listing before the rest is held for `:more`; 0 = unlimited. */
    private var rowLimit = DEFAULT_ROW_LIMIT

    private var columns = DEFAULT_COLUMNS

    fun run() {
        val terminal: Terminal = TerminalBuilder.builder().dumb(true).build()
        val out = terminal.writer()
        val reader = LineReaderBuilder.builder()
            .terminal(terminal)
            .parser(FhirPathParser())
            .completer(FhirPathCompleter({ session.navigator }, { session.pool }, { commands }))
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
                out.println("! ${e.message ?: e.javaClass.simpleName}")
            }
            out.flush()
        }
        out.println("bye")
        out.flush()
        terminal.close()
    }

    private fun prompt(): String {
        val focus = session.focus
        val scope = focus?.label ?: "pool(${session.pool.size})"
        return "${session.version.id}:$scope> "
    }

    /** @return false to exit. */
    private fun dispatch(input: String, out: PrintWriter): Boolean {
        if (!input.startsWith(":")) {
            // `all Specimen.subject` is a dropped colon, not an expression. Saying so
            // beats handing the whole line to the FHIRPath parser.
            val firstWord = input.substringBefore(' ')
            if (":$firstWord" in commandNames) {
                out.println("! not a FHIRPath expression — did you mean  :$input")
                return true
            }
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
            ":all" -> if (argument.isBlank()) out.println("usage: :all <expr>") else evaluateAll(argument, out)
            ":why" -> if (argument.isBlank()) out.println("usage: :why <expr>") else why(argument, out)
            ":ext" -> extensions(argument, out)
            ":more" -> more(out)
            ":limit" -> limit(argument, out)
            ":fhir" -> switchVersion(argument, out)
            else -> out.println("unknown command $command — :help for the list")
        }
        return true
    }

    // ------------------------------------------------------------------ evaluate

    private fun evaluate(expression: String, out: PrintWriter) {
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
        when (val outcome = session.evaluator.evaluate(focus.resource, expression)) {
            is Outcome.Failure -> out.println("! ${outcome.message}")
            is Outcome.Values -> {
                if (outcome.values.isEmpty()) {
                    out.println("{} empty — :why $expression")
                    return
                }
                emit(
                    out,
                    header = null,
                    preview = outcome.values.mapIndexed { idx, value ->
                        "  [$idx] ${session.render.typeOf(value)}  " +
                            session.render.value(value, valueWidth(VALUE_INDENT))
                    },
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
     */
    private fun emit(
        out: PrintWriter,
        header: String?,
        preview: List<String>,
        full: List<String> = preview,
    ) {
        lastListing = Listing(header, preview, full)
        header?.let { out.println(it) }
        val shown = if (rowLimit <= 0) preview.size else minOf(rowLimit, preview.size)
        for (index in 0 until shown) out.println(preview[index])
        val hidden = preview.size - shown
        when {
            hidden > 0 -> out.println("  … $hidden more of ${preview.size} — :more")
            // Nothing was held back by row count, but a value may still have been
            // shortened to fit the window; say so rather than leaving a dangling "…".
            preview != full && preview.any { it.contains('…') } ->
                out.println("  … shortened to fit — :more")
        }
    }

    private fun more(out: PrintWriter) {
        val listing = lastListing ?: run {
            out.println("nothing listed yet")
            return
        }
        listing.header?.let { out.println(it) }
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
     * One value in full: a primitive stays on the label's line, while anything
     * structured gets pretty-printed JSON underneath it.
     */
    private fun expanded(label: String, value: IBase): List<String> {
        val type = session.render.typeOf(value)
        val lines = session.render.pretty(value)
        if (lines.size == 1) return listOf("$label$type  ${lines.first()}")
        val indent = " ".repeat(label.length + 2)
        return listOf("$label$type") + lines.map { indent + it }
    }

    /**
     * A syntax error belongs to the expression, not to the data: report it once,
     * rather than once per resource in the pool.
     *
     * @return true when the expression could not be parsed and nothing should run.
     */
    private fun reportSyntaxError(expression: String, out: PrintWriter): Boolean {
        val error = session.evaluator.validate(expression) ?: return false
        out.println("! $error")
        // FHIRPath strings are single-quoted; " delimits an identifier, so a
        // double-quoted URL fails with a message that never mentions quoting.
        if (expression.contains('"')) {
            out.println("  FHIRPath string literals use single quotes — try '…' rather than \"…\"")
        }
        return true
    }

    private fun evaluateAll(expression: String, out: PrintWriter) {
        if (reportSyntaxError(expression, out)) return
        val result = session.evaluator.evaluateAll(expression)
        val header = "${result.hits.size} of ${result.considered} resource(s) matched" +
            if (result.errors > 0) "; ${result.errors} error(s): ${result.firstError}" else ""
        if (result.hits.isEmpty()) {
            out.println(header)
            if (result.errors == 0) {
                // Nothing matched anywhere, and it parsed: the question is which step
                // goes empty, which is exactly what :why answers.
                out.println("  nothing matched — :why $expression")
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
                    ?: ""
                val extra = if (hit.values.size > 1) " (+${hit.values.size - 1})" else ""
                "  ${hit.resource.label.padEnd(LABEL_WIDTH)} $first$extra"
            },
            // The full form keeps every value whole and adds the origin, so one
            // listing answers both "what matched" and "where did it come from".
            full = result.hits.flatMap { hit ->
                listOf("  ${hit.resource.label}  (${hit.resource.origin})") +
                    hit.values.flatMap { expanded("      ", it) }
            },
        )
    }

    private fun why(expression: String, out: PrintWriter) {
        if (reportSyntaxError(expression, out)) return
        if (session.pool.size == 0) {
            out.println("pool is empty")
            return
        }
        val root = dev.fhirwalk.eval.FhirPathTokenizer.segments(expression)
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
            ?: session.prefixWalker.diagnosisTarget(expression)
            ?: session.pool.all().first()
        if (session.focus == null) out.println("(no focus; diagnosing against ${focus.label})")
        val diagnosis = session.prefixWalker.diagnose(focus.resource, expression)
        // Each row shows only the step it adds, not the whole prefix: with long
        // extension URLs in the path, repeating the prefix makes the table unreadable.
        val segments = dev.fhirwalk.eval.FhirPathTokenizer.segments(expression)
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
            out.println("  ${abbreviate(label, STEP_WIDTH).padEnd(STEP_WIDTH)} ${count.padEnd(5)}$marker")
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

    // ---------------------------------------------------------------------- pool

    private fun load(path: String, out: PrintWriter) {
        val report = session.importer.load(Paths.get(expandHome(path)))
        val header = "loaded ${report.summary()}; pool now ${session.pool.size}"
        if (report.problems.isEmpty()) {
            out.println(header)
            return
        }
        emit(out, header, report.problems.map { "  ! $it" })
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
            session.pool.typeCounts().map { (type, count) -> "  ${type.padEnd(LABEL_WIDTH)} $count" } +
                listOf(
                    "census: ${census.extensions.size} extension url/path pair(s), " +
                        "${census.pathCounts.size} populated path(s)",
                ),
        )
    }

    private fun focus(argument: String, out: PrintWriter) {
        if (argument.isBlank()) {
            session.focus = null
            out.println("focus cleared — expressions now run across the pool")
            return
        }
        val target = if (argument.startsWith("#")) {
            session.pool.byIndex(argument.drop(1).toIntOrNull() ?: -1)
        } else {
            session.pool.find(argument) ?: session.pool.all().firstOrNull { it.label == argument }
        }
        if (target == null) {
            out.println("not in pool: $argument")
            return
        }
        session.focus = target
        out.println("focus ${target.label}  (${target.origin})")
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
                "  ${path.padEnd(34)} ${stat.count.toString().padStart(5)} ×  " +
                    "$url  ${stat.valueTypes.joinToString("|")}"
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
            out.println("switched to FHIR ${version.id.uppercase()} — pool reset")
        } catch (e: FhirVersionUnavailable) {
            out.println("! ${e.message}")
        }
    }

    private fun help(out: PrintWriter) {
        out.println("Type a FHIRPath expression, or:")
        commands.forEach { out.println("  ${it.name} ${it.args}".padEnd(26) + it.doc) }
        out.println()
        out.println("With a focus set, an expression runs against that resource;")
        out.println("with no focus it runs across the whole pool.")
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
        const val VALUE_INDENT = 14
        const val MIN_VALUE_WIDTH = 40
        const val MIN_ROW_LIMIT = 8
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
