package dev.fhirwalk.complete

import dev.fhirwalk.eval.FhirPathTokenizer
import dev.fhirwalk.eval.Focus
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
    private val focus: () -> Focus? = { null },
    private val recorded: () -> List<String> = { emptyList() },
    private val named: () -> Map<String, String> = { emptyMap() },
) : Completer {

    data class CommandInfo(val name: String, val args: String, val doc: String)

    override fun complete(reader: LineReader, line: ParsedLine, candidates: MutableList<Candidate>) {
        val text = line.line()
        val cursor = line.cursor()
        // `:all`, `:why` and `:focus` take an expression, so their argument completes
        // exactly as a bare expression does; every other command argument is its own thing.
        val slice = PromptLine.expressionSlice(text, cursor)
        if (slice == null) {
            completeCommand(text, candidates)
            return
        }
        // `:all` runs across the pool whatever is in focus, so it completes unrebased.
        val relativeTo = focus().takeIf { slice.command != ":all" }
        val expression = mutableListOf<Candidate>()
        completeExpression(slice.text, slice.cursor, relativeTo, expression)
        // Labels first in the menu, but sized to the room the expression left.
        if (slice.command == ":focus") focusLabels(slice, relativeTo, expression, candidates)
        candidates += expression
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
        // The arguments so far, the last being the one under the cursor (possibly "").
        val arguments = trimmed.split(Regex("[\\s,]+")).drop(1)
        when (command) {
            ":fhir" -> FhirVersion.entries.forEach {
                candidates += commandCand(it.id, it.id, "fhir versions", "FHIR ${it.id.uppercase()}")
            }
            ":load" -> completePath(lastToken, candidates)
            ":ext" -> pool().census.extensions.keys.map { it.path }.distinct().take(MAX_ROWS).forEach {
                candidates += commandCand(it, it, "paths with extensions", null)
            }
            ":view" -> {
                // Everything already on the line, so neither a name nor a number repeats.
                val taken = arguments.dropLast(1).map { it.removePrefix("#") }.toSet()
                val shown = names(lastToken, taken, MAX_ROWS, candidates)
                recordedColumns(lastToken, taken, minOf(MAX_ROWS, MAX_MENU - shown), candidates)
            }
            // The name first, to show or rebind one; then the number it is to hold.
            ":name" -> when (arguments.size) {
                1 -> names(lastToken, emptySet(), MAX_ROWS, candidates)
                2 -> recordedColumns(lastToken, emptySet(), MAX_ROWS, candidates)
            }
            ":unname" -> if (arguments.size == 1) names(lastToken, emptySet(), MAX_ROWS, candidates)
        }
    }

    /**
     * Recorded expressions by number, newest first as `:history` lists them, and
     * narrowed by the digits typed before the cap, or a long log would leave its
     * oldest entries uncompletable. A bare number completes as one, `#` or nothing
     * as `#n`; a number in [taken] is not offered. The number typed exactly comes
     * first, or `1` in a long log would offer #150 to #100 and never #1.
     */
    private fun recordedColumns(partial: String, taken: Set<String>, limit: Int, candidates: MutableList<Candidate>) {
        val hash = if (partial.startsWith("#") || partial.isEmpty()) "#" else ""
        val digits = partial.removePrefix("#")
        recorded().withIndex().reversed()
            .filter { (idx, _) -> (idx + 1).toString().let { it.startsWith(digits) && it !in taken } }
            .sortedByDescending { (idx, _) -> (idx + 1).toString() == digits }
            .take(limit)
            // JLine orders by value unless told otherwise, which puts #10 before #2.
            .forEachIndexed { rank, (idx, expression) ->
                val number = "$hash${idx + 1}"
                candidates += Candidate(number, "#${idx + 1}  $expression", "recorded expressions", null, null, null, true, rank)
            }
    }

    /**
     * Names from `:name`, narrowed by what is typed before the cap.
     *
     * @return how many were offered, so the numbers offered alongside leave room.
     */
    private fun names(partial: String, taken: Set<String>, limit: Int, candidates: MutableList<Candidate>): Int {
        val matching = named().entries
            .filter { (name, _) -> name.startsWith(partial, ignoreCase = true) && name !in taken }
            .take(limit)
        matching.forEach { (name, expression) -> candidates += commandCand(name, "$name  $expression", "named expressions", null) }
        return matching.size
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

    /**
     * Pool labels for `:focus`, which takes a label as well as an expression.
     *
     * The parser splits the argument as FHIRPath, so `Observation/ob` has the word
     * `ob`; each label is inserted minus what precedes that word, or accepting it
     * would repeat `Observation/`. Narrowed by what is typed before the cap, or a
     * large pool would leave everything past the first MAX_ROWS uncompletable.
     *
     * Only the word is replaced, so the text before it must already match the label
     * exactly: `patient/p` cannot become `Patient/p1`, and offering it would leave a
     * line that is no label at all.
     *
     * @param others the expression candidates offered alongside; together they must
     *   stay under JLine's threshold.
     */
    private fun focusLabels(
        slice: PromptLine.Slice,
        focus: Focus?,
        others: List<Candidate>,
        candidates: MutableList<Candidate>,
    ) {
        val typed = slice.text.take(slice.cursor)
        // Drilling into an element, the element's members are what you want on a
        // bare TAB; sixty labels on top would crowd them out.
        if (typed.isBlank() && focus != null && !focus.isResource) return
        val context = FhirPathTokenizer.cursorContext(slice.text, slice.cursor)
        val beforeWord = typed.take(context.replaceFrom)
        // JLine counts what matches the word, not everything handed to it.
        val room = MAX_MENU - others.count { it.value().startsWith(context.partial, ignoreCase = true) }
        pool().all()
            .filter {
                it.label.startsWith(beforeWord) &&
                    it.label.drop(beforeWord.length).startsWith(context.partial, ignoreCase = true)
            }
            .take(minOf(MAX_ROWS, room))
            .forEach { candidates += commandCand(it.label.drop(beforeWord.length), it.label, "pool", it.origin) }
    }

    // ---------------------------------------------------------------- expressions

    /**
     * @param focus what the expression runs against. Paths typed against an element
     *   are relative to it, so they are rebased onto [Focus.path] before the
     *   navigator or census sees them.
     */
    private fun completeExpression(slice: String, cursor: Int, focus: Focus?, candidates: MutableList<Candidate>) {
        val context = FhirPathTokenizer.cursorContext(slice, cursor)
        val nav = navigator()
        val census = pool().census
        fun rebase(path: String) = focus?.absolute(path) ?: path

        when (context.kind) {
            // An element has no resource types worth offering: what is legal at the
            // start of the line is whatever is legal on the element.
            CursorKind.ROOT -> if (focus != null && !focus.isResource) {
                members(focus.path, nav, census, candidates)
                functions(candidates, context.partial)
            } else {
                // A resource in focus also takes a relative path, `code` for
                // `Observation.code`, which is how `:focus` drills into it.
                focus?.let {
                    members(it.path, nav, census, candidates)
                    // Only once typed, as for resource types: the bare TAB is crowded.
                    if (context.partial.isNotEmpty()) functions(candidates, context.partial)
                }
                // With nothing typed yet, only the types the pool actually holds are
                // useful -- evaluating Practitioner.name against a pool with no
                // Practitioner tells you nothing. One keystroke opens up the full schema.
                resourceTypes(nav, candidates, all = context.partial.isNotEmpty())
            }

            CursorKind.TYPE_ARG -> {
                // After resolve(), the plausible types are the ones actually referenced.
                // Targets are censused on the Reference element itself, so the
                // trailing resolve() step comes off before the lookup.
                val basePath = rebase(context.basePath)
                val observed = census.referenceTargetsAt(nav.censusPath(withoutTrailingResolve(basePath)))
                observed.forEach { candidates += cand(it.value, it.value, "observed", "${it.count} ×") }
                // When the corpus has an answer, trust it rather than dumping 146
                // resource types the user would have to page through.
                resourceTypes(nav, candidates, all = observed.isEmpty() && context.partial.isNotEmpty())
            }

            // What follows the cursor decides whether the candidate has to close the
            // string literal itself.
            CursorKind.STRING_ARG -> stringArgument(
                context.copy(
                    basePath = rebase(context.basePath),
                    comparedPath = context.comparedPath?.let(::rebase),
                ),
                nav,
                census,
                candidates,
                slice.substring(cursor.coerceAtMost(slice.length)),
            )

            CursorKind.MEMBER -> {
                if (context.basePath.isBlank()) {
                    resourceTypes(nav, candidates, all = context.partial.isNotEmpty())
                    return
                }
                members(rebase(context.basePath), nav, census, candidates)
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
            // Only a literal inside a call has a paren to close: `Patient.gender = '`
            // completed to `'female')` would not parse.
            tail.isBlank() && context.enclosingCall != null -> "')"
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
                // The right of a comparison: the coded values observed on the left
                // operand. Those are the answer whenever there are any; code systems
                // are only the answer for a `system` operand, which records none.
                val values = context.comparedPath?.let { census.valuesAt(nav.censusPath(it)) }.orEmpty()
                if (values.isNotEmpty()) {
                    values.take(MAX_ROWS).forEach {
                        candidates += stringCand(it.value, "observed values", "${it.count} ×")
                    }
                    return
                }
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
        /** Past 100 matching candidates JLine asks "see all N possibilities?". */
        const val MAX_MENU = 99
        const val MAX_EXTENSION_STEPS = 24
    }
}
