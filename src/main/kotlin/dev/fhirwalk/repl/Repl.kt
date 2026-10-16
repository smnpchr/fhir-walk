package dev.fhirwalk.repl

import dev.fhirwalk.complete.FhirPathCompleter
import dev.fhirwalk.eval.Outcome
import dev.fhirwalk.model.FhirVersion
import dev.fhirwalk.model.FhirVersionUnavailable
import org.jline.reader.EndOfFileException
import org.jline.reader.LineReader
import org.jline.reader.LineReaderBuilder
import org.jline.reader.UserInterruptException
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
        FhirPathCompleter.CommandInfo(":hits", "", "expand the last pool-wide result"),
        FhirPathCompleter.CommandInfo(":fhir", "[version]", "switch FHIR version (resets the pool)"),
        FhirPathCompleter.CommandInfo(":help", "", "this list"),
        FhirPathCompleter.CommandInfo(":quit", "", "exit"),
    )

    /** Command names including the undocumented aliases, for the dropped-colon hint. */
    private val commandNames: Set<String> =
        commands.map { it.name }.toSet() + setOf(":exit", ":q", ":?")

    private var lastResult: dev.fhirwalk.eval.Evaluator.PoolResult? = null
    private var lastExpression: String? = null

    fun run() {
        val terminal: Terminal = TerminalBuilder.builder().dumb(true).build()
        val out = terminal.writer()
        val reader = LineReaderBuilder.builder()
            .terminal(terminal)
            .parser(FhirPathParser())
            .completer(FhirPathCompleter({ session.navigator }, { session.pool }, { commands }))
            // AUTO_LIST is what makes a single TAB show the grouped candidate list;
            // without it an ambiguous TAB only inserts the common prefix and beeps.
            .option(LineReader.Option.AUTO_LIST, true)
            .option(LineReader.Option.LIST_AMBIGUOUS, true)
            // AUTO_MENU turns that list into something you can walk through: further
            // TABs cycle the candidates into the line. Without it the list is purely
            // informational, arrow keys do history, and there is no way to pick a
            // long extension URL other than typing it.
            .option(LineReader.Option.AUTO_MENU, true)
            .option(LineReader.Option.AUTO_GROUP, true)
            .option(LineReader.Option.GROUP_PERSIST, true)
            .option(LineReader.Option.CASE_INSENSITIVE, true)
            .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true)
            .build()

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
            ":hits" -> hits(out)
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
                outcome.values.take(MAX_ROWS).forEachIndexed { idx, value ->
                    out.println("  [$idx] ${session.render.typeOf(value)}  ${session.render.value(value)}")
                }
                if (outcome.values.size > MAX_ROWS) {
                    out.println("  … ${outcome.values.size - MAX_ROWS} more of ${outcome.values.size}")
                }
            }
        }
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
        lastResult = result
        lastExpression = expression
        out.println(
            "${result.hits.size} of ${result.considered} resource(s) matched" +
                if (result.errors > 0) "; ${result.errors} error(s): ${result.firstError}" else "",
        )
        if (result.hits.isEmpty() && result.errors == 0) {
            // Nothing matched anywhere, and it parsed: the question is which step
            // goes empty, which is exactly what :why answers.
            out.println("  nothing matched — :why $expression")
        }
        result.hits.take(MAX_POOL_ROWS).forEach { hit ->
            val first = hit.values.firstOrNull()?.let { session.render.value(it, 60) } ?: ""
            val extra = if (hit.values.size > 1) " (+${hit.values.size - 1})" else ""
            out.println("  ${hit.resource.label.padEnd(28)} $first$extra")
        }
        if (result.hits.size > MAX_POOL_ROWS) {
            out.println("  … ${result.hits.size - MAX_POOL_ROWS} more — :hits to expand")
        }
    }

    private fun hits(out: PrintWriter) {
        val result = lastResult ?: run { out.println("no pool-wide result yet"); return }
        out.println("${lastExpression} — ${result.hits.size} hit(s)")
        result.hits.forEach { hit ->
            out.println("  ${hit.resource.label}  (${hit.resource.origin})")
            hit.values.take(MAX_ROWS).forEach { value ->
                out.println("      ${session.render.typeOf(value)}  ${session.render.value(value)}")
            }
        }
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
        out.println("loaded ${report.summary()}; pool now ${session.pool.size}")
        report.problems.take(MAX_PROBLEMS).forEach { out.println("  ! $it") }
        if (report.problems.size > MAX_PROBLEMS) {
            out.println("  … ${report.problems.size - MAX_PROBLEMS} more problem(s)")
        }
    }

    private fun poolSummary(out: PrintWriter) {
        if (session.pool.size == 0) {
            out.println("pool is empty")
            return
        }
        out.println("${session.pool.size} resource(s), FHIR ${session.version.id.uppercase()}")
        session.pool.typeCounts().forEach { (type, count) -> out.println("  ${type.padEnd(28)} $count") }
        val census = session.pool.census
        out.println(
            "census: ${census.extensions.size} extension url/path pair(s), " +
                "${census.pathCounts.size} populated path(s)",
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
        rows.take(MAX_ROWS).forEach { (path, url, stat) ->
            out.println("  ${path.padEnd(34)} ${stat.count.toString().padStart(5)} ×  $url  ${stat.valueTypes.joinToString("|")}")
        }
        if (rows.size > MAX_ROWS) out.println("  … ${rows.size - MAX_ROWS} more")
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
        out.println("TAB completes, including after :all and :why. On an empty line it")
        out.println("lists the pool's resource types; after a '.' the elements and")
        out.println("functions available there, with the ones present in the pool first;")
        out.println("inside extension('…') the URLs observed on that element.")
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
        const val MAX_ROWS = 40
        const val MAX_POOL_ROWS = 12
        const val MAX_PROBLEMS = 8
        const val STEP_WIDTH = 48
    }
}
