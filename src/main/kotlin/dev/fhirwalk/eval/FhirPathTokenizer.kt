package dev.fhirwalk.eval

/**
 * A deliberately small FHIRPath lexer.
 *
 * HAPI's version-independent `IFhirPath.parse()` returns an opaque handle, so the
 * AST is not available to us. We do not need it: splitting an expression at
 * navigation boundaries is enough for both features that care about structure --
 * prefix diagnosis ([PrefixWalker]) and cursor-aware completion. Working on the raw
 * string also means a half-typed, not-yet-parseable expression still completes.
 */
object FhirPathTokenizer {

    /** One `.`-separated step, e.g. `name`, `where(use = 'official')`, `extension(url)`. */
    data class Segment(val text: String, val start: Int, val end: Int) {
        /** Identifier part, without any call parentheses. */
        val name: String get() = text.substringBefore('(').trim()

        val isCall: Boolean get() = text.contains('(')

        /** Text between the outermost parentheses, or null when this is not a call. */
        val argText: String?
            get() {
                val open = text.indexOf('(')
                if (open < 0) return null
                val close = text.lastIndexOf(')')
                return if (close > open) text.substring(open + 1, close) else text.substring(open + 1)
            }

        /** First argument with surrounding quotes removed, for `extension('url')`. */
        val firstArgLiteral: String?
            get() = argText?.let { args ->
                val first = splitTopLevel(args, ',').firstOrNull()?.trim() ?: return null
                first.removeSurrounding("'").removeSurrounding("\"").takeIf { it != first || !first.contains(' ') }
            }
    }

    /**
     * Splits at `.` boundaries that are not inside parentheses, brackets or quotes.
     * Leading `%vars`, `$this` and parenthesised roots come back as the first segment.
     */
    fun segments(expr: String): List<Segment> {
        val out = mutableListOf<Segment>()
        var depth = 0
        var quote: Char? = null
        var start = 0
        var i = 0
        while (i < expr.length) {
            val c = expr[i]
            when {
                quote != null -> if (c == '\\') i++ else if (c == quote) quote = null
                c == '\'' || c == '"' || c == '`' -> quote = c
                c == '(' || c == '[' -> depth++
                c == ')' || c == ']' -> depth--
                c == '.' && depth == 0 -> {
                    out += Segment(expr.substring(start, i), start, i)
                    start = i + 1
                }
            }
            i++
        }
        if (start <= expr.length) out += Segment(expr.substring(start), start, expr.length)
        return out.filter { it.text.isNotBlank() || it.start < expr.length }
    }

    /**
     * Cumulative prefixes of the expression, shortest first: `Patient`,
     * `Patient.contact`, `Patient.contact.name`. The whole expression is the last
     * entry. This is what makes "where did it go empty" answerable.
     */
    fun prefixes(expr: String): List<String> {
        val segs = segments(expr).filter { it.text.isNotBlank() }
        return segs.indices.map { idx -> segs.take(idx + 1).joinToString(".") { it.text } }
    }

