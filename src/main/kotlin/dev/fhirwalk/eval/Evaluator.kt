package dev.fhirwalk.eval

import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.fhirpath.IFhirPath
import ca.uhn.fhir.fhirpath.IFhirPathEvaluationContext
import dev.fhirwalk.pool.PooledResource
import dev.fhirwalk.pool.ResourcePool
import org.hl7.fhir.instance.model.api.IBase
import org.hl7.fhir.instance.model.api.IIdType

/**
 * Hands the pool to HAPI as the implementation of `resolve()`.
 *
 * `IFhirPathEvaluationContext` is the version-independent counterpart of
 * `IHostApplicationServices`, so this one class makes `resolve()` work across every
 * supported FHIR version -- and across every file and bundle in the pool, which no
 * single-resource evaluator can do.
 */
class PoolEvaluationContext(private val pool: ResourcePool) : IFhirPathEvaluationContext {
    override fun resolveReference(theReference: IIdType?, theContext: IBase?): IBase? =
        pool.resolve(theReference)
}

sealed interface Outcome {
    data class Values(val values: List<IBase>) : Outcome
    data class Failure(val message: String) : Outcome
}

/** One resource's answer during a pool-wide evaluation. */
data class Hit(val resource: PooledResource, val values: List<IBase>)

class Evaluator(private val ctx: FhirContext, private val pool: ResourcePool) {

    private val fhirPath: IFhirPath = ctx.newFhirPath().apply {
        setEvaluationContext(PoolEvaluationContext(pool))
    }

    /**
     * @param input a resource, or any element inside one. HAPI takes either; with an
     *   element there is no focus resource, so `%resource` fails.
     */
    fun evaluate(input: IBase, expression: String): Outcome =
        try {
            Outcome.Values(fhirPath.evaluate(input, expression, IBase::class.java))
        } catch (e: Exception) {
            Outcome.Failure(readable(e))
        }

    /**
     * Checks an expression parses, without needing a resource to run it against.
     *
     * Callers evaluate this first: a syntax error is a property of the expression,
     * not of any resource, so reporting it once beats reporting it per resource.
     */
    fun validate(expression: String): String? =
        try {
            fhirPath.parse(expression)
            null
        } catch (e: Exception) {
            readable(e)
        }

    /**
     * HAPI wraps lexer failures so the message arrives as
     * `HAPI-2409: org.hl7.fhir.r4.fhirpath.FHIRLexer$FHIRLexerException: Error @1, 14: …`.
     * Only the tail is about the user's expression; the rest is plumbing.
     */
    private fun readable(e: Exception): String {
        var message = e.message?.lineSequence()?.firstOrNull()?.trim()
            ?: return e.javaClass.simpleName
        repeat(3) {
            val stripped = message
                .replace(HAPI_CODE, "")
                .replace(NESTED_EXCEPTION, "")
            if (stripped == message) return@repeat
            message = stripped
        }
        return message.ifBlank { e.javaClass.simpleName }
    }

    /**
     * The pool-wide fan-out: one expression, every resource in scope. Only non-empty answers
     * come back as hits; [misses] counts the rest so callers can report coverage.
     */
    fun evaluateAll(expression: String, candidates: List<PooledResource> = pool.inScope()): PoolResult {
        val hits = mutableListOf<Hit>()
        var misses = 0
        var firstError: String? = null
        var errors = 0
        for (item in candidates) {
            when (val outcome = evaluate(item.resource, expression)) {
                is Outcome.Values ->
                    if (outcome.values.isEmpty()) misses++ else hits += Hit(item, outcome.values)
                is Outcome.Failure -> {
                    errors++
                    if (firstError == null) firstError = outcome.message
                }
            }
        }
        return PoolResult(hits, misses, errors, firstError, candidates.size)
    }

    data class PoolResult(
        val hits: List<Hit>,
        val misses: Int,
        val errors: Int,
        val firstError: String?,
        val considered: Int,
    )

    private companion object {
        val HAPI_CODE = Regex("^HAPI-\\d+:\\s*")
        val NESTED_EXCEPTION = Regex("^(?:[a-z][\\w]*\\.)+[\\w$]*(?:Exception|Error):\\s*")
    }
}
