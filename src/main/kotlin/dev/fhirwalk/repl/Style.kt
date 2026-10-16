package dev.fhirwalk.repl

import org.jline.terminal.Terminal
import org.jline.utils.InfoCmp

/**
 * Colour for printed output, applied as the last step before a row is printed.
 *
 * Everything that measures text -- padding columns, trimming a value to the window,
 * spotting the "…" that means a row was shortened -- works on the plain string, so
 * styling must come after it. [Render] stays plain for the same reason, and a
 * disabled style returns its input unchanged, which keeps piped output clean.
 *
 * Only the basic eight colours plus bold and faint are used, so it reads on light
 * and dark backgrounds alike.
 */
class Style(val enabled: Boolean) {

    fun index(text: String) = paint(FAINT, text)
    fun type(text: String) = paint(CYAN, text)
    fun label(text: String) = paint(BOLD, text)
    fun hint(text: String) = paint(FAINT, text)
    fun count(text: String) = paint(YELLOW, text)
    fun url(text: String) = paint(GREEN, text)

    /**
     * Highlights a rendered value if it is JSON, and returns it as is otherwise --
     * a primitive or a fallback description has no tokens worth colouring.
     */
    fun value(text: String, keysOnly: Boolean = false): String =
        if (text.trimStart().firstOrNull() in JSON_START) json(text, keysOnly) else text

    /**
     * Highlights one line of JSON, as produced by [Render.pretty] or flattened by
     * [Render.value].
     *
     * Works line by line with no structure: a string is a key when a ':' follows it,
     * which holds for both forms since a key and its colon are never split. A line
     * cut short by "…" may end inside a string; that string is coloured to the end.
     *
     * @param keysOnly colour only the keys. A preview row packs a whole value onto
     *   one line, where colouring every token is busier than it is helpful.
     */
    fun json(line: String, keysOnly: Boolean = false): String {
        if (!enabled) return line

        val out = StringBuilder(line.length + 32)
        var i = 0
        while (i < line.length) {
            val char = line[i]
            when {
                char == '"' -> {
                    val end = stringEnd(line, i)
                    val token = line.substring(i, end)
                    val isKey = line.drop(end).trimStart().startsWith(":")
                    out.append(
                        when {
                            isKey -> paint(BLUE, token)
                            keysOnly -> token
                            else -> paint(GREEN, token)
                        },
                    )
                    i = end
                }
                char == '-' || char.isDigit() -> {
                    val end = scan(line, i) { it.isDigit() || it in NUMBER_CHARS }
                    val token = line.substring(i, end)
                    out.append(if (keysOnly) token else paint(YELLOW, token))
                    i = end
                }
                char.isLetter() -> {
                    val end = scan(line, i) { it.isLetter() }
                    val token = line.substring(i, end)
                    out.append(if (keysOnly || token !in LITERALS) token else paint(MAGENTA, token))
                    i = end
                }
                else -> {
                    out.append(char)
                    i++
                }
            }
        }
        return out.toString()
    }

    private fun paint(code: String, text: String): String =
        if (!enabled || text.isBlank()) text else "$ESC${code}m$text${ESC}0m"

    /** Index just past the closing quote of the string opening at [start], or the line end. */
    private fun stringEnd(line: String, start: Int): Int {
        var i = start + 1
        while (i < line.length) {
            when (line[i]) {
                '\\' -> i++
                '"' -> return i + 1
            }
            i++
        }
        return line.length
    }

    private inline fun scan(line: String, start: Int, accept: (Char) -> Boolean): Int {
        var i = start + 1
        while (i < line.length && accept(line[i])) i++
        return i
    }

    companion object {
        val PLAIN = Style(false)

        /**
         * Colour only on a real terminal that reports colours, and never when the
         * user has set `NO_COLOR` (https://no-color.org). `./gradlew run` and pipes
         * get a dumb terminal, so scripted sessions stay free of escape codes.
         */
        fun detect(terminal: Terminal): Style {
            if (!System.getenv("NO_COLOR").isNullOrEmpty()) return PLAIN
            if (terminal.type == Terminal.TYPE_DUMB || terminal.type == Terminal.TYPE_DUMB_COLOR) return PLAIN
            val colours = terminal.getNumericCapability(InfoCmp.Capability.max_colors) ?: 0
            return Style(colours >= 8)
        }

        private const val ESC = "\u001b["
        private const val BOLD = "1"
        private const val FAINT = "2"
        private const val GREEN = "32"
        private const val YELLOW = "33"
        private const val BLUE = "34"
        private const val MAGENTA = "35"
        private const val CYAN = "36"

        private val JSON_START = setOf('{', '[')
        private val NUMBER_CHARS = setOf('.', 'e', 'E', '+', '-')
        private val LITERALS = setOf("true", "false", "null")
    }
}
