package dev.fhirwalk.pool

import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.parser.IParser
import ca.uhn.fhir.util.BundleUtil
import dev.fhirwalk.model.FhirVersion
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.streams.asSequence

/**
 * Import paths into a [ResourcePool]. Three shapes, one extension point: adding a
 * loader (an IG package, a server sync) means another branch here and nothing else.
 *
 * Note: Bundles are expanded into their entries and the Bundle itself is not
 * retained, so the census counts each real resource exactly once.
 */
class Importer(private val ctx: FhirContext, private val pool: ResourcePool) {

    data class Report(
        var files: Int = 0,
        var resources: Int = 0,
        val problems: MutableList<String> = mutableListOf(),
    ) {
        fun summary(): String = buildString {
            append("$resources resource(s) from $files file(s)")
            if (problems.isNotEmpty()) append("; ${problems.size} problem(s)")
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
        if (text.isBlank()) return
        val resource = runCatching { parser.parseResource(text) }.getOrElse { e ->
            report.problems += "${file.name}: ${versionHint(text, e)}"
            return
        }
        admit(resource, null, file.name, report)
    }

    /** Import #3: NDJSON, one resource per line -- the bulk-export shape. */
    private fun loadNdjson(file: Path, report: Report) {
        Files.newBufferedReader(file).use { reader ->
            reader.lineSequence().forEachIndexed { idx, line ->
                if (line.isBlank()) return@forEachIndexed
                val resource = runCatching { json.parseResource(line) }.getOrElse { e ->
                    report.problems += "${file.name}:${idx + 1}: ${e.message?.take(120)}"
                    return@forEachIndexed
                }
                admit(resource, null, "${file.name}:${idx + 1}", report)
            }
        }
    }

    /** Import #1: a Bundle flattens into its entries, recursively. */
    private fun admit(
        resource: org.hl7.fhir.instance.model.api.IBaseResource,
        fullUrl: String?,
        origin: String,
        report: Report,
        depth: Int = 0,
    ) {
        if (resource is org.hl7.fhir.instance.model.api.IBaseBundle && depth < MAX_BUNDLE_DEPTH) {
            val entries = runCatching { BundleUtil.toListOfEntries(ctx, resource) }.getOrElse {
                report.problems += "$origin: unreadable bundle (${it.message?.take(80)})"
                return
            }
            entries.forEachIndexed { idx, entry ->
                val child = entry.resource ?: return@forEachIndexed
                admit(child, entry.fullUrl, "$origin#entry[$idx]", report, depth + 1)
            }
            return
        }
        pool.add(resource, fullUrl, origin)
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
