package dev.fhirwalk.repl.command

import dev.fhirwalk.eval.Evaluator
import dev.fhirwalk.eval.FhirPathTokenizer
import dev.fhirwalk.eval.Outcome
import dev.fhirwalk.pool.DeltaPairs
import dev.fhirwalk.pool.PooledResource
import dev.fhirwalk.repl.Output
import dev.fhirwalk.repl.Session

/**
 * How an answer changed across a git pool's delta: the expression at the base and
 * at the revision, for every resource the delta touched.
 *
 * Kept apart from [GitCommands], which moves the pool; this only reads both ends.
 */
internal class ChangeCommands(private val state: ReplState) : CommandGroup {

    private val session: Session get() = state.session

    override val commands = listOf(command(":changes", run = ::changes))

    /**
     * `:changes Specimen.type.coding.code`: the delta's resources of the expression's
     * root type, each answered at the base and at the revision, counted by the pair
     * of answers. A resource whose answer stayed the same is counted, not listed.
     *
     * Resources pair as [DeltaPairs] has it: by label, only where that is unambiguous;
     * the rest are counted as not compared and listed on `:more`. Answers compare as
     * `:trace` compares them, by type and whole text. The base side evaluates against
     * the base snapshot, so `resolve()` reads what was there.
     */
    private fun changes(argument: String, out: Output) {
        val expression = argument.trim()
        state.lastPicks = null
        state.nameable = null
        if (expression.isEmpty()) {
            out.text(USAGE)
            return
        }
        val git = session.current.git ?: run {
            out.text("${state.poolName()} is not a git pool — :changes compares the two ends of a delta; :open <name> <dir>@<rev> opens one")
            return
        }
        state.lastExpression = expression
        if (state.reportSyntaxError(expression, out)) return
        val first = FhirPathTokenizer.segments(expression).firstOrNull()?.name.orEmpty()
        val types = session.navigator.resourceTypes()
        // FHIRPath names are case-sensitive: `specimen.type` is an element, empty on every resource.
        types.firstOrNull { it.equals(first, ignoreCase = true) && it != first }?.let {
            out.text("$first is not a resource type — FHIRPath spells it $it")
            return
        }
        val before = try {
            git.before()
        } catch (e: Exception) {
            out.error("cannot read ${git.source.path}: ${e.message ?: e.javaClass.simpleName}")
            return
        }
        val baseName = git.delta.base?.short
        before.base.problems.forEach { out.error("at $baseName: $it") }

        val root = first.takeIf { it in types }
        fun counts(resource: PooledResource) = root == null || resource.type == root
        val all = DeltaPairs(before.base.changed, git.delta.added + git.delta.modified)
        val pairs = all.pairs.filter { counts(it.resource) }
        val duplicated = all.duplicated.filter(::counts)
        val unnamed = all.unnamed.filter(::counts)
        val what = root ?: "resource"
        val span = baseName?.let { "from $it to ${git.commit.short}" } ?: "of ${git.commit.short}, the first commit"
        if (pairs.isEmpty() && duplicated.isEmpty && unnamed.isEmpty) {
            state.lastListing = null
            out.text("no $what in the delta $span")
            return
        }

        val groups = LinkedHashMap<String, Group>()
        var unchanged = 0
        var errors = 0
        var firstError: String? = null
        var matched = false
        for (pair in pairs) {
            val from = pair.before?.let { answer(before.evaluator, it, expression) } ?: Answer.ABSENT
            val to = pair.after?.let { answer(session.evaluator, it, expression) } ?: Answer.ABSENT
            val failures = listOf(from.outcome, to.outcome).filterIsInstance<Outcome.Failure>()
            // A resource that fails at both ends is one error, not two.
            if (failures.isNotEmpty()) {
                errors++
                if (firstError == null) firstError = failures.first().message
            }
            if (listOf(from.outcome, to.outcome).any { it is Outcome.Values && it.values.isNotEmpty() }) matched = true
            if (from.exactly == to.exactly) {
                unchanged++
                continue
            }
            groups.getOrPut("${from.exactly}\u0002${to.exactly}") { Group(from, to) }.resources += pair.resource
        }
        // Answers that only differ where the one-line form cannot show it are spelled
        // out, with their types: a pair that reads `5 → 5`, and every pair once two read
        // alike. Spelled, every pair reads differently.
        val alike = groups.values.groupingBy { it.shown }.eachCount().any { it.value > 1 }
        groups.values.filter { alike || it.from.shown == it.to.shown }.forEach { it.shown = "${it.from.spelled} → ${it.to.spelled}" }
        val ranked = groups.values.sortedWith(compareByDescending<Group> { it.resources.size }.thenBy { it.shown })

        val changed = pairs.size - unchanged
        val header = Output.line {
            +"${pairs.size} $what(s) in the delta $span; "
            +if (changed == 0) "no answer changed" else "$changed answer(s) changed"
            if (errors > 0) error("; $errors error(s): $firstError")
            notCompared(duplicated, unnamed)?.let { hint("; $it") }
        }
        state.nameable = expression.takeIf { matched }
        if (matched) state.record(expression, out)
        val aside = asideLines(duplicated, unnamed)
        if (ranked.isEmpty() && aside.isEmpty()) {
            state.lastListing = null
            out.add(header)
            if (!matched && errors == 0) out.text("  nothing matched on either side — :why")
            return
        }
        val width = (ranked.firstOrNull()?.resources?.size ?: 0).toString().length
        fun counted(group: Group): String = group.resources.size.toString().padStart(width)
        out.listing(
            listOf(header),
            rows = ranked.map { group ->
                Output.line {
                    +"  "
                    count(counted(group))
                    +"  "
                    shrink(group.shown, Output.Kind.PREVIEW, cutsEnd = true)
                }
            },
            // Whole, each pair is its two answers, then the resources that gave it;
            // after them, the resources that were not compared.
            full = ranked.flatMap { group ->
                listOf(Output.line { +"  "; count(counted(group)); +"  "; +group.shown }) +
                    whole("before", group.from) + whole("after", group.to) +
                    group.resources.map(::resourceLine)
            } + aside,
        )
    }

