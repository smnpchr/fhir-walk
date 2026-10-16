package dev.fhirwalk.complete

import dev.fhirwalk.eval.FhirPathTokenizer
import dev.fhirwalk.eval.FhirPathTokenizer.CursorKind
import dev.fhirwalk.model.FhirPathFunctions
import dev.fhirwalk.model.FhirVersion
import dev.fhirwalk.model.ModelNavigator
import dev.fhirwalk.pool.Census
import dev.fhirwalk.pool.ResourcePool
import org.jline.reader.Candidate
import org.jline.reader.Completer
import org.jline.reader.LineReader
import org.jline.reader.ParsedLine
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Completion from two independent sources, which is the feature the tool exists for:
 *
 *  - **schema** -- what is legal here, from HAPI's runtime model ([ModelNavigator])
 *  - **observed** -- what is actually present in the pool, with counts ([Census])
 *
 * Extension URLs can only come from the second. Scoping them to the element they
 * were observed on ("extensions seen on Patient.name", not "every extension known
 * to FHIR") is what makes the list short enough to be useful.
 */
class FhirPathCompleter(
    private val navigator: () -> ModelNavigator,
    private val pool: () -> ResourcePool,
    private val commands: () -> List<CommandInfo>,
) : Completer {

    data class CommandInfo(val name: String, val args: String, val doc: String)

    override fun complete(reader: LineReader, line: ParsedLine, candidates: MutableList<Candidate>) {
        val text = line.line()
        val cursor = line.cursor()
        if (text.trimStart().startsWith(":")) {
            completeCommand(text, candidates)
            return
        }
        completeExpression(text, cursor, candidates)
    }

    // ------------------------------------------------------------------ commands

    private fun completeCommand(text: String, candidates: MutableList<Candidate>) {
        val words = text.trim().split(Regex("\\s+"))
        if (words.size <= 1) {
            commands().forEach { candidates += cand(it.name, it.name, "commands", it.doc) }
            return
        }
        when (words.first()) {
            ":fhir" -> FhirVersion.entries.forEach {
                candidates += cand(it.id, it.id, "fhir versions", "FHIR ${it.id.uppercase()}")
            }
            ":load" -> completePath(words.last(), candidates)
            ":focus" -> pool().all().take(MAX_FOCUS_CANDIDATES).forEach {
                candidates += cand(it.label, it.label, "pool", it.origin)
            }
            ":ext" -> pool().census.extensions.keys.map { it.path }.distinct().take(MAX_ROWS).forEach {
                candidates += cand(it, it, "paths with extensions", null)
            }
        }
    }

    private fun completePath(partial: String, candidates: MutableList<Candidate>) {
        val asPath = runCatching { Paths.get(partial.ifBlank { "." }) }.getOrNull() ?: return
        val dir: Path = if (Files.isDirectory(asPath)) asPath else asPath.parent ?: Paths.get(".")
        if (!Files.isDirectory(dir)) return
        runCatching {
            Files.list(dir).use { stream ->
                stream.limit(MAX_ROWS.toLong()).forEach { entry ->
                    val display = entry.toString() + if (Files.isDirectory(entry)) "/" else ""
                    candidates += Candidate(display, display, "files", null, null, null, !Files.isDirectory(entry))
                }
            }
        }
    }

    // ---------------------------------------------------------------- expressions

    private fun completeExpression(text: String, cursor: Int, candidates: MutableList<Candidate>) {
        val context = FhirPathTokenizer.cursorContext(text, cursor)
        val nav = navigator()
        val census = pool().census

        when (context.kind) {
            CursorKind.ROOT -> resourceTypes(nav, candidates)

            CursorKind.TYPE_ARG -> {
                // After resolve(), the plausible types are the ones actually referenced.
                // Targets are censused on the Reference element itself, so the
                // trailing resolve() step comes off before the lookup.
                val observed = census.referenceTargetsAt(withoutTrailingResolve(context.basePath))
                observed.forEach { candidates += cand(it.value, it.value, "observed", "${it.count} ×") }
                resourceTypes(nav, candidates)
            }

            CursorKind.STRING_ARG -> stringArgument(context, census, candidates)

            CursorKind.MEMBER -> {
                if (context.basePath.isBlank()) {
                    resourceTypes(nav, candidates)
                    return
                }
                members(context.basePath, nav, census, candidates)
                functions(candidates)
            }
        }
    }

    private fun resourceTypes(nav: ModelNavigator, candidates: MutableList<Candidate>) {
        val counts = pool().typeCounts()
        counts.forEach { (type, count) ->
            candidates += cand(type, type, "in pool", "$count resource(s)")
        }
        nav.resourceTypes()
            .filter { it !in counts }
            .forEach { candidates += cand(it, it, "all resource types", null) }
    }

    private fun members(
        basePath: String,
        nav: ModelNavigator,
        census: Census,
        candidates: MutableList<Candidate>,
    ) {
        for (child in nav.childrenAt(basePath)) {
            val observed = census.countAt("$basePath.${child.name}")
            val cardinality = if (child.isCollection) "*" else "1"
            val detail = buildString {
                append(child.typeName)
                append(" [$cardinality]")
                if (observed > 0) append("  $observed ×")
                child.doc?.let { append("  — ").append(it.take(70)) }
            }
            // Elements present in the data are a separate group: in a schema as large
            // as FHIR's, "what is actually populated" is the more useful half.
            val group = if (observed > 0) "elements (in pool)" else "elements"
            candidates += cand(child.name, child.name, group, detail)
        }
    }

    private fun functions(candidates: MutableList<Candidate>) {
        for (fn in FhirPathFunctions.all()) {
            val group = if (fn.name in FhirPathFunctions.commonFirst) "functions" else "functions (more)"
            candidates += Candidate(fn.insertText, fn.signature, group, fn.doc, null, null, false)
        }
    }

    private fun stringArgument(
        context: FhirPathTokenizer.CursorContext,
        census: Census,
        candidates: MutableList<Candidate>,
    ) {
        when (context.enclosingCall) {
            "extension" -> {
                val scoped = census.extensionsAt(context.basePath)
                scoped.forEach {
                    candidates += cand(it.value, it.value, "observed on ${context.basePath}", detail(it))
                }
                if (scoped.isEmpty()) {
                    census.allExtensions().take(MAX_ROWS).forEach {
                        candidates += cand(it.value, it.value, "observed elsewhere in pool", detail(it))
                    }
                }
            }
            "conformsTo" -> pool().types().flatMap { census.profilesFor(it) }
                .forEach { candidates += cand(it.value, it.value, "profiles in pool", "${it.count} ×") }
            else -> {
                // A quoted literal being compared against a Coding: offer the systems
                // observed at the sibling path.
                val systems = census.codeSystemsAt(context.basePath)
                    .ifEmpty { census.codeSystemsAt(context.basePath.substringBeforeLast('.')) }
                systems.take(MAX_ROWS).forEach {
                    candidates += cand(it.value, it.value, "systems in pool", "${it.count} ×")
                }
            }
        }
    }

    private fun withoutTrailingResolve(path: String): String {
        val segments = FhirPathTokenizer.segments(path).filter { it.text.isNotBlank() }
        if (segments.lastOrNull()?.name != "resolve") return path
        return segments.dropLast(1).joinToString(".") { it.text }
    }

    private fun detail(suggestion: Census.Suggestion): String =
        listOfNotNull("${suggestion.count} ×", suggestion.detail).joinToString("  ")

    private fun cand(value: String, display: String, group: String, descr: String?) =
        Candidate(value, display, group, descr, null, null, true)

    private companion object {
        const val MAX_ROWS = 60
        const val MAX_FOCUS_CANDIDATES = 200
    }
}
