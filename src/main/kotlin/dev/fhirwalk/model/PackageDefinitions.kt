package dev.fhirwalk.model

import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.context.support.IValidationSupport
import com.fasterxml.jackson.databind.ObjectMapper
import org.hl7.fhir.instance.model.api.IBaseResource
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

/**
 * The base StructureDefinitions, read from an NPM package bundled on the classpath.
 *
 * From R5 on, HAPI ships its definitions as NPM packages (`.tgz`) rather than as
 * loose files, and its default reader for them lives in `hapi-fhir-validation` --
 * 29 modules, including an HTTP client, for what the FHIRPath engine uses as a
 * lookup of type definitions. This is that lookup and nothing more: it reads one
 * classpath resource and never talks to anything.
 *
 * **Lazy by design.** The engine asks for definitions by URL, a handful per
 * expression (`ofType(Quantity)` needs Quantity and its ancestors). Parsing all 307
 * up front took 5 s and held 200 MB; here the package is scanned once, each
 * definition kept deflated, and parsed only when first asked for. That is also why
 * [fetchAllStructureDefinitions] returns nothing: answering it would parse
 * everything, and the engine does not need it to evaluate.
 *
 * Version-independent: the package location comes from [FhirVersion], and parsing
 * goes through the context's own JSON parser.
 *
 * @param packageResource classpath path of the package, e.g.
 *   `/org/hl7/fhir/r5/packages/hl7.fhir.r5.core-5.0.0.tgz`.
 */
class PackageDefinitions(
    private val ctx: FhirContext,
    private val packageResource: String,
) : IValidationSupport {

    /** Each definition's JSON, deflated, by canonical URL. */
    private val packed: Map<String, ByteArray> by lazy { load() }

    private val parsed = ConcurrentHashMap<String, IBaseResource>()

    override fun getFhirContext(): FhirContext = ctx

    /** Deliberately empty; see the class comment. */
    override fun <T : IBaseResource> fetchAllStructureDefinitions(): List<T> = emptyList()

    override fun fetchStructureDefinition(url: String): IBaseResource? = definition(url)

    override fun <T : IBaseResource> fetchResource(type: Class<T>?, url: String): T? {
        val found = definition(url) ?: return null
        @Suppress("UNCHECKED_CAST")
        return if (type == null || type.isInstance(found)) found as T else null
    }

    private fun definition(url: String): IBaseResource? {
        parsed[url]?.let { return it }
        val bytes = packed[url] ?: return null
        val json = InflaterInputStream(bytes.inputStream()).use { String(it.readBytes(), Charsets.UTF_8) }
        return parsed.computeIfAbsent(url) { ctx.newJsonParser().parseResource(json) }
    }

    /**
     * One pass over the package. Its `.index.json` maps each file to the canonical
     * URL inside it, so no definition has to be parsed to be found later.
     */
    private fun load(): Map<String, ByteArray> {
        val stream = javaClass.getResourceAsStream(packageResource)
            ?: error(
                "FHIR definitions not on the classpath: $packageResource — " +
                    "add the matching hapi-fhir-validation-resources JAR to build.gradle.kts",
            )
        var index: ByteArray? = null
        val byFile = HashMap<String, ByteArray>()
        GZIPInputStream(stream).use { gzip ->
            tarEntries(gzip) { name, bytes ->
                when {
                    name == "package/.index.json" -> index = bytes
                    name.startsWith("package/StructureDefinition-") && name.endsWith(".json") ->
                        byFile[name.removePrefix("package/")] = deflate(bytes)
                }
            }
        }
        val files = index?.let { ObjectMapper().readTree(it).path("files") }
            ?: error("$packageResource has no package/.index.json — not a FHIR NPM package?")
        return files
            .filter { it.path("resourceType").asText() == "StructureDefinition" }
            .mapNotNull { file ->
                val url = file.path("url").asText().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                byFile[file.path("filename").asText()]?.let { url to it }
            }
            .toMap()
    }

    private fun deflate(bytes: ByteArray): ByteArray =
        ByteArrayOutputStream(bytes.size / 4).also { out -> DeflaterOutputStream(out).use { it.write(bytes) } }
            .toByteArray()

    /**
     * The regular files of a tar stream. An NPM package is plain ustar: 512-byte
     * headers, each file padded to a 512-byte boundary, two zero blocks at the end.
     * Long names would come as PAX headers; npm's own packer does not emit them for
     * the short `package/…` names a FHIR package uses, so they are skipped like any
     * other non-file entry.
     */
    private fun tarEntries(input: InputStream, each: (String, ByteArray) -> Unit) {
        val header = ByteArray(BLOCK)
        while (input.readNBytes(header, 0, BLOCK) == BLOCK) {
            if (header.all { it.toInt() == 0 }) break
            val name = field(header, 0, 100)
            val prefix = field(header, 345, 155)
            val size = field(header, 124, 12).trim().toLong(8)
            val type = header[156].toInt().toChar()
            val bytes = input.readNBytes(size.toInt())
            input.skipNBytes((BLOCK - size % BLOCK) % BLOCK)
            if (type == '0' || type == '\u0000') each(if (prefix.isEmpty()) name else "$prefix/$name", bytes)
        }
    }

    private fun field(header: ByteArray, offset: Int, length: Int): String =
        String(header, offset, length, Charsets.US_ASCII).substringBefore('\u0000')

    private companion object {
        const val BLOCK = 512
    }
}
