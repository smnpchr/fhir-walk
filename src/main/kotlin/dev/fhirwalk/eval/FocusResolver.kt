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
 * Kept apart from the REPL so the rules can be tested: `[n]`, `#index`, then a label,
 * then an expression. `Observation/obs2` is a label and also a valid expression (a
 * division), so labels are tried first and the expression reading is the fallback.
 * `[n]` can never be valid FHIRPath on its own, so it picks a row of the last
 * numbered listing without shadowing anything.
 */
class FocusResolver(
    private val evaluator: Evaluator,
    private val pool: ResourcePool,
    private val navigator: ModelNavigator,
    private val typeOf: (IBase) -> String,
) {

    /**
     * One result of an expression, with the resource it was found in.
     *
     * @param position where it came in that resource's own results, which is what
     *   an indexer on the expression would select it by.
     * @param siblings how many results that resource gave in all.
     */
    data class Found(val owner: PooledResource, val value: IBase, val position: Int, val siblings: Int)

    /**
     * The rows of a numbered listing, for `:focus [n]`. Built with [picksOf] or by
     * [resolve]; numbered lazily, since few listings are ever picked from.
     *
     * @param from the focus the expression ran against, or null for the pool. A pick
     *   is only meaningful while that is still the focus.
     */
    class Picks internal constructor(val expression: String, val from: Focus?, rows: () -> List<Found>) {
        val results: List<Found> by lazy(rows)
    }

    sealed interface Resolution {
        /**
         * Whether the argument was taken as an expression and evaluated, as opposed to
         * a label, an `#index` or a pick: callers that track "the expression just run"
         * must know which.
         */
        val evaluated: Boolean get() = false

        /**
         * @param picked whether it came from a row of [Picks], which stay pickable.
         * @param evaluated whether an expression found it, rather than a label or `#index`.
         */
        data class Focused(
            val focus: Focus,
            val picked: Boolean = false,
            override val evaluated: Boolean = false,
        ) : Resolution
        data class NotInPool(val argument: String) : Resolution
        data class CannotPick(val reason: String) : Resolution
        data class Invalid(val message: String) : Resolution {
            override val evaluated: Boolean get() = true
        }
        data class Failed(val message: String) : Resolution {
            override val evaluated: Boolean get() = true
        }

        /** @param couldBeLabel whether the argument may have been meant as a label. */
        data class Nothing(val couldBeLabel: Boolean) : Resolution {
            override val evaluated: Boolean get() = true
        }

        /** @param picks the same results, numbered for `:focus [n]`. */
        data class Several(val picks: Picks, val owners: Int) : Resolution {
            override val evaluated: Boolean get() = true
        }
    }

    /** @param picks the rows of the last numbered listing, if any. */
    fun resolve(argument: String, current: Focus?, picks: Picks? = null): Resolution {
        PICK.matchEntire(argument.trim())?.let { return pick(it.groupValues[1], current, picks) }
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
                is Outcome.Values -> found(current.resource, outcome.values)
            }
        } else {
            val result = evaluator.evaluateAll(expression)
            if (result.hits.isEmpty() && result.errors > 0) return Resolution.Failed(result.firstError.orEmpty())
            result.hits.flatMap { hit -> found(hit.resource, hit.values) }
        }
        val distinct = distinctByInstance(found)
        val one = distinct.singleOrNull()
            ?: return if (distinct.isEmpty()) {
                Resolution.Nothing(couldBeLabel = current?.isResource != false)
            } else {
                Resolution.Several(Picks(expression, current) { distinct }, distinct.map { it.owner }.distinct().size)
            }
        return Resolution.Focused(focusOn(one, expression, current), evaluated = true)
    }

    /** @param number the digits as typed, so a message never names another number. */
    private fun pick(number: String, current: Focus?, picks: Picks?): Resolution {
        picks ?: return Resolution.CannotPick("nothing numbered to pick from — run an expression first")
        if (picks.from !== current) return Resolution.CannotPick("the focus changed since that listing — rerun it")
        val found = number.toIntOrNull()?.let { picks.results.getOrNull(it) }
            ?: return Resolution.CannotPick("no [$number] — that listing has [0] to [${picks.results.lastIndex}]")
        val index = found.position.takeIf { found.siblings > 1 }
        return Resolution.Focused(focusOn(found, picks.expression, picks.from, index), picked = true)
    }

    /** The rows of [values], [expression]'s result against [focus], for `:focus [n]`. */
    fun picksOf(expression: String, focus: Focus, values: List<IBase>): Picks =
        Picks(expression, focus) { found(focus.resource, values) }

    /** Numbers [values] the way an indexer on the expression would. */
    private fun found(owner: PooledResource, values: List<IBase>): List<Found> =
        values.mapIndexed { idx, value -> Found(owner, value, idx, values.size) }

    /**
     * Several resources that reference the same one each `resolve()` to that same
     * instance, which is one target, not an ambiguity.
     */
    private fun distinctByInstance(found: List<Found>): List<Found> {
        val seen = Collections.newSetFromMap(IdentityHashMap<IBase, Boolean>())
        return found.filter { seen.add(it.value) }
    }

    /**
     * A resource result, reached by `resolve()` or by a pool-wide query, becomes an
     * ordinary resource focus when the pool holds it. Anything else is an element.
     */
    private fun focusOn(found: Found, expression: String, parent: Focus?, index: Int? = null): Focus {
        (found.value as? IBaseResource)?.let(pool::entryFor)?.let { return Focus.of(it, parent) }
        return Focus.reached(found.owner, found.value, typeOf(found.value), expression, parent, navigator, index)
    }

    private companion object {
        /** Loose on purpose: `[ 1 ]` and `[-1]` are meant as picks, not as FHIRPath. */
        val PICK = Regex("""\[\s*(-?\d+)\s*]""")
    }
}
