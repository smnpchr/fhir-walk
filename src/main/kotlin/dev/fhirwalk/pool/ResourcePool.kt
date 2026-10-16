package dev.fhirwalk.pool

import ca.uhn.fhir.context.FhirContext
import org.hl7.fhir.instance.model.api.IBaseResource
import org.hl7.fhir.instance.model.api.IIdType

/**
 * Every resource imported in this session, indexed for `resolve()`.
 *
 * The pool is the whole point of the tool: it is what lets `resolve()` cross file
 * and bundle boundaries, and what lets an expression be answered over a corpus
 * rather than a single specimen.
 */
class ResourcePool(val ctx: FhirContext) {

    private val items = mutableListOf<PooledResource>()
    private val byKey = HashMap<String, PooledResource>()
    private val byFullUrl = HashMap<String, PooledResource>()
    private val byBareId = HashMap<String, MutableList<PooledResource>>()

    val census = Census()
    private val walker = CensusWalker(ctx, census)

    val size: Int get() = items.size

    fun all(): List<PooledResource> = items

    fun ofType(type: String): List<PooledResource> =
        items.filter { it.type.equals(type, ignoreCase = true) }

    fun typeCounts(): Map<String, Int> =
        items.groupingBy { it.type }.eachCount().toSortedMap()

    fun types(): List<String> = typeCounts().keys.toList()

    fun byIndex(index: Int): PooledResource? = items.getOrNull(index)

    fun find(reference: String): PooledResource? =
        byKey[reference] ?: byFullUrl[reference] ?: byBareId[reference]?.singleOrNull()

    fun add(resource: IBaseResource, fullUrl: String?, origin: String): PooledResource {
        val type = ctx.getResourceType(resource)
        val id = resource.idElement?.idPart?.takeIf { it.isNotBlank() }
        val item = PooledResource(items.size, resource, type, id, fullUrl, origin)
        items += item
        item.key?.let { byKey.putIfAbsent(it, item) }
        fullUrl?.takeIf { it.isNotBlank() }?.let { byFullUrl.putIfAbsent(it, item) }
        id?.let { byBareId.getOrPut(it) { mutableListOf() } += item }
        walker.visit(resource, type)
        return item
    }

    /**
     * Backs the `resolve()` function. Tries, in order: `Type/id`, absolute or
     * urn: fullUrl, and finally a bare id when it is unambiguous across the pool.
     *
     * Business-identifier resolution is deliberately absent for now -- it needs a
     * secondary index and an ambiguity policy, and is only worth it once real data
     * shows it is needed.
     */
    fun resolve(reference: IIdType?): IBaseResource? {
        if (reference == null) return null
        val raw = reference.value
        if (!raw.isNullOrBlank()) {
            byFullUrl[raw]?.let { return it.resource }
            byKey[raw]?.let { return it.resource }
        }
        val type = reference.resourceType
        val idPart = reference.idPart
        if (!type.isNullOrBlank() && !idPart.isNullOrBlank()) {
            byKey["$type/$idPart"]?.let { return it.resource }
        }
        if (!idPart.isNullOrBlank()) {
            byBareId[idPart]?.singleOrNull()?.let { return it.resource }
        }
        return null
    }

    fun clear() {
        items.clear()
        byKey.clear()
        byFullUrl.clear()
        byBareId.clear()
        census.clear()
    }
}
