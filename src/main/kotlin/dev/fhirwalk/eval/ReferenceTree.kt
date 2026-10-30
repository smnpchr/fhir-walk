package dev.fhirwalk.eval

import dev.fhirwalk.pool.PooledResource
import dev.fhirwalk.pool.ResourcePool
import org.hl7.fhir.instance.model.api.IBaseReference
import org.hl7.fhir.instance.model.api.IBaseResource

/**
 * The resources an expression links, as a forest: each resource's answer names its
 * parents (`Specimen.parent`), and the forest hangs every child under them, roots
 * first. The links are a graph, not a tree -- a child may have two parents, and
 * nothing stops a cycle -- so this is its depth-first forest: a resource is shown
 * once, in full, and wherever else a link reaches it, the row says where it is
 * rather than repeating what is below. That keeps it to one row per link.
 *
 * @param rows in the order they print.
 * @param trees how many roots the rows hang from, counting a cycle that has none.
 * @param linked how many resources the rows show.
 * @param notReferences values that are neither a Reference nor a resource.
 * @param notPooled resources an answer gave that the pool does not hold, such as contained ones.
 */
class ReferenceTree(
    val rows: List<Row>,
    val trees: Int,
    val linked: Int,
    val notReferences: Int,
    val notPooled: Int,
    val errors: Int,
    val firstError: String?,
) {
    /**
     * @param depth 0 for a root.
     * @param missing what it refers to that is not there to resolve, said where it is shown in full.
     * @param contained the references it makes to resources contained in it, which the pool does not hold.
     */
    data class Row(
        val depth: Int,
        val resource: PooledResource,
        val mark: Mark = Mark.None,
        val missing: List<String> = emptyList(),
        val contained: List<String> = emptyList(),
    )

    sealed interface Mark {
        /** Shown here in full. */
        data object None : Mark

        /** Already shown, under [parent], or as a root when null. */
        data class Shown(val parent: PooledResource?) : Mark

        /** On the path to here: the link closes a cycle. */
        data object Cycle : Mark
    }

    companion object {
        /**
         * @param candidates the resources whose answers are links: what is in scope.
         *   A parent outside it still shows, since it is in the pool.
         * @param from the root to show alone, with what hangs below it; null for every root.
         */
        fun build(
            evaluator: Evaluator,
            pool: ResourcePool,
            expression: String,
            candidates: List<PooledResource>,
            from: PooledResource? = null,
        ): ReferenceTree {
            val result = evaluator.evaluateAll(expression, candidates)
            val parents = LinkedHashMap<PooledResource, MutableSet<PooledResource>>()
            val children = HashMap<PooledResource, MutableList<PooledResource>>()
            val missing = HashMap<PooledResource, MutableSet<String>>()
            val contained = HashMap<PooledResource, MutableSet<String>>()
            var notReferences = 0
            var notPooled = 0
            for (hit in result.hits) {
                for (value in hit.values) {
                    val target = when (value) {
                        is IBaseResource -> pool.entryFor(value) ?: run {
                            notPooled++
                            null
                        }
                        is IBaseReference -> {
                            val reference = value.referenceElement
                            val text = reference?.value
                            // resolve() hands back the pool's own instance.
                            pool.resolve(reference, hit.resource.resource)?.let(pool::entryFor) ?: run {
                                val into = if (text?.startsWith("#") == true) contained else missing
                                into.getOrPut(hit.resource) { linkedSetOf() } += text ?: "a Reference by identifier only"
                                null
                            }
                        }
                        else -> {
                            notReferences++
                            null
                        }
                    } ?: continue
                    if (parents.getOrPut(hit.resource) { linkedSetOf() }.add(target)) {
                        children.getOrPut(target) { mutableListOf() } += hit.resource
                    }
                }
            }
            val nodes = (parents.keys + children.keys + missing.keys + contained.keys).sortedBy { it.index }
            children.values.forEach { list -> list.sortBy { it.index } }

            val walk = Walk(children, missing, contained)
            if (from != null) {
                if (from in nodes) walk.from(from)
            } else {
                nodes.filter { parents[it].isNullOrEmpty() }.forEach(walk::from)
                // A cycle has no root: what is left hangs from one, so it starts there.
                nodes.forEach { if (it !in walk.state) walk.from(onCycle(it, parents)) }
            }
            return ReferenceTree(walk.rows, walk.trees, walk.state.size, notReferences, notPooled, result.errors, result.firstError)
        }

        /**
         * A resource on the cycle that [start] hangs from, found by climbing first
         * parents until one comes back. Only for one the roots did not reach: each
         * of its parents is one too, since a resource walked has had every child
         * walked, so the climb stays among them, and none is a root, so it has one.
         */
        private fun onCycle(start: PooledResource, parents: Map<PooledResource, Set<PooledResource>>): PooledResource {
            val seen = HashSet<PooledResource>()
            var at = start
            while (seen.add(at)) at = parents[at]?.firstOrNull() ?: return at
            return at
        }
    }

    /** Depth-first, by a stack of its own: a chain of links is as deep as the data makes it. */
    private class Walk(
        private val children: Map<PooledResource, List<PooledResource>>,
        private val missing: Map<PooledResource, Set<String>>,
        private val contained: Map<PooledResource, Set<String>>,
    ) {
        val rows = mutableListOf<Row>()
        var trees = 0

        /** False while on the path being walked, true once all below it is shown. */
        val state = HashMap<PooledResource, Boolean>()

        /** Where each resource was shown in full: under which parent, null for a root. */
        private val shownUnder = HashMap<PooledResource, PooledResource?>()

        private class Frame(val resource: PooledResource, val depth: Int) {
            var next = 0
        }

        fun from(root: PooledResource) {
            trees++
            val stack = ArrayDeque<Frame>()
            enter(root, null, 0, stack)
            while (stack.isNotEmpty()) {
                val top = stack.last()
                val below = children[top.resource].orEmpty()
                if (top.next == below.size) {
                    state[top.resource] = true
                    stack.removeLast()
                    continue
                }
                val child = below[top.next++]
                when (state[child]) {
                    false -> rows += Row(top.depth + 1, child, Mark.Cycle)
                    true -> rows += Row(top.depth + 1, child, Mark.Shown(shownUnder[child]))
                    null -> enter(child, top.resource, top.depth + 1, stack)
                }
            }
        }

        private fun enter(resource: PooledResource, parent: PooledResource?, depth: Int, stack: ArrayDeque<Frame>) {
            rows += Row(depth, resource, missing = missing[resource].orEmpty().toList(), contained = contained[resource].orEmpty().toList())
            state[resource] = false
            shownUnder[resource] = parent
            stack.addLast(Frame(resource, depth))
        }
    }
}
