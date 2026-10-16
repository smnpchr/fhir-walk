package dev.fhirwalk.eval

import dev.fhirwalk.pool.PooledResource
import dev.fhirwalk.pool.ResourcePool
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

    fun diagnose(resource: IBaseResource, expression: String): Diagnosis {
        val prefixes = FhirPathTokenizer.prefixes(expression)
        val steps = mutableListOf<Step>()
        var firstEmpty: Int? = null

        for ((idx, prefix) in prefixes.withIndex()) {
            if (firstEmpty != null) {
                // Past the break: later prefixes cannot say anything useful.
                steps += Step(prefix, null, null, null, reached = false)
                continue
            }
            when (val outcome = evaluator.evaluate(resource, prefix)) {
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
            val failing = prefixes[idx]
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

    /** Only resources of the expression's root type can meaningfully be compared. */
    private fun candidatesFor(expression: String): List<PooledResource> {
        val root = FhirPathTokenizer.segments(expression).firstOrNull()?.name ?: return emptyList()
        return pool.ofType(root)
    }

    private fun isTrue(value: org.hl7.fhir.instance.model.api.IBase): Boolean =
        (value as? org.hl7.fhir.instance.model.api.IPrimitiveType<*>)?.valueAsString == "true"
}
