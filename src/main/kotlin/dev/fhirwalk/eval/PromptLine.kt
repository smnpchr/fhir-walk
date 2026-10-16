package dev.fhirwalk.eval

/**
 * Where, in a line typed at the prompt, the FHIRPath expression begins.
 *
 * A bare line is all expression. `:all` and `:why` take an expression as their
 * argument, so everything after the command name completes exactly as a bare
 * expression would -- the alternative is completion that works until you prefix the
 * line with a command, which is worse than no completion at all.
 *
 * Lives below both the parser and the completer, which must agree on the answer.
 */
object PromptLine {

    /** Commands whose argument is a FHIRPath expression. */
    val EXPRESSION_COMMANDS = setOf(":all", ":why")

    /**
     * @param text the expression alone.
     * @param cursor the cursor position within [text].
     * @param offset where [text] starts in the original line.
     */
    data class Slice(val text: String, val cursor: Int, val offset: Int)

    /**
     * @return the expression under the cursor, or null when the cursor sits in a
     *   command name or in an argument that is not an expression (a path, a version).
     */
    fun expressionSlice(line: String, cursor: Int): Slice? {
        val start = line.indexOfFirst { !it.isWhitespace() }
        if (start < 0 || line[start] != ':') return Slice(line, cursor, 0)

        // The command name ends at the first whitespace after it; with none, the
        // cursor is still in the name itself and there is no expression yet.
        val nameEnd = (start until line.length).firstOrNull { line[it].isWhitespace() } ?: return null
        if (line.substring(start, nameEnd) !in EXPRESSION_COMMANDS) return null

        var expressionStart = nameEnd
        while (expressionStart < line.length && line[expressionStart].isWhitespace()) expressionStart++
        if (cursor < expressionStart) return null

        return Slice(line.substring(expressionStart), cursor - expressionStart, expressionStart)
    }
}
