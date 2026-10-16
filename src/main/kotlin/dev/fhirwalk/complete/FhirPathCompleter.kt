package dev.fhirwalk.complete

import dev.fhirwalk.eval.FhirPathTokenizer
import dev.fhirwalk.eval.FhirPathTokenizer.CursorKind
import dev.fhirwalk.model.FhirPathFunctions
import dev.fhirwalk.model.FhirVersion
import dev.fhirwalk.eval.PromptLine
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
        // `:all` and `:why` take an expression, so their argument completes exactly as
        // a bare expression does; every other command argument is its own thing.
        val slice = PromptLine.expressionSlice(text, cursor)
        if (slice == null) {
            completeCommand(text, candidates)
            return
        }
        completeExpression(slice.text, slice.cursor, candidates)
    }

    // ------------------------------------------------------------------ commands

    private fun completeCommand(text: String, candidates: MutableList<Candidate>) {
        val trimmed = text.trimStart()
        val command = trimmed.takeWhile { !it.isWhitespace() }
        // Trailing whitespace is the signal that the name is finished and an argument
        // is being typed, so it must not be trimmed away: ":fhir" offers command
        // names, ":fhir " offers versions.
        if (command.length == trimmed.length) {
            commands().forEach { candidates += commandCand(it.name, it.name, "commands", it.doc) }
            return
        }
        val lastToken = trimmed.substringAfterLast(' ', "")
        when (command) {
            ":fhir" -> FhirVersion.entries.forEach {
                candidates += commandCand(it.id, it.id, "fhir versions", "FHIR ${it.id.uppercase()}")
            }
            ":load" -> completePath(lastToken, candidates)
            // Narrowed by what is typed before the cap, or a large pool would leave
            // everything past the first MAX_ROWS uncompletable.
            ":focus" -> pool().all().filter { it.label.startsWith(lastToken, ignoreCase = true) }
                .take(MAX_ROWS)
                .forEach { candidates += commandCand(it.label, it.label, "pool", it.origin) }
            ":ext" -> pool().census.extensions.keys.map { it.path }.distinct().take(MAX_ROWS).forEach {
                candidates += commandCand(it, it, "paths with extensions", null)
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

    private fun completeExpression(slice: String, cursor: Int, candidates: MutableList<Candidate>) {
        val context = FhirPathTokenizer.cursorContext(slice, cursor)
        val nav = navigator()
        val census = pool().census

        when (context.kind) {
            // With nothing typed yet, only the types the pool actually holds are
            // useful -- evaluating Practitioner.name against a pool with no
            // Practitioner tells you nothing. One keystroke opens up the full schema.
            CursorKind.ROOT -> resourceTypes(nav, candidates, all = context.partial.isNotEmpty())

            CursorKind.TYPE_ARG -> {
                // After resolve(), the plausible types are the ones actually referenced.
                // Targets are censused on the Reference element itself, so the
                // trailing resolve() step comes off before the lookup.
                val observed = census.referenceTargetsAt(nav.censusPath(withoutTrailingResolve(context.basePath)))
                observed.forEach { candidates += cand(it.value, it.value, "observed", "${it.count} ×") }
                // When the corpus has an answer, trust it rather than dumping 146
                // resource types the user would have to page through.
                resourceTypes(nav, candidates, all = observed.isEmpty() && context.partial.isNotEmpty())
            }

            // What follows the cursor decides whether the candidate has to close the
            // string literal itself.
            CursorKind.STRING_ARG ->
                stringArgument(context, nav, census, candidates, slice.substring(cursor.coerceAtMost(slice.length)))

            CursorKind.MEMBER -> {
                if (context.basePath.isBlank()) {
                    resourceTypes(nav, candidates, all = context.partial.isNotEmpty())
                    return
                }
                members(context.basePath, nav, census, candidates)
                functions(candidates, context.partial)
            }
        }
    }

    /**
     * @param all whether to include every resource type in the schema on top of the
     *   ones present in the pool. JLine asks for confirmation past 100 candidates, so
     *   the broad list is withheld until the user has narrowed it with a prefix.
     */
    private fun resourceTypes(nav: ModelNavigator, candidates: MutableList<Candidate>, all: Boolean) {
        val counts = pool().typeCounts()
        counts.forEach { (type, count) ->
            candidates += cand(type, type, "in pool", "$count resource(s)")
        }
        if (!all) return
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
        // Census keys carry no where()/first()/[n] and restart at the resource type
        // after resolve(), so counts and extensions are looked up by that spelling.
        val censusPath = nav.censusPath(basePath)
        for (child in nav.childrenAt(basePath)) {
            val observed = census.countAt("$censusPath.${child.name}")
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
        extensionSteps(censusPath, census, candidates)
        targetSteps(basePath, nav, census, candidates)
    }

    /**
     * Whole `ofType(X)` steps after a `resolve()` whose references point at more than
     * one type. The navigator cannot pick one, so no elements are offered; one TAB on
     * a step narrows the collection to a single type and the next TAB has elements
     * again. Ranked by how many references point at each type.
     */
    private fun targetSteps(
        basePath: String,
        nav: ModelNavigator,
        census: Census,
        candidates: MutableList<Candidate>,
    ) {
        val referencePath = withoutTrailingResolve(basePath)
        if (referencePath == basePath) return
        val targets = census.referenceTargetsAt(nav.censusPath(referencePath))
        if (targets.size < 2) return
        for (target in targets) {
            val step = "ofType(${target.value})"
            candidates += cand(step, step, "ofType() — referenced here", "${target.count} ×")
        }
    }

    /**
     * Whole `extension('url')` steps for the URLs observed at this path -- call,
     * quotes and closing paren included.
     *
     * Completing to a bare `extension('` left the line holding an unterminated
     * string, so pressing Enter on it failed to parse. Offering the finished step
     * means one TAB gets you a valid expression you can keep typing after.
     */
    private fun extensionSteps(
        basePath: String,
        census: Census,
        candidates: MutableList<Candidate>,
    ) {
        for (ext in census.extensionsAt(basePath).take(MAX_EXTENSION_STEPS)) {
            val step = "extension('${ext.value}')"
            candidates += cand(step, step, "extension() — observed here", detail(ext))
        }
    }

    /**
     * A bare TAB after a dot shows elements plus only the handful of functions you
     * reach for while composing a path; the full ~90-entry table appears once the
     * user starts typing a name. Otherwise every TAB would exceed JLine's
     * 100-candidate threshold and prompt "see all possibilities?" instead of helping.
     */
    private fun functions(candidates: MutableList<Candidate>, partial: String) {
        val offered = if (partial.isBlank()) {
            FhirPathFunctions.all().filter { it.name in FhirPathFunctions.commonFirst }
        } else {
            FhirPathFunctions.all()
        }
        for (fn in offered) {
            val group = if (fn.name in FhirPathFunctions.commonFirst) "functions" else "functions (more)"
            candidates += Candidate(fn.insertText, fn.signature, group, fn.doc, null, null, false)
        }
    }

    private fun stringArgument(
        context: FhirPathTokenizer.CursorContext,
        nav: ModelNavigator,
        census: Census,
        candidates: MutableList<Candidate>,
        tail: String,
    ) {
        // Accepting a candidate must leave a valid expression. Inserting only the URL
        // left the string unterminated, so Enter submitted something unparseable --
        // unless the user had already typed the closer.
        val closer = when {
            tail.startsWith("'") -> ""
            tail.isBlank() -> "')"
            else -> "'"
        }
        fun stringCand(value: String, group: String, descr: String?) =
            cand(value + closer, value, group, descr)

        val censusPath = nav.censusPath(context.basePath)
        when (context.enclosingCall) {
            "extension" -> {
                val scoped = census.extensionsAt(censusPath)
                scoped.forEach {
                    candidates += stringCand(it.value, "observed on ${context.basePath}", detail(it))
                }
                if (scoped.isEmpty()) {
                    census.allExtensions().take(MAX_ROWS).forEach {
                        candidates += stringCand(it.value, "observed elsewhere in pool", detail(it))
                    }
                }
            }
            "conformsTo" -> pool().types().flatMap { census.profilesFor(it) }
                .forEach { candidates += stringCand(it.value, "profiles in pool", "${it.count} ×") }
            else -> {
                // A quoted literal being compared against a Coding: offer the systems
                // observed at the sibling path.
                val systems = census.codeSystemsAt(censusPath)
                    .ifEmpty { census.codeSystemsAt(censusPath.substringBeforeLast('.')) }
                systems.take(MAX_ROWS).forEach {
                    candidates += stringCand(it.value, "systems in pool", "${it.count} ×")
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

    /**
     * Expression candidates are never "complete": JLine appends a space after a
     * complete candidate, which is wrong mid-path -- you almost always want to type
     * `.` or `'` next, not a space.
     */
    private fun cand(value: String, display: String, group: String, descr: String?) =
        Candidate(value, display, group, descr, null, null, false)

    /** Commands and their arguments are words, so a trailing space is right here. */
    private fun commandCand(value: String, display: String, group: String, descr: String?) =
        Candidate(value, display, group, descr, null, null, true)

    private companion object {
        const val MAX_ROWS = 60
        const val MAX_EXTENSION_STEPS = 24
    }
}
