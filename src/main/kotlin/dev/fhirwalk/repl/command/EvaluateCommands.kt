package dev.fhirwalk.repl.command

import dev.fhirwalk.eval.FhirPathTokenizer
import dev.fhirwalk.eval.Focus
import dev.fhirwalk.eval.Outcome
import dev.fhirwalk.repl.Output
import dev.fhirwalk.repl.Session
import dev.fhirwalk.repl.abbreviate

/** Running an expression, against the focus or across the pool, and finding out why it came back empty. */
internal class EvaluateCommands(private val state: ReplState) : CommandGroup {

    private val session: Session get() = state.session

    override val commands = listOf(
        command(":all") { argument, out ->
            if (argument.isBlank()) {
                out.text("usage: :all <expr>")
            } else {
                state.lastExpression = argument
                evaluateAll(argument, out)
            }
        },
        command(":why", run = ::why),
        command(":ext", run = ::extensions),
    )

    /** A line that is not a command: an expression, run against the focus, or across the pool without one. */
    fun evaluate(expression: String, out: Output) {
        state.lastExpression = expression
        state.lastPicks = null
        state.nameable = null
        if (state.reportSyntaxError(expression, out)) return
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
        when (val outcome = session.evaluator.evaluate(focus.node, expression, focus.resource.resource)) {
            is Outcome.Failure -> out.error("${outcome.message}")
            is Outcome.Values -> {
                if (outcome.values.isEmpty()) {
                    out.text("{} empty — :why")
                    return
                }
                state.lastPicks = session.focusResolver.picksOf(expression, focus, outcome.values)
                out.listing(
                    header = emptyList(),
                    rows = outcome.values.mapIndexed { idx, value -> state.valueRow(value, idx) },
                    full = outcome.values.flatMapIndexed { idx, value -> state.expanded("  [$idx] ", value) },
                )
            }
        }
    }

    /** Not numbered: rows are resources, and an indexer would not pick between them. */
    private fun evaluateAll(expression: String, out: Output) {
        state.lastPicks = null
        // Before the syntax check, so a failed :all leaves nothing behind to name.
        state.nameable = null
        if (state.reportSyntaxError(expression, out)) return
        val result = session.evaluator.evaluateAll(expression)
        val meta = session.current.meta
        val header = Output.line {
            +"${result.hits.size} of ${result.considered} resource(s) matched"
            // A meta pool's rows are labelled by bundle; how many bundles is the answer as often.
            if (meta != null && meta.scoped == null) {
                +", in ${result.hits.mapTo(HashSet()) { it.resource.part }.size} of ${meta.members.size} bundle(s)"
            }
            if (result.errors > 0) error("; ${result.errors} error(s): ${result.firstError}")
        }
        // Matching something is the bar: a misspelt element parses fine and only
        // ever comes back empty, and would otherwise clutter the log.
        state.nameable = expression.takeIf { result.hits.isNotEmpty() }
        if (result.hits.isNotEmpty()) state.record(expression, out)
        if (result.hits.isEmpty()) {
            out.add(header)
            if (result.errors == 0) {
                // Nothing matched anywhere, and it parsed: the question is which step
                // goes empty, which is exactly what :why answers.
                out.text("  nothing matched — :why")
            }
            state.lastListing = null
            return
        }
        out.listing(
            listOf(header),
            rows = result.hits.map { hit ->
                Output.line {
                    +"  "
                    label(hit.resource.label.padEnd(ReplState.LABEL_WIDTH))
                    +" "
                    shrink(hit.values.firstOrNull()?.let { session.render.value(it) }.orEmpty(), Output.Kind.PREVIEW, cutsEnd = true)
                    if (hit.values.size > 1) hint(" (+${hit.values.size - 1})")
                }
            },
            // The full form keeps every value whole and adds the origin, so one
            // listing answers both "what matched" and "where did it come from".
            full = result.hits.flatMap { hit ->
                listOf(Output.line { +"  "; label(hit.resource.label); +"  "; hint("(${hit.resource.origin})") }) +
                    hit.values.flatMap { state.expanded("      ", it) }
            },
        )
    }

    /** @param argument the expression, or blank to re-diagnose the last one. */
    private fun why(argument: String, out: Output) {
        val expression = argument.ifBlank { state.lastExpression ?: "" }
        if (expression.isBlank()) {
            out.text("usage: :why <expr>  —  or :why alone, to re-run the last expression")
            return
        }
        state.lastExpression = expression
        if (state.reportSyntaxError(expression, out)) return
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
        val diagnosis = session.prefixWalker.diagnose(focus.node, expression, focus.resource.resource) { prefix ->
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

    private companion object {
        const val STEP_WIDTH = 48
    }
}
