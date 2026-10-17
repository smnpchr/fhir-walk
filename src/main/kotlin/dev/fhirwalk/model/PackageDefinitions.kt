package dev.fhirwalk.model

import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.context.support.IValidationSupport
import com.fasterxml.jackson.databind.ObjectMapper
import org.hl7.fhir.instance.model.api.IBaseResource
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.JarURLConnection
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.Deflater
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
 * up front took 5 s and held 200 MB; here the package is scanned once per process,
 * each definition kept deflated, and parsed only when first asked for. That is also
 * why [fetchAllStructureDefinitions] returns nothing: answering it would parse
 * everything, and the engine does not need it to evaluate.
 *
 * Version-independent: the package location comes from [FhirVersion], and parsing
 * goes through the context's own JSON parser.
 *
 * @param packageResource classpath path of the package, e.g.
 *   `/org/hl7/fhir/r5/packages/hl7.fhir.r5.core-5.0.0.tgz`; see [locate].
 */
class PackageDefinitions(
    private val ctx: FhirContext,
    private val packageResource: String,
) : IValidationSupport {

    /** Parsed definitions belong to this context, so only the packed bytes are shared. */
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
        val packed = packed(packageResource)
        val key = candidates(url).firstOrNull { it in packed } ?: return null
        parsed[key]?.let { return it }
        val json = InflaterInputStream(packed.getValue(key).inputStream()).use { String(it.readBytes(), Charsets.UTF_8) }
        return parsed.computeIfAbsent(key) { ctx.newJsonParser().parseResource(json) }
    }

    companion object {
        private const val BASE = "http://hl7.org/fhir/StructureDefinition/"
        private const val BLOCK = 512

        /**
         * Per package, for the whole process: the bytes do not depend on the context,
         * so switching back to a version must not scan its package again. A failed
         * load is kept too, or every lookup -- several per resource in a pool-wide
         * evaluation -- would rescan the whole package before failing again.
         */
        private val shared = ConcurrentHashMap<String, Lazy<Result<Map<String, ByteArray>>>>()

        private fun packed(resource: String): Map<String, ByteArray> =
            shared.computeIfAbsent(resource) { lazy { runCatching { load(it) } } }.value.getOrThrow()

        /**
         * The spellings HAPI's own reader accepts for one definition, most specific
         * first: a canonical URL (any `|version` dropped), `StructureDefinition/X`, a
         * bare type name, and -- for `ofType(String)` and the like -- the primitive
         * whose name is the lowercase of a capitalised one. Without these, R5 failed
         * expressions that R4 merely answers with nothing.
         */
        private fun candidates(url: String): List<String> {
            val bare = url.substringBefore('|')
            val canonical = when {
                bare.startsWith("http://") || bare.startsWith("https://") -> bare
                bare.startsWith("StructureDefinition/") -> BASE + bare.removePrefix("StructureDefinition/")
                else -> BASE + bare
            }
            val name = canonical.substringAfterLast('/')
            val primitive = name.replaceFirstChar { it.lowercaseChar() }
            return if (primitive == name) listOf(canonical) else listOf(canonical, canonical.dropLast(name.length) + primitive)
        }

        /**
         * Finds a package on the classpath by name rather than by exact file name, so
         * a HAPI upgrade that ships `hl7.fhir.r5.core-5.0.1.tgz` keeps working. The
         * highest version wins when there are several.
         *
         * @param directory e.g. `org/hl7/fhir/r5/packages`
         * @param namePrefix e.g. `hl7.fhir.r5.core-`
         * @return the resource path, or null when no such package is on the classpath.
         */
        fun locate(directory: String, namePrefix: String): String? {
            val dir = directory.trim('/')
            val url = PackageDefinitions::class.java.classLoader.getResource(dir) ?: return null
            val names = when (url.protocol) {
                // Its own handle: the cached one is shared with the class loader, and
                // closing that would break every later read from the same JAR.
                "jar" -> (url.openConnection() as JarURLConnection).apply { useCaches = false }.jarFile.use { jar ->
                    jar.entries().asSequence().map { it.name }
                        .filter { it.startsWith("$dir/") && '/' !in it.removePrefix("$dir/") }
                        .map { it.removePrefix("$dir/") }
                        .toList()
                }
                "file" -> File(url.toURI()).list()?.toList().orEmpty()
                else -> emptyList()
            }
            return names.filter { it.startsWith(namePrefix) && it.endsWith(".tgz") }
                .maxWithOrNull(compareBy<String> { versionKey(it.removePrefix(namePrefix)) }.thenBy { it })
                ?.let { "/$dir/$it" }
        }

        /** `5.0.10` after `5.0.9`: numeric parts compared as numbers. */
        private fun versionKey(version: String): String =
            Regex("\\d+").replace(version.removeSuffix(".tgz")) { it.value.padStart(8, '0') }

        /**
         * One pass over the package. Its `.index.json` maps each file to the canonical
         * URL inside it, so no definition has to be parsed to be found later.
         */
        private fun load(resource: String): Map<String, ByteArray> {
            val stream = PackageDefinitions::class.java.getResourceAsStream(resource)
                ?: error(
                    "FHIR definitions not on the classpath: $resource — " +
                        "add the matching hapi-fhir-validation-resources JAR to build.gradle.kts",
                )
            var index: ByteArray? = null
            val byFile = HashMap<String, ByteArray>()
            GZIPInputStream(stream).use { gzip ->
                tarEntries(gzip, wanted = { it == "package/.index.json" || isDefinition(it) }) { name, bytes ->
                    if (name == "package/.index.json") index = bytes else byFile[name.removePrefix("package/")] = deflate(bytes)
                }
            }
            val files = index?.let { ObjectMapper().readTree(it).path("files") }
                ?: error("$resource has no package/.index.json — not a FHIR NPM package?")
            return files
                .filter { it.path("resourceType").asText() == "StructureDefinition" }
                .mapNotNull { file ->
                    val url = file.path("url").asText().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    byFile[file.path("filename").asText()]?.let { url to it }
                }
                .toMap()
        }

        private fun isDefinition(name: String) =
            name.startsWith("package/StructureDefinition-") && name.endsWith(".json")

        /** Kept only in memory, so speed matters more than the last few percent. */
        private fun deflate(bytes: ByteArray): ByteArray {
            val out = ByteArrayOutputStream(bytes.size / 4)
            DeflaterOutputStream(out, Deflater(Deflater.BEST_SPEED)).use { it.write(bytes) }
            return out.toByteArray()
        }

        /**
         * The regular files of a tar stream that [wanted] asks for; everything else
         * is skipped without being read into memory. Handles ustar names (with their
         * prefix field), and the two long-name extensions a packer may emit: PAX
         * (`x`, a `path=` record) and GNU (`L`). Either applies to the entry after it;
         * ignoring them would file that entry under a truncated name and lose it
         * without a word.
         */
        private fun tarEntries(input: InputStream, wanted: (String) -> Boolean, each: (String, ByteArray) -> Unit) {
            val header = ByteArray(BLOCK)
            var longName: String? = null
            while (input.readNBytes(header, 0, BLOCK) == BLOCK) {
                if (header.all { it.toInt() == 0 }) break
                // Blank is legal for entries that carry no data.
                val size = field(header, 124, 12).trim().ifEmpty { "0" }.toLong(8)
                val padding = (BLOCK - size % BLOCK) % BLOCK
                when (header[156].toInt().toChar()) {
                    'L' -> longName = String(input.readNBytes(size.toInt()), Charsets.UTF_8).trimEnd('\u0000')
                    'x' -> paxPath(input.readNBytes(size.toInt()))?.let { longName = it }
                    '0', '\u0000' -> {
                        val prefix = field(header, 345, 155)
                        val name = longName ?: field(header, 0, 100).let { if (prefix.isEmpty()) it else "$prefix/$it" }
                        longName = null
                        if (wanted(name)) each(name, input.readNBytes(size.toInt())) else input.skipNBytes(size)
                    }
                    else -> {
                        longName = null
                        input.skipNBytes(size)
                    }
                }
                input.skipNBytes(padding)
            }
        }

        /** The `path` of a PAX header: records of the form `<length> <key>=<value>\n`. */
        private fun paxPath(body: ByteArray): String? =
            String(body, Charsets.UTF_8).lineSequence()
                .map { it.substringAfter(' ', "") }
                .firstOrNull { it.startsWith("path=") }
                ?.removePrefix("path=")

        private fun field(header: ByteArray, offset: Int, length: Int): String =
            String(header, offset, length, Charsets.US_ASCII).substringBefore('\u0000')
    }
}
