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

    /** Commands are conventional: whitespace-separated words. */
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
        var index = words.size - 1
        if (index < 0) {
            words += ""
            starts += line.length
            index = 0
        } else if (cursor > 0 && cursor <= line.length && line.getOrNull(cursor - 1)?.isWhitespace() == true) {
            // Cursor sits after a space: a new, empty word.
            words += ""
            starts += cursor
            index = words.size - 1
        }
        val word = words[index]
        return Parsed(line, cursor, word, (cursor - starts[index]).coerceIn(0, word.length), index, words)
    }

    /**
     * Implements [CompletingParsedLine] so completion works inside quoted strings.
     * Without it JLine warns and mis-handles the replacement — which would break the
     * most valuable case, completing an extension URL inside `extension('…')`.
     */
    private class Parsed(
        private val line: String,
        private val cursor: Int,
        private val word: String,
        private val wordCursor: Int,
        private val wordIndex: Int,
        private val words: MutableList<String>,
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

        override fun rawWordCursor(): Int = wordCursor

        override fun rawWordLength(): Int = word.length
    }
}
