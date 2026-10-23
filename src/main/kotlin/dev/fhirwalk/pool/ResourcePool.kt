package dev.fhirwalk.pool

import ca.uhn.fhir.context.FhirContext
import org.hl7.fhir.instance.model.api.IBaseResource
import org.hl7.fhir.instance.model.api.IIdType
import java.util.IdentityHashMap

/**
 * Every resource imported in this session, indexed for `resolve()`.
 *
 * The pool is the whole point of the tool: it is what lets `resolve()` cross file
 * and bundle boundaries, and what lets an expression be answered over a corpus
 * rather than a single specimen.
 *
 * A meta pool adds each bundle as a part ([PooledResource.part]). Every key is then
 * the part's: `resolve()` stays inside the bundle the resource came from, and a
 * label names its part, since the same Patient comes back in many exports.
 */
class ResourcePool(val ctx: FhirContext) {

    private val items = mutableListOf<PooledResource>()
    private val byKey = HashMap<String, PooledResource>()
    private val byFullUrl = HashMap<String, PooledResource>()
    private val byBareId = HashMap<String, MutableList<PooledResource>>()
    private val byInstance = IdentityHashMap<IBaseResource, PooledResource>()

    val census = Census()
    private val walker = CensusWalker(ctx, census)

    val size: Int get() = items.size

    fun all(): List<PooledResource> = items

    fun ofType(type: String): List<PooledResource> =
        items.filter { it.type.equals(type, ignoreCase = true) }

    /**
     * What pool-wide evaluation runs over, when that is less than everything: a
     * revision's delta. Lookup, `resolve()` and the census still see the whole pool,
     * since what changed in a commit usually references what did not.
     */
    var scope: Set<PooledResource>? = null

    /** The resources pool-wide evaluation runs over: [scope], or everything. */
    fun inScope(): List<PooledResource> = scope?.let { s -> items.filter { it in s } } ?: items

    fun inScope(type: String): List<PooledResource> = inScope().filter { it.type.equals(type, ignoreCase = true) }

    fun typeCounts(): Map<String, Int> =
        items.groupingBy { it.type }.eachCount().toSortedMap()

    fun types(): List<String> = typeCounts().keys.toList()

    fun byIndex(index: Int): PooledResource? = items.getOrNull(index)

    /**
     * @param bareIds whether an unambiguous bare id counts. `:focus` turns it off
     *   while something is in focus, where `code` means the element, not a
     *   resource that happens to have the id `code`.
     */
    fun find(reference: String, bareIds: Boolean = true): PooledResource? =
        byKey[reference] ?: byFullUrl[reference] ?: byBareId[reference]?.takeIf { bareIds }?.singleOrNull()
            ?: findInPart(reference, bareIds)

    /** `specimen-r1:Patient/p1`, a label: the part's own `Patient/p1`. */
    private fun findInPart(reference: String, bareIds: Boolean): PooledResource? {
        val part = reference.substringBefore(':', "").takeIf { it in parts } ?: return null
        val rest = reference.substringAfter(':')
        return byKey[keyIn(part, rest)] ?: byFullUrl[keyIn(part, rest)]
            ?: byBareId[keyIn(part, rest)]?.takeIf { bareIds }?.singleOrNull()
    }

    /** The parts in the order they were added: a meta pool's bundles. */
    val parts: Set<String> get() = partNames

    private val partNames = linkedSetOf<String>()

    /** The pool entry for this very instance, e.g. one `resolve()` returned. */
    fun entryFor(resource: IBaseResource): PooledResource? = byInstance[resource]

    /** @param part the bundle it belongs to in a meta pool; see [PooledResource.part]. */
    fun add(resource: IBaseResource, fullUrl: String?, origin: String, part: String? = null): PooledResource {
        val type = ctx.getResourceType(resource)
        val id = resource.idElement?.idPart?.takeIf { it.isNotBlank() }
        val item = PooledResource(items.size, resource, type, id, fullUrl, origin, part)
        items += item
        part?.let { partNames += it }
        item.key?.let { byKey.putIfAbsent(keyIn(part, it), item) }
        fullUrl?.takeIf { it.isNotBlank() }?.let { byFullUrl.putIfAbsent(keyIn(part, it), item) }
        id?.let { byBareId.getOrPut(keyIn(part, it)) { mutableListOf() } += item }
        byInstance[resource] = item
        walker.visit(resource, type)
        return item
    }

    /**
     * Backs the `resolve()` function. Tries, in order: `Type/id`, absolute or
     * urn: fullUrl, and finally a bare id when it is unambiguous across the pool --
     * in a meta pool, across the part [within] belongs to, and only there.
     *
     * Business-identifier resolution is deliberately absent for now -- it needs a
     * secondary index and an ambiguity policy, and is only worth it once real data
     * shows it is needed.
     *
     * @param within the resource the reference sits in.
     */
    fun resolve(reference: IIdType?, within: IBaseResource? = null): IBaseResource? {
        if (reference == null) return null
        val part = within?.let { byInstance[it]?.part }
        // A part's keys all carry it, so a reference from outside every part finds none.
        if (part == null && partNames.isNotEmpty()) return null
        val raw = reference.value
        if (!raw.isNullOrBlank()) {
            byFullUrl[keyIn(part, raw)]?.let { return it.resource }
            byKey[keyIn(part, raw)]?.let { return it.resource }
        }
        val type = reference.resourceType
        val idPart = reference.idPart
        if (!type.isNullOrBlank() && !idPart.isNullOrBlank()) {
            byKey[keyIn(part, "$type/$idPart")]?.let { return it.resource }
        }
        if (!idPart.isNullOrBlank()) {
            byBareId[keyIn(part, idPart)]?.singleOrNull()?.let { return it.resource }
        }
        return null
    }

    private fun keyIn(part: String?, key: String): String = if (part == null) key else "$part:$key"

    /**
     * Types the references the census could not type from their string, by resolving
     * them the same way `resolve()` does at eval time -- so completion after
     * `resolve()` sees exactly what evaluation would. Called after each import,
     * because a reference is typically walked before its target is loaded.
     */
    fun settleReferences() {
        census.pendingReferences.removeAll { (path, reference, from) ->
            val target = resolve(reference, from) ?: return@removeAll false
            census.referenceTargets.getOrPut(path) { linkedMapOf() }
                .merge(ctx.getResourceType(target), 1, Int::plus)
            true
        }
    }

    fun clear() {
        scope = null
        items.clear()
        byKey.clear()
        byFullUrl.clear()
        byBareId.clear()
        byInstance.clear()
        partNames.clear()
        census.clear()
    }
}
