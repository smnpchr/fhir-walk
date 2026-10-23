package dev.fhirwalk.pool

import org.hl7.fhir.instance.model.api.IBaseResource

/**
 * A resource in the pool, with enough provenance to point the user back at the file
 * it came from. Provenance is what makes a pool-wide result list navigable rather
 * than just a count.
 *
 * @param part the bundle it belongs to in a meta pool, where `resolve()` stays
 *   inside it; null in any other pool.
 */
class PooledResource(
    val index: Int,
    val resource: IBaseResource,
    val type: String,
    val id: String?,
    val fullUrl: String?,
    val origin: String,
    val part: String? = null,
) {
    /** Canonical `Type/id`, the primary key `resolve()` looks up. */
    val key: String? = id?.let { "$type/$it" }

    /** In a meta pool prefixed by its [part], `specimen-r1:Patient/p1`, since every bundle may hold a Patient/p1. */
    val label: String get() = part?.let { "$it:" }.orEmpty() + (key ?: fullUrl ?: "$type[#$index]")

    override fun toString(): String = "$label  ($origin)"
}
