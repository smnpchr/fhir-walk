package dev.fhirwalk.model

import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.context.FhirVersionEnum

/**
 * The only version-aware code in the project. Everything downstream takes a
 * [FhirContext] and is therefore version-agnostic by construction.
 *
 * @param definitions where the NPM package holding this version's base
 *   StructureDefinitions lives, for versions whose definitions HAPI ships only as
 *   packages (R5 on). Read by [PackageDefinitions]; null where HAPI reads them
 *   itself (R4).
 */
enum class FhirVersion(
    val id: String,
    private val factory: () -> FhirContext,
    private val definitions: Definitions? = null,
) {
    R4("r4", FhirContext::forR4),
    R5("r5", FhirContext::forR5, Definitions("org/hl7/fhir/r5/packages", "hl7.fhir.r5.core-")),

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
            definitions?.let { where ->
                // Checked now, so `:fhir` refuses the switch rather than reporting
                // success and then failing every expression.
                val found = PackageDefinitions.locate(where.directory, where.namePrefix)
                    ?: throw FhirVersionUnavailable(this, IllegalStateException(where.toString()), where.missing())
                ctx.validationSupport = PackageDefinitions(ctx, found)
            }
        }
    } catch (e: NoClassDefFoundError) {
        // Only a genuinely absent structures JAR is reported as such -- anything else
        // must surface as itself rather than hide behind a misleading message.
        throw FhirVersionUnavailable(this, e)
    } catch (e: ClassNotFoundException) {
        throw FhirVersionUnavailable(this, e)
    } catch (e: RuntimeException) {
        // How HAPI itself says the structures JAR is missing (`FhirContext.forDstu3()`);
        // any other failure is not that, and surfaces as itself.
        if (e.message?.startsWith(STRUCTURES_MISSING) == true) throw FhirVersionUnavailable(this, e)
        throw e
    }

    /**
     * True when this version loads: its structures are on the classpath and, where
     * it needs one, its definitions package. Asked without building a context, which
     * is slow, and kept: the classpath does not change while the process runs.
     */
    fun isAvailable(): Boolean = available

    private val available: Boolean by lazy {
        FhirVersionEnum.valueOf(name).isPresentOnClasspath &&
            (definitions == null || PackageDefinitions.locate(definitions.directory, definitions.namePrefix) != null)
    }

    /** What this version needs before it loads: `hapi-fhir-structures-dstu3`. */
    val missing: String
        get() = if (!FhirVersionEnum.valueOf(name).isPresentOnClasspath) "hapi-fhir-structures-$id" else "hapi-fhir-validation-resources-$id"

    companion object {
        val DEFAULT = R4

        fun parse(text: String): FhirVersion? =
            entries.firstOrNull { it.id.equals(text, ignoreCase = true) }

        fun ids(): String = entries.joinToString(", ") { it.id }

        /** The versions that load, as [ids] spells them. */
        fun availableIds(): String = entries.filter { it.isAvailable() }.joinToString(", ") { it.id }

        /** HAPI's message code for a structures JAR that is not on the classpath. */
        private const val STRUCTURES_MISSING = "HAPI-1680:"
    }
}

/** A classpath NPM package, found by name so its version can change under it. */
data class Definitions(val directory: String, val namePrefix: String) {
    fun missing() = "no $directory/$namePrefix*.tgz on the classpath"
}

class FhirVersionUnavailable(
    val version: FhirVersion,
    cause: Throwable,
    detail: String? = null,
) : RuntimeException(
    if (detail == null) {
        "FHIR ${version.id.uppercase()} structures are not on the classpath. " +
            "Add ca.uhn.hapi.fhir:hapi-fhir-structures-${version.id} to build.gradle.kts."
    } else {
        "FHIR ${version.id.uppercase()} definitions are not on the classpath ($detail). " +
            "Add ca.uhn.hapi.fhir:hapi-fhir-validation-resources-${version.id} to build.gradle.kts."
    },
    cause,
)
