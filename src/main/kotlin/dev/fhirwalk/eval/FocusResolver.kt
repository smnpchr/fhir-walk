package dev.fhirwalk.eval

import dev.fhirwalk.model.ModelNavigator
import dev.fhirwalk.pool.PooledResource
import dev.fhirwalk.pool.ResourcePool
import org.hl7.fhir.instance.model.api.IBase
import org.hl7.fhir.instance.model.api.IBaseResource
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Turns a `:focus` argument into a [Focus], or says why it cannot.
 *
 * Kept apart from the REPL so the rules can be tested: `#index`, then a label, then
 * an expression. `Observation/obs2` is a label and also a valid expression (a
 * division), so labels are tried first and the expression reading is the fallback.
 */
class FocusResolver(
    private val evaluator: Evaluator,
    private val pool: ResourcePool,
    private val navigator: ModelNavigator,
    private val typeOf: (IBase) -> String,
) {

    /** One result of a focus expression, with the resource it was found in. */
    data class Found(val owner: PooledResource, val value: IBase)

    sealed interface Resolution {
        data class Focused(val focus: Focus) : Resolution
        data class NotInPool(val argument: String) : Resolution
        data class Invalid(val message: String) : Resolution
        data class Failed(val message: String) : Resolution

        /** @param couldBeLabel whether the argument may have been meant as a label. */
        data class Nothing(val couldBeLabel: Boolean) : Resolution

        /** @param hint how to narrow to one, ready to print. */
        data class Several(val results: List<Found>, val owners: Int, val hint: String) : Resolution
    }

    fun resolve(argument: String, current: Focus?): Resolution {
        if (argument.startsWith("#")) {
            val target = pool.byIndex(argument.drop(1).toIntOrNull() ?: -1)
                ?: return Resolution.NotInPool(argument)
            return Resolution.Focused(Focus.of(target, current))
        }
        // With something in focus, a bare word is far likelier a relative path than
        // the bare id of some resource, so only full labels count then.
        val target = pool.find(argument, bareIds = current == null)
            ?: pool.all().firstOrNull { it.label == argument }
        if (target != null) return Resolution.Focused(Focus.of(target, current))
        return byExpression(argument, current)
    }

    private fun byExpression(expression: String, current: Focus?): Resolution {
        evaluator.validate(expression)?.let { return Resolution.Invalid(it) }
        val found = if (current != null) {
            when (val outcome = evaluator.evaluate(current.node, expression)) {
                is Outcome.Failure -> return Resolution.Failed(outcome.message)
                is Outcome.Values -> outcome.values.map { Found(current.resource, it) }
            }
        } else {
            val result = evaluator.evaluateAll(expression)
            if (result.hits.isEmpty() && result.errors > 0) return Resolution.Failed(result.firstError.orEmpty())
            result.hits.flatMap { hit -> hit.values.map { Found(hit.resource, it) } }
        }
        val distinct = distinctByInstance(found)
        val one = distinct.singleOrNull()
            ?: return if (distinct.isEmpty()) {
                Resolution.Nothing(couldBeLabel = current?.isResource != false)
            } else {
                several(expression, distinct)
            }
        return Resolution.Focused(focusOn(one, expression, current))
    }

    /**
     * Several resources that reference the same one each `resolve()` to that same
     * instance, which is one target, not an ambiguity.
     */
    private fun distinctByInstance(found: List<Found>): List<Found> {
        val seen = Collections.newSetFromMap(IdentityHashMap<IBase, Boolean>())
        return found.filter { seen.add(it.value) }
    }

    private fun several(expression: String, results: List<Found>): Resolution.Several {
        val owners = results.map { it.owner }.distinct().size
        // An indexer applies per resource, so [n] only narrows results that all came
        // from one resource; across several, pick the resource first.
        val hint = if (owners == 1) {
            // `name|contact.name[0]` indexes only the right operand.
            val indexed = if (FhirPathTokenizer.isPath(expression)) expression else "($expression)"
            "pick it with  :focus $indexed[0]"
        } else {
            ":focus a label first, or narrow with where()"
        }
        return Resolution.Several(results, owners, hint)
    }

    /**
     * A resource result, reached by `resolve()` or by a pool-wide query, becomes an
     * ordinary resource focus when the pool holds it. Anything else is an element.
     */
    private fun focusOn(found: Found, expression: String, parent: Focus?): Focus {
        (found.value as? IBaseResource)?.let(pool::entryFor)?.let { return Focus.of(it, parent) }
        return Focus.reached(found.owner, found.value, typeOf(found.value), expression, parent, navigator)
    }
}
