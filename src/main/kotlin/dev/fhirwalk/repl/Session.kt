package dev.fhirwalk.repl

import ca.uhn.fhir.context.FhirContext
import dev.fhirwalk.eval.Evaluator
import dev.fhirwalk.eval.PrefixWalker
import dev.fhirwalk.eval.ViewBuilder
import dev.fhirwalk.model.FhirVersion
import dev.fhirwalk.model.ModelNavigator
import dev.fhirwalk.pool.Importer
import dev.fhirwalk.eval.Focus
import dev.fhirwalk.eval.FocusResolver
import dev.fhirwalk.pool.ResourcePool

/**
 * Everything that depends on the chosen FHIR version, in one object.
 *
 * `:fhir r5` replaces this wholesale, which is why switching versions empties every
 * pool: a pool is homogeneous by construction. Mixed-version pools are the one
 * complexity we refuse outright.
 *
 * It holds several named [Pool]s, one of them current. The pool, evaluator and the
 * rest are the current pool's, so code that works on "the pool" needs no idea that
 * there are others.
 *
 * @param poolNames the pools to start with, empty; the first is current.
 */
class Session(val version: FhirVersion, poolNames: List<String> = listOf(Pool.DEFAULT)) {
    val ctx: FhirContext = version.create()
    val render = Render(ctx)

    private val byName = LinkedHashMap<String, Pool>()

    init {
        poolNames.ifEmpty { listOf(Pool.DEFAULT) }.forEach { open(it) }
    }

    var current: Pool = byName.values.first()
        private set

    /** In the order they were opened. */
    val pools: List<Pool> get() = byName.values.toList()

    fun pool(name: String): Pool? = byName[name]

    /** A new, empty pool, which does not become current. Null when [name] is taken. */
    fun open(name: String): Pool? {
        if (name in byName) return null
        return Pool(name, ctx, render).also { byName[name] = it }
    }

    /** @return false when there is no such pool. */
    fun switchTo(name: String): Boolean {
        current = byName[name] ?: return false
        return true
    }

    /**
     * Drops a pool; closing the current one makes the first remaining one current.
     * The last pool cannot be closed: there is always something to evaluate against.
     */
    fun close(name: String): Boolean {
        if (byName.size == 1 || name !in byName) return false
        byName.remove(name)
        if (current.name == name) current = byName.values.first()
        return true
    }

    val pool: ResourcePool get() = current.pool
    val evaluator: Evaluator get() = current.evaluator
    val navigator: ModelNavigator get() = current.navigator
    val prefixWalker: PrefixWalker get() = current.prefixWalker
    val importer: Importer get() = current.importer
    val focusResolver: FocusResolver get() = current.focusResolver
    val viewBuilder: ViewBuilder get() = current.viewBuilder

    var focus: Focus?
        get() = current.focus
        set(value) {
            current.focus = value
        }
}

/**
 * One pool and everything bound to it: its census, so completion reflects what is in
 * this pool and no other, and an evaluator whose `resolve()` stays inside it. Each
 * pool keeps its own focus, so switching away and back finds it where it was.
 */
class Pool(val name: String, ctx: FhirContext, render: Render) {
    val pool = ResourcePool(ctx)
    val evaluator = Evaluator(ctx, pool)
    val navigator = ModelNavigator(ctx, pool.census)
    val prefixWalker = PrefixWalker(evaluator, pool)
    val importer = Importer(ctx, pool)
    val focusResolver = FocusResolver(evaluator, pool, navigator, render::typeOf)
    val viewBuilder = ViewBuilder(evaluator, pool, navigator, render::typeOf)

    var focus: Focus? = null

    /** Absolute paths loaded into this pool, saved so the next session loads them again. */
    val loadedPaths = mutableListOf<String>()

    companion object {
        /** The pool a session starts with, and the one everything went into before there were several. */
        const val DEFAULT = "pool"
    }
}
