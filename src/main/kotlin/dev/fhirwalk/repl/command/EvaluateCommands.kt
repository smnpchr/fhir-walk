package dev.fhirwalk.repl.command

import dev.fhirwalk.eval.FhirPathTokenizer
import dev.fhirwalk.eval.Focus
import dev.fhirwalk.eval.Outcome
import dev.fhirwalk.pool.PooledResource
import dev.fhirwalk.repl.Output
import dev.fhirwalk.repl.Session
import org.hl7.fhir.instance.model.api.IBase

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
        command(":tally", run = ::tally),
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
                    label(state.shown(hit.resource).padEnd(ReplState.LABEL_WIDTH))
                    +" "
                    // Every value, joined as a :view cell joins them; the printer cuts
                    // what does not fit, and :more shows each whole. The count stays
                    // in sight however much is cut, and tells "a, b" from "a", "b".
                    shrink(joined(hit.values), Output.Kind.PREVIEW, cutsEnd = true)
                    if (hit.values.size > 1) hint("  (${hit.values.size} values)")
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

    /**
     * [values] joined with ", " for one row, which is only ever printed cut to the
     * window: past [ROW_CAP] characters the rest is not encoded, only marked.
     */
    private fun joined(values: List<IBase>): String {
        val text = StringBuilder()
        for ((i, value) in values.withIndex()) {
            if (i > 0) text.append(", ")
            if (text.length > ROW_CAP) return text.append("…").toString()
            text.append(session.render.value(value, ROW_CAP))
        }
        return text.toString()
    }

    /**
     * `:tally <expr>`: each distinct value the expression gives across the scope,
     * with how many resources hold it, most first.
     *
     * Pool-wide evaluation runs per resource, so `distinct()` only removes repeats
     * within one; this is the count across them. A resource holding a value twice
     * counts once. Values are told apart by type and unflattened text, so two
     * structured values are the same when their JSON is, as in `:trace`, while
     * `"Stool"` and `"Stool "`, or the string `"5"` and the integer `5`, are not:
     * a tally is where such differences should show.
     */
    private fun tally(argument: String, out: Output) {
        val expression = argument.trim()
        state.lastPicks = null
        state.nameable = null
        if (expression.isEmpty()) {
            out.text("usage: :tally <expr>  —  how many resources hold each value, e.g. :tally Observation.status")
            return
        }
        state.lastExpression = expression
        if (state.reportSyntaxError(expression, out)) return
        if (session.pool.size == 0) {
            out.text("pool is empty — :load <path> first")
            return
        }
        val result = session.evaluator.evaluateAll(expression)
        val meta = session.current.meta
        val byBundle = meta != null && meta.scoped == null
        val tallies = LinkedHashMap<String, Tally>()
        for (hit in result.hits) {
            for (value in hit.values) {
                val type = session.render.typeOf(value)
                val raw = session.render.pretty(value).joinToString("\n")
                val tally = tallies.getOrPut("$type\u0000$raw") { Tally(value, type, raw, session.render.value(value)) }
                // A hit's values all come before the next hit's: the last holder is this one, or it is new.
                if (tally.resources.lastOrNull() !== hit.resource) tally.resources += hit.resource
            }
        }
        // Values that only differ where the one-line form cannot show it are spelled out.
        val shared = tallies.values.groupingBy { it.shown }.eachCount().filterValues { it > 1 }.keys
        for (tally in tallies.values) {
            if (tally.shown in shared) tally.shown = "${tally.type} ${quoted(tally.raw)}"
        }
        val ranked = tallies.values.sortedWith(compareByDescending<Tally> { it.resources.size }.thenBy { it.shown })
        val header = Output.line {
            +"${result.hits.size} of ${result.considered} resource(s) matched"
            if (byBundle) +", in ${result.hits.mapTo(HashSet()) { it.resource.part }.size} of ${meta!!.members.size} bundle(s)"
            +"; ${ranked.size} distinct value(s)"
            if (result.errors > 0) error("; ${result.errors} error(s): ${result.firstError}")
        }
        state.nameable = expression.takeIf { result.hits.isNotEmpty() }
        if (result.hits.isEmpty()) {
            out.add(header)
            if (result.errors == 0) out.text("  nothing matched — :why")
            state.lastListing = null
            return
        }
        state.record(expression, out)
        val width = ranked.first().resources.size.toString().length
        fun counted(tally: Tally): String = tally.resources.size.toString().padStart(width)
        fun bundles(tally: Tally): String = " in ${tally.resources.mapTo(HashSet()) { it.part }.size} bundle(s)"
        out.listing(
            listOf(header),
            rows = ranked.map { tally ->
                Output.line {
                    +"  "
                    count(counted(tally))
                    +"  "
                    shrink(tally.shown, Output.Kind.PREVIEW, cutsEnd = true)
                    if (byBundle) hint(" ·" + bundles(tally))
                }
            },
            // Whole, each value is followed by the resources that hold it.
            full = ranked.flatMap { tally ->
                state.expanded("  ${counted(tally)}  ", tally.value) +
                    tally.resources.map { resource -> Output.line { +"      "; label(resource.label) } }
            },
        )
    }

    /** [text] in double quotes, with what would not show escaped: `"Stool "`, `"a\nb"`. */
    private fun quoted(text: String): String =
        "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\t", "\\t") + "\""

    /**
     * One distinct value of a `:tally`: the first instance seen, its type and whole
     * text, how a row shows it, and the resources holding it, in pool order.
     */
    private class Tally(val value: IBase, val type: String, val raw: String, var shown: String) {
        val resources = mutableListOf<PooledResource>()
    }

    /** @param argument the expression, or blank to re-diagnose the last one. */
    private fun why(argument: String, out: Output) {
        // Whatever was listed before is not what :more should print after a :why.
        state.lastListing = null
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
        if (session.focus == null) {
            val candidates = session.prefixWalker.surveyCandidates(expression)
            if (candidates.isNotEmpty()) {
                survey(expression, root!!, candidates, out)
                return
            }
            if (root != null && session.pool.scope != null && session.pool.ofType(root).isNotEmpty()) {
                out.text("no $root in scope — :scope all to count the whole pool")
                return
            }
        }
        val focus = session.focus
            ?: (session.prefixWalker.diagnosisTarget(expression) ?: session.pool.all().first()).let { Focus.of(it) }
        // A relative step is rooted at the focus, then stripped of where()/[n] the way
        // the census spells it: "how many Observations populate code.coding.foo" is
        // the question, not how many share this one's indexes.
        val diagnosis = session.prefixWalker.diagnose(focus.node, expression, focus.resource.resource) { prefix ->
            val rooted = session.focus?.absolute(prefix)
            if (rooted == null || rooted == prefix) prefix else session.navigator.censusPath(rooted)
        }
        val steps = diagnosis.steps.mapIndexed { idx, step ->
            Step(
                count = when {
                    !step.reached -> "—"
                    step.error != null -> "!"
                    else -> "${step.count}"
                },
                marker = if (idx != diagnosis.firstEmpty) null else if (step.error != null) "fails here" else "empty here",
                error = step.error,
            )
        }
        val after = mutableListOf<Output.Line>()
        if (diagnosis.firstEmpty == null) {
            after += Output.text("  path resolves fully")
        } else {
            diagnosis.poolCoverage?.let { coverage ->
                after += Output.line {
                    +"  "
                    shrink(coverage.expression)
                    +" is populated in ${coverage.matching} of ${coverage.considered} resource(s) of that type"
                }
                if (coverage.matching == 0) {
                    after += Output.text("  nothing in the pool populates it — check the element name or the data")
                }
            }
        }
        val header = if (session.focus == null) listOf(Output.text("(no focus; diagnosing against ${focus.label})")) else emptyList()
        showSteps(expression, header, steps, after, out)
    }

    /** A row of `:why`: what the step gives, and whether the path breaks there. */
    private class Step(val count: String, val marker: String?, val error: String?)

    /**
     * Prints `:why`'s steps as a listing, the count first: the step is what gives way
     * to the window, cut in the middle so both ends of a long extension URL stay, and
     * `:more` prints every step whole. Each row shows only the step it adds, not the
     * whole prefix: with long extension URLs in the path, repeating the prefix makes
     * the table unreadable.
     */
    private fun showSteps(expression: String, header: List<Output.Line>, steps: List<Step>, after: List<Output.Line>, out: Output) {
        val labels = FhirPathTokenizer.segments(expression).filter { it.text.isNotBlank() }
            .mapIndexed { idx, segment -> if (idx == 0) segment.text else ".${segment.text}" }
        val width = steps.maxOf { it.count.length }
        val rows = steps.mapIndexed { idx, step ->
            Output.line {
                +"  "
                val count = step.count.padStart(width)
                if (step.error != null && step.count == "!") error(count) else +count
                +"  "
                shrink(labels.getOrNull(idx) ?: expression)
                step.marker?.let { +"  <-- $it" }
                step.error?.let {
                    +"  "
                    shrink("! $it", Output.Kind.ERROR, cutsEnd = true)
                }
            }
        }
        // The conclusion is a footer: the row limit may hold steps back, never it.
        out.listing(header, rows, footer = after)
    }

    /** :why with no focus: how many resources of the root type get through each step. */
    private fun survey(expression: String, root: String, candidates: List<PooledResource>, out: Output) {
        val survey = session.prefixWalker.survey(expression, candidates)
        val where = if (session.pool.scope != null) "$root resources in scope" else "$root resources"
        val steps = survey.steps.mapIndexed { idx, step ->
            // Every resource that got this far failed here: it errs rather than goes empty.
            val arrived = if (idx == 0) survey.considered else survey.steps[idx - 1].reached
            val failed = step.reached == 0 && step.errors > 0 && step.errors == arrived
            Step(
                count = step.reached?.toString() ?: "—",
                marker = if (idx != survey.firstEmpty) null else if (failed) "fails here" else "empty here",
                error = step.error?.takeIf { step.errors > 0 }?.let { "${step.errors} failed: $it" },
            )
        }
        val furthest = survey.furthest
        val after = when {
            survey.firstEmpty == null ->
                Output.text("  path resolves fully in ${survey.steps.last().reached} of ${survey.considered} $root")
            survey.firstEmpty == 0 -> Output.text("  nothing in the pool populates it — check the element name or the data")
            furthest != null -> Output.line {
                +"  "
                shrink(furthest.label, Output.Kind.LABEL)
                +" gets as far as any — :focus it, then :why, to see its values"
            }
            else -> null
        }
        showSteps(expression, listOf(Output.text("(no focus; counting the $where that get through each step)")), steps, listOfNotNull(after), out)
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
        /** Wider than any window a row is fitted to. */
        const val ROW_CAP = 2000
    }
}
