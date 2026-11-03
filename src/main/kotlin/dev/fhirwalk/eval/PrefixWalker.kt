package dev.fhirwalk.eval

import dev.fhirwalk.pool.PooledResource
import dev.fhirwalk.pool.ResourcePool
import org.hl7.fhir.instance.model.api.IBase
import org.hl7.fhir.instance.model.api.IBaseResource

/**
 * Answers "why is my expression empty" by evaluating successive prefixes and
 * reporting the first step that collapses to nothing.
 *
 * This is the dominant FHIRPath failure mode and almost nothing diagnoses it. It
 * needs no AST -- just the tokenizer's prefix split and the evaluator -- and because
 * we hold the pool it can also say whether the step is empty *everywhere* or only
 * on the resource in focus.
 */
class PrefixWalker(private val evaluator: Evaluator, private val pool: ResourcePool) {

    data class Step(
        val expression: String,
        val count: Int?,
        val typeName: String?,
        val error: String?,
        val reached: Boolean,
    )

    data class Diagnosis(
        val steps: List<Step>,
        /** Index of the first step yielding nothing, or null if the whole path resolved. */
        val firstEmpty: Int?,
        /** How many pool resources give a non-empty answer for the failing prefix. */
        val poolCoverage: Coverage?,
    )

    data class Coverage(val expression: String, val matching: Int, val considered: Int)

    /**
     * @param poolWide the expression to count across the pool for a prefix. Coverage
     *   is looked up by root resource type, so a path relative to a focus has to be
     *   rooted first; by default the prefix is taken as already rooted.
     * @param within the pool resource [input] is or sits in; see [Evaluator.evaluate].
     */
    fun diagnose(
        input: IBase,
        expression: String,
        within: IBaseResource? = input as? IBaseResource,
        poolWide: (String) -> String = { it },
    ): Diagnosis {
        val prefixes = FhirPathTokenizer.prefixes(expression)
        val steps = mutableListOf<Step>()
        var firstEmpty: Int? = null

        for ((idx, prefix) in prefixes.withIndex()) {
            if (firstEmpty != null) {
                // Past the break: later prefixes cannot say anything useful.
                steps += Step(prefix, null, null, null, reached = false)
                continue
            }
            when (val outcome = evaluator.evaluate(input, prefix, within)) {
                is Outcome.Failure -> {
                    steps += Step(prefix, null, null, outcome.message, reached = true)
                    firstEmpty = idx
                }
                is Outcome.Values -> {
                    val typeName = outcome.values.firstOrNull()?.let { it.javaClass.simpleName }
                    steps += Step(prefix, outcome.values.size, typeName, null, reached = true)
                    if (outcome.values.isEmpty()) firstEmpty = idx
                }
            }
        }

        val coverage = firstEmpty?.let { idx ->
            val failing = poolWide(prefixes[idx])
            val candidates = candidatesFor(failing)
            if (candidates.isEmpty()) {
                null
            } else {
                val result = evaluator.evaluateAll("$failing.exists()", candidates)
                Coverage(failing, result.hits.count { hit -> hit.values.any { isTrue(it) } }, candidates.size)
            }
        }

        return Diagnosis(steps, firstEmpty, coverage)
    }

    /** How many [Survey.considered] resources get through a step: every prefix up to it non-empty. */
    data class Reach(val expression: String, val reached: Int?, val errors: Int, val error: String?)

    data class Survey(
        val steps: List<Reach>,
        /** Index of the first step no resource gets through, or null if some get to the end. */
        val firstEmpty: Int?,
        val considered: Int,
        /** A resource that gets as far as any does, to focus and diagnose by itself. */
        val furthest: PooledResource?,
    )

    /**
     * The diagnosis with no focus: how many [candidates] get through each step.
     *
     * Counting values on one resource picked at random answers the wrong question
     * when the expression filters (`where(…)`): the step "goes empty" on that
     * resource although others pass it. A resource that goes empty or fails at a
     * step is not counted at any later one, so the counts never rise, even past
     * `exists()` or `empty()`, which answer something for an empty input too.
     */
    fun survey(expression: String, candidates: List<PooledResource>): Survey {
        val prefixes = FhirPathTokenizer.prefixes(expression)
        val reached = IntArray(prefixes.size)
        val errors = IntArray(prefixes.size)
        val firstError = arrayOfNulls<String>(prefixes.size)
        var deepest = -1
        var furthest: PooledResource? = null
        for (item in candidates) {
            var depth = -1
            for ((idx, prefix) in prefixes.withIndex()) {
                val outcome = evaluator.evaluate(item.resource, prefix)
                if (outcome is Outcome.Failure) {
                    errors[idx]++
                    if (firstError[idx] == null) firstError[idx] = outcome.message
                    break
                }
                if ((outcome as Outcome.Values).values.isEmpty()) break
                reached[idx]++
                depth = idx
            }
            if (depth > deepest) {
                deepest = depth
                furthest = item
            }
        }
        val firstEmpty = reached.indexOfFirst { it == 0 }.takeIf { it >= 0 }
        val steps = prefixes.mapIndexed { idx, prefix ->
            val past = firstEmpty != null && idx > firstEmpty
            Reach(prefix, if (past) null else reached[idx], errors[idx], firstError[idx])
        }
        return Survey(steps, firstEmpty, candidates.size, furthest)
    }

    /**
     * The resources [survey] counts for an expression: those of its root type in
     * scope, or none. A count over the pool answers for what `:all` ran over, so
     * `:why` after "nothing matched" counts the same resources.
     */
    fun surveyCandidates(expression: String): List<PooledResource> =
        rootTypeOf(expression)?.let { pool.inScope(it) } ?: emptyList()

    /**
     * The resource to diagnose against when nothing is in focus.
     *
     * It must be of the expression's own root type: diagnosing a Specimen expression
     * against a Patient reports "empty at step one" and teaches nothing.
     */
    fun diagnosisTarget(expression: String): PooledResource? =
        rootTypeOf(expression)?.let { pool.ofType(it).firstOrNull() } ?: pool.all().firstOrNull()

    /** Only resources of the expression's root type can meaningfully be compared. */
    private fun candidatesFor(expression: String): List<PooledResource> =
        rootTypeOf(expression)?.let { pool.ofType(it) } ?: emptyList()

    private fun rootTypeOf(expression: String): String? =
        FhirPathTokenizer.segments(expression).firstOrNull()?.name?.takeIf { it.isNotBlank() }

    private fun isTrue(value: IBase): Boolean =
        (value as? org.hl7.fhir.instance.model.api.IPrimitiveType<*>)?.valueAsString == "true"
}
