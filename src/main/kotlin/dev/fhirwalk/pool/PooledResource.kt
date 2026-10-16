package dev.fhirwalk.pool

import org.hl7.fhir.instance.model.api.IBaseResource

/**
 * A resource in the pool, with enough provenance to point the user back at the file
 * it came from. Provenance is what makes a pool-wide result list navigable rather
 * than just a count.
 */
class PooledResource(
    val index: Int,
    val resource: IBaseResource,
    val type: String,
    val id: String?,
    val fullUrl: String?,
    val origin: String,
) {
    /** Canonical `Type/id`, the primary key `resolve()` looks up. */
    val key: String? = id?.let { "$type/$it" }

    val label: String get() = key ?: fullUrl ?: "$type[#$index]"

    override fun toString(): String = "$label  ($origin)"
}
