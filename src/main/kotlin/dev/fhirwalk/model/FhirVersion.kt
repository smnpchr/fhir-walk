package dev.fhirwalk.model

import ca.uhn.fhir.context.FhirContext

/**
 * The only version-aware code in the project. Everything downstream takes a
 * [FhirContext] and is therefore version-agnostic by construction.
 *
 * @param definitions the classpath NPM package holding this version's base
 *   StructureDefinitions, for versions whose definitions HAPI ships only as packages
 *   (R5 on). Read by [PackageDefinitions]; null where HAPI reads them itself (R4).
 */
enum class FhirVersion(
    val id: String,
    private val factory: () -> FhirContext,
    private val definitions: String? = null,
) {
    R4("r4", FhirContext::forR4),
    R5("r5", FhirContext::forR5, "/org/hl7/fhir/r5/packages/hl7.fhir.r5.core-5.0.0.tgz"),

    // Structures not shipped by default -- uncomment the matching dependency in
    // build.gradle.kts and these start working. The factory call itself throws a
    // clear HAPI error when the structures JAR is absent, which `create()` wraps.
    // R4B ships its definitions as a package too, so it would also need its
    // package path here, as R5 has.
    R4B("r4b", FhirContext::forR4B),
    DSTU3("dstu3", FhirContext::forDstu3),
    ;

    /** Contexts are expensive to build and thread-safe once built, so callers cache. */
    fun create(): FhirContext = try {
        factory().also { ctx ->
            // Without this, HAPI looks for its package reader in hapi-fhir-validation,
            // which is not a dependency, and newFhirPath() fails with HAPI-2330.
            definitions?.let { ctx.validationSupport = PackageDefinitions(ctx, it) }
        }
    } catch (e: NoClassDefFoundError) {
        // Only a genuinely absent structures JAR is reported as such -- anything else
        // must surface as itself rather than hide behind a misleading message.
        throw FhirVersionUnavailable(this, e)
    } catch (e: ClassNotFoundException) {
        throw FhirVersionUnavailable(this, e)
    }

    /** True when this version's structures are on the classpath. */
    fun isAvailable(): Boolean = try {
        create()
        true
    } catch (e: FhirVersionUnavailable) {
        false
    }

    companion object {
        val DEFAULT = R4

        fun parse(text: String): FhirVersion? =
            entries.firstOrNull { it.id.equals(text, ignoreCase = true) }

        fun ids(): String = entries.joinToString(", ") { it.id }
    }
}

class FhirVersionUnavailable(val version: FhirVersion, cause: Throwable) : RuntimeException(
    "FHIR ${version.id.uppercase()} structures are not on the classpath. " +
        "Add ca.uhn.hapi.fhir:hapi-fhir-structures-${version.id} to build.gradle.kts.",
    cause,
)
