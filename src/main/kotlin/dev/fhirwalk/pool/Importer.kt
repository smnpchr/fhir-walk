package dev.fhirwalk.pool

import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.parser.IParser
import ca.uhn.fhir.util.BundleUtil
import dev.fhirwalk.model.FhirVersion
import org.hl7.fhir.instance.model.api.IBaseResource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.streams.asSequence

private typealias Sink = (resource: IBaseResource, fullUrl: String?, origin: String) -> Unit

/**
 * Import paths into a [ResourcePool]. Three shapes, one extension point: adding a
 * loader (an IG package, a server sync) means another branch here and nothing else.
 * [parse] is that branch for content that is not a file, such as a git blob.
 *
 * Note: Bundles are expanded into their entries and the Bundle itself is not
 * retained, so the census counts each real resource exactly once.
 */
class Importer(private val ctx: FhirContext, private val pool: ResourcePool) {

    data class Report(
        var files: Int = 0,
        var resources: Int = 0,
        /** Files that were a Bundle, as against a single resource or NDJSON. */
        var bundles: Int = 0,
        val problems: MutableList<String> = mutableListOf(),
    ) {
        fun summary(): String = buildString {
            append("$resources resource(s) from $files file(s)")
            if (problems.isNotEmpty()) append("; ${problems.size} problem(s)")
        }
    }

    /** A resource [parse] found, kept so it can be added to a pool more than once. */
    class Parsed(val resource: IBaseResource, val fullUrl: String?, val origin: String) {
        /** As [PooledResource.label] would have it, for a resource that is in no pool. */
        fun label(ctx: FhirContext): String {
            val type = ctx.getResourceType(resource)
            val id = resource.idElement?.idPart?.takeIf { it.isNotBlank() }
            return id?.let { "$type/$it" } ?: fullUrl ?: "$type ($origin)"
        }
    }

    private val json: IParser by lazy { ctx.newJsonParser() }
    private val xml: IParser by lazy { ctx.newXmlParser() }

    fun load(path: Path): Report {
        val report = Report()
        when {
            path.isDirectory() -> loadTree(path, report)
            path.isRegularFile() -> loadFile(path, report)
            else -> report.problems += "not found: $path"
        }
        pool.settleReferences()
        return report
    }

    /** Import #2: walk a directory tree, picking up everything that looks like FHIR. */
    private fun loadTree(root: Path, report: Report) {
        Files.walk(root).use { stream ->
            stream.asSequence()
                .filter { it.isRegularFile() && it.extension.lowercase() in SUPPORTED }
                .sortedBy { it.toString() }
                .forEach { loadFile(it, report) }
        }
    }

    private fun loadFile(file: Path, report: Report) {
        report.files++
        when (file.extension.lowercase()) {
            "ndjson", "jsonl" -> loadNdjson(file, report)
            "xml" -> loadSingle(file, xml, report)
            else -> loadSingle(file, json, report)
        }
    }

    private fun loadSingle(file: Path, parser: IParser, report: Report) {
        val text = runCatching { file.readText() }.getOrElse {
            report.problems += "${file.name}: unreadable (${it.message})"
            return
        }
        parseSingle(text, parser, file.name, report, ::addToPool)
    }

    private fun parseSingle(text: String, parser: IParser, origin: String, report: Report, sink: Sink) {
        if (text.isBlank()) return
        val resource = runCatching { parser.parseResource(text) }.getOrElse { e ->
            report.problems += "$origin: ${versionHint(text, e)}"
            return
        }
        admit(resource, null, origin, report, sink)
    }

    /** Import #3: NDJSON, one resource per line -- the bulk-export shape. */
    private fun loadNdjson(file: Path, report: Report) {
        Files.newBufferedReader(file).use { reader -> parseNdjson(reader.lineSequence(), file.name, report, ::addToPool) }
    }

    private fun parseNdjson(lines: Sequence<String>, origin: String, report: Report, sink: Sink) {
        lines.forEachIndexed { idx, line ->
            if (line.isBlank()) return@forEachIndexed
            val resource = runCatching { json.parseResource(line) }.getOrElse { e ->
                report.problems += "$origin:${idx + 1}: ${e.message?.take(120)}"
                return@forEachIndexed
            }
            admit(resource, null, "$origin:${idx + 1}", report, sink)
        }
    }

    /**
     * Import #4: content from somewhere other than the file system. The resources
     * come back rather than going into the pool, so a caller can keep them and add
     * them again: a git blob has the same content in every revision that holds it.
     *
     * @param name the file's path, which decides its format and is its origin.
     * @return nothing for a name whose extension is not one [load] reads.
     */
    fun parse(name: String, text: String, report: Report): List<Parsed> {
        val found = mutableListOf<Parsed>()
        val sink: Sink = { resource, fullUrl, origin -> found += Parsed(resource, fullUrl, origin) }
        when (name.substringAfterLast('.', "").lowercase()) {
            "ndjson", "jsonl" -> parseNdjson(text.lineSequence(), name, report, sink)
            "xml" -> parseSingle(text, xml, name, report, sink)
            "json" -> parseSingle(text, json, name, report, sink)
        }
        return found
    }

    /** Whether [load] and [parse] read a file with this name. */
    fun reads(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in SUPPORTED

    private fun addToPool(resource: IBaseResource, fullUrl: String?, origin: String) {
        pool.add(resource, fullUrl, origin)
    }

    /** Import #1: a Bundle flattens into its entries, recursively. */
    private fun admit(
        resource: IBaseResource,
        fullUrl: String?,
        origin: String,
        report: Report,
        sink: Sink,
        depth: Int = 0,
    ) {
        if (resource is org.hl7.fhir.instance.model.api.IBaseBundle && depth < MAX_BUNDLE_DEPTH) {
            if (depth == 0) report.bundles++
            val entries = runCatching { BundleUtil.toListOfEntries(ctx, resource) }.getOrElse {
                report.problems += "$origin: unreadable bundle (${it.message?.take(80)})"
                return
            }
            entries.forEachIndexed { idx, entry ->
                val child = entry.resource ?: return@forEachIndexed
                admit(child, entry.fullUrl, "$origin#entry[$idx]", report, sink, depth + 1)
            }
            return
        }
        sink(resource, fullUrl, origin)
        report.resources++
    }

    /**
     * A parse failure is most often a version mismatch, so say so concretely rather
     * than surfacing a stack trace: the fix is a one-word command.
     */
    private fun versionHint(text: String, failure: Throwable): String {
        val base = failure.message?.take(120) ?: failure.javaClass.simpleName
        val alternatives = FhirVersion.entries
            .filter { it.id != currentId }
            .filter { candidate ->
                // Contexts are expensive, so they are built at most once per session
                // even if every file in a large tree fails to parse.
                val parser = probeParsers.getOrPut(candidate) {
                    runCatching { candidate.create().newJsonParser() }.getOrNull() ?: return@filter false
                }
                runCatching { parser.parseResource(text) }.isSuccess
            }
        return if (alternatives.isEmpty()) {
            base
        } else {
            "$base -- parses cleanly as ${alternatives.joinToString("/") { it.id }}; try :fhir ${alternatives.first().id}"
        }
    }

    private val probeParsers = mutableMapOf<FhirVersion, IParser>()

    private val currentId: String =
        FhirVersion.entries.firstOrNull { it.name.equals(ctx.version.version.name, ignoreCase = true) }?.id
            ?: ctx.version.version.name.lowercase()

    private companion object {
        val SUPPORTED = setOf("json", "xml", "ndjson", "jsonl")
        const val MAX_BUNDLE_DEPTH = 5
    }
}
