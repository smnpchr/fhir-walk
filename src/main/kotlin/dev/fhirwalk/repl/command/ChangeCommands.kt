package dev.fhirwalk.repl.command

import dev.fhirwalk.eval.Evaluator
import dev.fhirwalk.eval.FhirPathTokenizer
import dev.fhirwalk.eval.Outcome
import dev.fhirwalk.pool.PooledResource
import dev.fhirwalk.repl.GitPool
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
     * Resources pair by `Type/id` or fullUrl, so one that moved file is the same
     * resource on both sides; one with neither has no counterpart. Answers compare
     * as `:trace` compares them: every value whole, whitespace included. The base
     * side evaluates against the base snapshot, so `resolve()` reads what was there.
     */
    private fun changes(argument: String, out: Output) {
        val expression = argument.trim()
        state.lastPicks = null
        state.nameable = null
        state.lastListing = null
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
        val before = try {
            git.before()
        } catch (e: Exception) {
            out.error("cannot read ${git.source.path}: ${e.message ?: e.javaClass.simpleName}")
            return
        }
        val baseName = git.delta.base?.short
        before.base.problems.forEach { out.error("at $baseName: $it") }

        val root = FhirPathTokenizer.segments(expression).firstOrNull()?.name
            ?.takeIf { it in session.navigator.resourceTypes() }
        fun counts(resource: PooledResource) = root == null || resource.type == root
        val pairs = pairs(git, before).filter { (was, now) -> (was ?: now)!!.let(::counts) }
        val what = root ?: "resource"
        val span = baseName?.let { "from $it to ${git.commit.short}" } ?: "of ${git.commit.short}, the first commit"
        if (pairs.isEmpty()) {
            out.text("no $what in the delta $span")
            return
        }

        val groups = LinkedHashMap<String, Group>()
        var unchanged = 0
        var errors = 0
        var firstError: String? = null
        var matched = false
        for ((was, now) in pairs) {
            val then = was?.let { answer(before.evaluator, it, expression) }
            val after = now?.let { answer(session.evaluator, it, expression) }
            for (outcome in listOfNotNull(then?.outcome, after?.outcome)) {
                when (outcome) {
                    is Outcome.Failure -> {
                        errors++
                        if (firstError == null) firstError = outcome.message
                    }
                    is Outcome.Values -> if (outcome.values.isNotEmpty()) matched = true
                }
            }
            val from = then ?: Answer.ABSENT
            val to = after ?: Answer.ABSENT
            if (from.exactly == to.exactly) {
                unchanged++
                continue
            }
            groups.getOrPut("${from.exactly}\u0001${to.exactly}") { Group(from, to) }.resources += (now ?: was)!!
        }
        val ranked = groups.values.sortedWith(compareByDescending<Group> { it.resources.size }.thenBy { it.shown })
        // Pairs that only differ where the one-line form cannot show it are spelled out.
        val shared = ranked.groupingBy { it.shown }.eachCount().filterValues { it > 1 }.keys
        ranked.filter { it.shown in shared }.forEach { it.shown = "${it.from.spelled} → ${it.to.spelled}" }

        val changed = pairs.size - unchanged
        val header = Output.line {
            +"${pairs.size} $what(s) in the delta $span; "
            +if (changed == 0) "no answer changed" else "$changed answer(s) changed"
            if (errors > 0) error("; $errors error(s): $firstError")
        }
        state.nameable = expression.takeIf { matched }
        if (matched) state.record(expression, out)
        if (ranked.isEmpty()) {
            out.add(header)
            if (!matched && errors == 0) out.text("  nothing matched on either side — :why")
            return
        }
        val width = ranked.first().resources.size.toString().length
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
            // Whole, each pair is its two answers, then the resources that gave it.
            full = ranked.flatMap { group ->
                listOf(Output.line { +"  "; count(counted(group)); +"  "; +group.shown }) +
                    whole("before", group.from) + whole("after", group.to) +
                    group.resources.map { resource -> Output.line { +"      "; label(resource.label) } }
            },
        )
    }

    /**
     * Every resource the delta touched, as it was and as it is: either side null where
     * it was added or deleted. The base side holds the resources of the files the
     * delta changed; the revision's side, those it added or modified.
     */
    private fun pairs(git: GitPool, before: GitPool.Before): List<Pair<PooledResource?, PooledResource?>> {
        val pairs = mutableListOf<Pair<PooledResource?, PooledResource?>>()
        val paired = HashSet<PooledResource>()
        for (now in git.delta.inPool.sortedBy { it.index }) {
            val was = idOf(now)?.let { before.base.pool.find(it, bareIds = false) }
            was?.let { paired += it }
            pairs += was to now
        }
        for (was in before.base.changed) {
            if (was in paired) continue
            // Not among the revision's changed resources: deleted, unless it is held elsewhere.
            pairs += was to idOf(was)?.let { session.pool.find(it, bareIds = false) }
        }
        return pairs
    }

    /** What names a resource in every revision; null for one known only by its place. */
    private fun idOf(resource: PooledResource): String? = resource.key ?: resource.fullUrl

    private fun answer(evaluator: Evaluator, resource: PooledResource, expression: String): Answer =
        when (val outcome = evaluator.evaluate(resource.resource, expression)) {
            is Outcome.Failure -> Answer(outcome, "!${outcome.message}", "! ${outcome.message}", "! ${outcome.message}")
            is Outcome.Values -> {
                val raw = outcome.values.map { session.render.pretty(it).joinToString("\n") }
                Answer(
                    outcome,
                    exactly = "=" + raw.joinToString("\u0000"),
                    shown = outcome.values.joinToString(", ") { session.render.value(it) }.ifEmpty { "{}" },
                    spelled = raw.joinToString(", ") { quoted(it) }.ifEmpty { "{}" },
                )
            }
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

    /** A pair of answers, and the resources that gave it, in pool order. */
    private class Group(val from: Answer, val to: Answer) {
        var shown = "${from.shown} → ${to.shown}"
        val resources = mutableListOf<PooledResource>()
    }

    private companion object {
        const val USAGE = "usage: :changes <expr>  —  how the answer changed across the delta, before → after, e.g. :changes Specimen.type.coding.code"
    }
}
