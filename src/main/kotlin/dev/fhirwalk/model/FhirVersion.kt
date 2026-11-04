package dev.fhirwalk.model

import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.context.FhirVersionEnum

/**
 * The only version-aware code in the project. Everything downstream takes a
 * [FhirContext] and is therefore version-agnostic by construction.
 *
 * A version loads with two JARs: its structures, and its base StructureDefinitions
 * (`hapi-fhir-validation-resources-*`), without which `ofType()` fails on every
 * resource. R4 and R5 ship both; R4B and DSTU3 need both lines uncommented in
 * build.gradle.kts.
 *
 * @param profiles the classpath file that shows the definitions are there, for
 *   versions HAPI reads them for itself as loose files (R4, R4B, DSTU3).
 * @param definitions where the NPM package holding this version's base
 *   StructureDefinitions lives, for versions whose definitions HAPI ships only as
 *   packages (R5 on). Read by [PackageDefinitions].
 */
enum class FhirVersion(
    val id: String,
    private val factory: () -> FhirContext,
    private val profiles: String? = null,
    private val definitions: Definitions? = null,
) {
    R4("r4", FhirContext::forR4, profiles = "org/hl7/fhir/r4/model/profile/profiles-resources.xml"),
    R5("r5", FhirContext::forR5, definitions = Definitions("org/hl7/fhir/r5/packages", "hl7.fhir.r5.core-")),

    // Not shipped by default: uncomment both of their dependency lines in
    // build.gradle.kts, structures and validation-resources, and these load.
    R4B("r4b", FhirContext::forR4B, profiles = "org/hl7/fhir/r4b/model/profile/profiles-resources.xml"),
    DSTU3("dstu3", FhirContext::forDstu3, profiles = "org/hl7/fhir/dstu3/model/profile/profiles-resources.xml"),
    ;

    /**
     * Contexts are expensive to build and thread-safe once built, so callers cache.
     * Throws [FhirVersionUnavailable] when either JAR is missing: checked now, so
     * `:fhir` refuses the switch rather than reporting success and then failing
     * every expression that needs a definition.
     */
    fun create(): FhirContext = try {
        if (!hasDefinitions()) throw FhirVersionUnavailable(this, IllegalStateException(definitionsLabel()))
        factory().also { ctx ->
            // Without this, HAPI looks for its package reader in hapi-fhir-validation,
            // which is not a dependency, and newFhirPath() fails with HAPI-2330.
            definitions?.let { where ->
                val found = checkNotNull(PackageDefinitions.locate(where.directory, where.namePrefix)) { where.missing() }
                ctx.validationSupport = PackageDefinitions(ctx, found)
            }
        }
    } catch (e: NoClassDefFoundError) {
        // Only a genuinely absent structures JAR is reported as such -- anything else
        // must surface as itself rather than hide behind a misleading message.
        throw FhirVersionUnavailable(this, e)
    } catch (e: ClassNotFoundException) {
        throw FhirVersionUnavailable(this, e)
    } catch (e: FhirVersionUnavailable) {
        throw e
    } catch (e: RuntimeException) {
        // How HAPI itself says the structures JAR is missing (`FhirContext.forDstu3()`);
        // any other failure is not that, and surfaces as itself.
        if (e.message?.startsWith(STRUCTURES_MISSING) == true) throw FhirVersionUnavailable(this, e)
        throw e
    }

    /**
     * True when this version loads: its structures and its definitions are on the
     * classpath. Asked without building a context, which is slow, and kept: the
     * classpath does not change while the process runs.
     */
    fun isAvailable(): Boolean = available

    private val available: Boolean by lazy { missing.isEmpty() }

    /**
     * The dependencies this version still needs before it loads, as named in
     * build.gradle.kts: `hapi-fhir-structures-dstu3`, `hapi-fhir-validation-resources-dstu3`.
     * Empty for one that loads.
     */
    val missing: List<String>
        get() = listOfNotNull(
            "hapi-fhir-structures-$id".takeUnless { FhirVersionEnum.valueOf(name).isPresentOnClasspath },
            "hapi-fhir-validation-resources-$id".takeUnless { hasDefinitions() },
        )

    private fun hasDefinitions(): Boolean = when {
        definitions != null -> PackageDefinitions.locate(definitions.directory, definitions.namePrefix) != null
        profiles != null -> FhirVersion::class.java.classLoader.getResource(profiles) != null
        else -> true
    }

    private fun definitionsLabel(): String = definitions?.missing() ?: "no $profiles on the classpath"

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

/** A version that cannot load: says every dependency it still needs, as build.gradle.kts names them. */
class FhirVersionUnavailable(
    val version: FhirVersion,
    cause: Throwable,
) : RuntimeException(
    "FHIR ${version.id.uppercase()} cannot load here: add " +
        version.missing.ifEmpty { listOf("hapi-fhir-structures-${version.id}") }
            .joinToString(" and ") { "ca.uhn.hapi.fhir:$it" } +
        " to build.gradle.kts.",
    cause,
)
