package dev.fhirwalk.pool

/**
 * The resources a delta touched, paired across its two ends: each as it was and as
 * it is.
 *
 * FHIR gives a resource no identity beyond its `Type/id` (or its fullUrl), so that is
 * the only thing paired by, and only where it is unambiguous. A resource is paired
 * with the one of the same label on the other side when that label occurs at most
 * once on each side; a missing side means it was added or deleted. Everything else
 * is set aside rather than guessed at:
 *
 * - [duplicated]: a label held more than once on either side, every resource with it;
 * - [unnamed]: a resource with neither id nor fullUrl, known only by its place in a
 *   file, which a writer may reorder.
 *
 * Both are kept by side, [Aside.before] and [Aside.after], since one resource set
 * aside is usually on both.
 *
 * Only what the delta changed is looked at, on both sides: the resources of the
 * files it changed or deleted, as the base held them, and of the files it added or
 * changed, as the revision holds them. A resource that moved to another file is in
 * both and pairs; one in a file the delta left alone is not looked at, so a label it
 * shares cannot stand in for one the delta added or deleted.
 *
 * @param before the base's resources from the files the delta changed or deleted.
 * @param after the revision's resources from the files it added or changed.
 */
class DeltaPairs(before: List<PooledResource>, after: List<PooledResource>) {

    /** As it was, as it is; either side null where it was added or deleted. */
    class Pair(val before: PooledResource?, val after: PooledResource?) {
        /** The side that names it: as it is, or as it was when it went. */
        val resource: PooledResource get() = after ?: before!!
    }

    /** Resources not paired, as the base held them and as the revision holds them. */
    class Aside(val before: List<PooledResource>, val after: List<PooledResource>) {
        val isEmpty: Boolean get() = before.isEmpty() && after.isEmpty()

        fun filter(keep: (PooledResource) -> Boolean) = Aside(before.filter(keep), after.filter(keep))
    }

    val pairs: List<Pair>
    val duplicated: Aside
    val unnamed: Aside

    init {
        val was = before.groupBy { labelOf(it) }
        val now = after.groupBy { labelOf(it) }
        unnamed = Aside(was[null].orEmpty(), now[null].orEmpty())
        val pairs = mutableListOf<Pair>()
        val duplicatedBefore = mutableListOf<PooledResource>()
        val duplicatedAfter = mutableListOf<PooledResource>()
        // The revision's order first, then what only the base held.
        val labels = LinkedHashSet<String>().apply {
            now.keys.filterNotNullTo(this)
            was.keys.filterNotNullTo(this)
        }
        for (label in labels) {
            val then = was[label].orEmpty()
            val after = now[label].orEmpty()
            if (then.size > 1 || after.size > 1) {
                duplicatedBefore += then
                duplicatedAfter += after
            } else {
                pairs += Pair(then.singleOrNull(), after.singleOrNull())
            }
        }
        this.pairs = pairs
        this.duplicated = Aside(duplicatedBefore, duplicatedAfter)
    }

    companion object {
        /** What names a resource in every revision: `Type/id`, else its fullUrl; null for neither. */
        fun labelOf(resource: PooledResource): String? = resource.key ?: resource.fullUrl
    }
}