    /** Splits on a separator at paren/quote depth zero. */
    fun splitTopLevel(text: String, separator: Char): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        var quote: Char? = null
        var start = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quote != null -> if (c == '\\') i++ else if (c == quote) quote = null
                c == '\'' || c == '"' || c == '`' -> quote = c
                c == '(' || c == '[' -> depth++
                c == ')' || c == ']' -> depth--
                c == separator && depth == 0 -> {
                    out += text.substring(start, i)
                    start = i + 1
                }
            }
            i++
        }
        out += text.substring(start)
        return out
    }

    // ---------------------------------------------------------------- completion

    enum class CursorKind {
        /** Completing a resource type at the start of the expression. */
        ROOT,

        /** Completing an element or function name against [CursorContext.basePath]. */
        MEMBER,

        /** Completing inside a quoted string argument, e.g. `extension('…`. */
        STRING_ARG,

        /** Completing a type name argument, e.g. `ofType(…`. */
        TYPE_ARG,
    }

    /**
     * @param basePath the navigable path the completion is relative to, already
     *   absolute from the resource root ("" when at the expression root).
     * @param partial the text typed so far for the token under the cursor.
     * @param replaceFrom offset in the original expression where [partial] begins.
     * @param enclosingCall the function whose argument list we are inside, if any.
     */
    data class CursorContext(
        val kind: CursorKind,
        val basePath: String,
        val partial: String,
        val replaceFrom: Int,
        val enclosingCall: String? = null,
    )

    private val IDENT = Regex("[A-Za-z0-9_]")

    fun cursorContext(expr: String, cursor: Int): CursorContext {
        val head = expr.substring(0, cursor.coerceIn(0, expr.length))

        // Walk once, remembering the innermost unclosed call and quote state.
        var quote: Char? = null
        val openCalls = ArrayDeque<Int>() // offsets of unclosed '(' or '['
        var quoteStart = -1
        var i = 0
        while (i < head.length) {
            val c = head[i]
            when {
                quote != null -> if (c == '\\') i++ else if (c == quote) {
                    quote = null
                    quoteStart = -1
                }
                c == '\'' || c == '"' || c == '`' -> {
                    quote = c
                    quoteStart = i
                }
                c == '(' || c == '[' -> openCalls.addLast(i)
                c == ')' || c == ']' -> openCalls.removeLastOrNull()
            }
            i++
        }

        val innermostOpen = openCalls.lastOrNull()
        val callName = innermostOpen?.let { identifierEndingAt(head, it) }
        val callInputPath = innermostOpen?.let { open ->
            val nameStart = open - (callName?.length ?: 0)
            head.substring(0, nameStart).trimEnd().removeSuffix(".").trimEnd()
        }

        // Inside a string literal: the only sensible completions are data-derived
        // (extension URLs, systems, profiles), which is exactly the corpus axis.
        if (quote != null && quoteStart >= 0) {
            return CursorContext(
                kind = CursorKind.STRING_ARG,
                basePath = callInputPath ?: "",
                partial = head.substring(quoteStart + 1),
                replaceFrom = quoteStart + 1,
                enclosingCall = callName,
            )
        }

        // Identifier under the cursor.
        var p = head.length
        while (p > 0 && IDENT.matches(head[p - 1].toString())) p--
        val partial = head.substring(p)

        // Everything between the enclosing '(' (or start) and the identifier.
        val localStart = (innermostOpen?.plus(1)) ?: 0
        val local = head.substring(localStart, p)
        // Only the last argument, and only after the last binary operator.
        val lastArg = splitTopLevel(local, ',').last()
        val relative = lastArg.substringAfterLast(' ').ifBlank { lastArg }.trimStart()
        val relativeBase = relative.trimEnd().removeSuffix(".")

        if (callName != null && callName in TYPE_ARG_FUNCTIONS && relativeBase.isBlank()) {
            return CursorContext(CursorKind.TYPE_ARG, callInputPath ?: "", partial, p, callName)
        }

        val base = when {
            innermostOpen == null -> relativeBase
            relativeBase.isBlank() -> callInputPath ?: ""
            else -> listOf(callInputPath ?: "", relativeBase).filter { it.isNotBlank() }.joinToString(".")
        }

        val kind = if (base.isBlank() && !local.trimEnd().endsWith(".")) CursorKind.ROOT else CursorKind.MEMBER
        return CursorContext(kind, base, partial, p, callName)
    }

    private val TYPE_ARG_FUNCTIONS = setOf("ofType", "as", "is")

    /** The identifier immediately preceding [offset], e.g. the name of a call. */
    private fun identifierEndingAt(text: String, offset: Int): String {
        var p = offset
        while (p > 0 && IDENT.matches(text[p - 1].toString())) p--
        return text.substring(p, offset)
    }
}