    /**
     * `not compared: Patient/p1 held more than once, 1 resource without id or fullUrl
     * — :more lists them`. Counted per side, the larger: one id-less resource the
     * delta changed is on both, and is one resource.
     */
    private fun notCompared(duplicated: DeltaPairs.Aside, unnamed: DeltaPairs.Aside): String? {
        if (duplicated.isEmpty && unnamed.isEmpty) return null
        val labels = (duplicated.before + duplicated.after).mapNotNull(DeltaPairs::labelOf).distinct()
        val count = maxOf(unnamed.before.size, unnamed.after.size)
        val why = listOfNotNull(
            labels.takeIf { it.isNotEmpty() }?.let { held ->
                (if (held.size <= 2) held.joinToString(", ") else "${held.size} labels") + " held more than once"
            },
            count.takeIf { it > 0 }?.let { "$it resource(s) without id or fullUrl" },
        )
        return "not compared: ${why.joinToString(", ")} — :more lists them"
    }

    /** What [notCompared] counts, resource by resource, each under the side that holds it. */
    private fun asideLines(duplicated: DeltaPairs.Aside, unnamed: DeltaPairs.Aside): List<Output.Line> {
        fun section(title: String, aside: DeltaPairs.Aside): List<Output.Line> =
            if (aside.isEmpty) {
                emptyList()
            } else {
                listOf(Output.line { +"  "; hint("not compared, $title:") }) +
                    listOf("before" to aside.before, "after" to aside.after).filter { it.second.isNotEmpty() }.flatMap { (side, resources) ->
                        listOf(Output.line { +"      "; hint(side) }) + resources.map(::resourceLine)
                    }
            }
        return section("a label held more than once on a side", duplicated) + section("without id or fullUrl", unnamed)
    }

    /** A resource under a pair: its label and the file it came from, which tells two of one label apart. */
    private fun resourceLine(resource: PooledResource): Output.Line =
        Output.line { +"        "; label(resource.label); +"  "; hint("(${resource.origin})") }

    private fun answer(evaluator: Evaluator, resource: PooledResource, expression: String): Answer =
        when (val outcome = evaluator.evaluate(resource.resource, expression)) {
            is Outcome.Failure -> Answer(outcome, "!${outcome.message}", "! ${outcome.message}", "! ${outcome.message}")
            is Outcome.Values -> Answer(
                outcome,
                exactly = "=" + session.render.exactly(outcome.values),
                shown = outcome.values.joinToString(", ") { session.render.value(it) }.ifEmpty { "{}" },
                spelled = outcome.values.joinToString(", ") { value ->
                    "${session.render.typeOf(value)} ${quoted(session.render.pretty(value).joinToString("\n"))}"
                }.ifEmpty { "{}" },
            )
        }

    private fun whole(side: String, answer: Answer): List<Output.Line> {
        val head = Output.line { +"      "; hint(side) }
        return listOf(head) + when (val outcome = answer.outcome) {
            null -> listOf(Output.text("        —"))
            is Outcome.Failure -> listOf(Output.line { +"        "; error(answer.shown) })
            is Outcome.Values ->
                if (outcome.values.isEmpty()) listOf(Output.text("        {}")) else outcome.values.flatMap { state.expanded("        ", it) }
        }
    }

    /** [text] in double quotes, with what would not show escaped, as `:tally` spells values. */
    private fun quoted(text: String): String =
        "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\t", "\\t") + "\""

    /**
     * One side's answer: [exactly] compares, [shown] is its one-line form, [spelled]
     * the form that tells apart answers [shown] runs together. [outcome] is null for a
     * resource that is not on that side.
     */
    private class Answer(val outcome: Outcome?, val exactly: String, val shown: String, val spelled: String) {
        companion object {
            val ABSENT = Answer(null, "-", "—", "—")
        }
    }

    /** A pair of answers, and the resources that gave it, in the delta's order. */
    private class Group(val from: Answer, val to: Answer) {
        var shown = "${from.shown} → ${to.shown}"
        val resources = mutableListOf<PooledResource>()
    }

    private companion object {
        const val USAGE = "usage: :changes <expr>  —  how the answer changed across the delta, before → after, e.g. :changes Specimen.type.coding.code"
    }
}
