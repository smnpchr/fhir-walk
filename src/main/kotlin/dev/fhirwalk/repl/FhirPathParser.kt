package dev.fhirwalk.repl

import dev.fhirwalk.eval.FhirPathTokenizer
import dev.fhirwalk.eval.PromptLine
import org.jline.reader.CompletingParsedLine
import org.jline.reader.ParsedLine
import org.jline.reader.Parser

/**
 * Tells JLine where the "word under the cursor" ends in a FHIRPath expression.
 *
 * The stock parser splits on whitespace, which would make `Patient.name.giv` a
 * single word and replace the whole thing on completion. Here the word is just the
 * trailing identifier -- or the partial contents of a string literal, so completing
 * an extension URL replaces only what has been typed inside the quotes.
 */
class FhirPathParser : Parser {

    override fun parse(line: String, cursor: Int, context: Parser.ParseContext): ParsedLine {
        // `:all Patient.na` is an expression with a command in front of it, so the
        // word boundaries inside the argument are FHIRPath's, not whitespace.
        val slice = PromptLine.expressionSlice(line, cursor) ?: return parseCommand(line, cursor)

        val ctx = FhirPathTokenizer.cursorContext(slice.text, slice.cursor)
        val word = ctx.partial
        return Parsed(
            line = line,
            cursor = cursor,
            word = word,
            wordCursor = (cursor - slice.offset - ctx.replaceFrom).coerceIn(0, word.length),
            wordIndex = 0,
            words = mutableListOf(word),
        )
    }

    /**
     * Commands are conventional: whitespace-separated words, and the word is the one
     * the cursor is in, not the last: in `:sco| 0787` it is `:sco`, which completion
     * replaces as a whole. At the start of a word it is that word, cursor 0, so
     * `:scope |0787` completes `0787`: where a first TAB leaves the cursor after
     * completing the name.
     *
     * A name picked from the menu gets a space of its own, `:fhir | r4`, so whitespace
     * up to the first argument counts as its start too: the word is `r4`, and what is
     * replaced runs from the cursor to its end, which takes the extra space away.
     * Elsewhere in whitespace the cursor starts a new, empty word there, so TAB can
     * add an argument between two.
     */
    private fun parseCommand(line: String, cursor: Int): ParsedLine {
        val words = mutableListOf<String>()
        val starts = mutableListOf<Int>()
        var i = 0
        while (i < line.length) {
            while (i < line.length && line[i].isWhitespace()) i++
            if (i >= line.length) break
            val start = i
            while (i < line.length && !line[i].isWhitespace()) i++
            starts += start
            words += line.substring(start, i)
        }
        val at = cursor.coerceIn(0, line.length)
        val within = words.indices.firstOrNull { at >= starts[it] && at <= starts[it] + words[it].length }
        if (within != null) {
            val word = words[within]
            return Parsed(line, cursor, word, (at - starts[within]).coerceIn(0, word.length), within, words)
        }
        val before = starts.count { it < at }
        if (before == 1 && words.size > 1) {
            // Between the name and its first argument, with only whitespace to it.
            val word = words[1]
            return Parsed(line, cursor, word, 0, 1, words, rawWordCursor = 0, rawWordLength = starts[1] - at + word.length)
        }
        // In whitespace, or on an empty line: a new, empty word, in its place among the others.
        words.add(before, "")
        return Parsed(line, cursor, "", 0, before, words)
    }

    /**
     * Implements [CompletingParsedLine] so completion works inside quoted strings.
     * Without it JLine warns and mis-handles the replacement — which would break the
     * most valuable case, completing an extension URL inside `extension('…')`.
     */
    /**
     * @param rawWordCursor where the cursor is in what completion replaces, and
     * @param rawWordLength how long that is: the [word] itself, unless whitespace
     *   before it goes with it (see [parseCommand]).
     */
    private class Parsed(
        private val line: String,
        private val cursor: Int,
        private val word: String,
        private val wordCursor: Int,
        private val wordIndex: Int,
        private val words: MutableList<String>,
        private val rawWordCursor: Int = wordCursor,
        private val rawWordLength: Int = word.length,
    ) : CompletingParsedLine {
        override fun word(): String = word
        override fun wordCursor(): Int = wordCursor
        override fun wordIndex(): Int = wordIndex
        override fun words(): MutableList<String> = words
        override fun line(): String = line
        override fun cursor(): Int = cursor

        /**
         * Candidates are inserted verbatim. The cursor is already inside the quotes
         * when completing a URL, so letting JLine add its own quoting would corrupt
         * the expression.
         */
        override fun escape(candidate: CharSequence, complete: Boolean): CharSequence = candidate

        override fun rawWordCursor(): Int = rawWordCursor

        override fun rawWordLength(): Int = rawWordLength
    }
}
