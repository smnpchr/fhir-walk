package dev.fhirwalk.repl

import dev.fhirwalk.eval.PromptLine

/**
 * A line as typed, made one line if it was pasted as several (#103). JLine's bracketed
 * paste hands an expression copied from an editor, or a rule file's `match: >`, to the
 * REPL as one line with its line breaks inside. Everything kept from a line, the
 * expression log, names, views and the state file, holds one per line, so a line break
 * left in would come back as several entries.
 *
 * In FHIRPath (an expression, or the argument of a command that takes one), outside
 * quotes and comments, each line break, with the whitespace around it, becomes one
 * space, or none where it joins a path step or a bracket (`Specimen` / `.where(`). A
 * `//` comment ends at its line break, so it is dropped rather than left to swallow the
 * lines after it; a `/* … */` one is kept, made one line. Inside a string or a quoted
 * identifier, a line break cannot be folded without changing the value, so the line is
 * refused. Any other command's argument (a path, a name) is not FHIRPath: there each
 * line break just becomes a space.
 *
 * A paste whose later lines start with `:` is several commands, and is refused rather
 * than run as one, or as several at once.
 */
internal object PastedLine {

    sealed interface Result {
        /** @param folded whether there was anything to fold. */
        data class Line(val text: String, val folded: Boolean) : Result

        data class Refused(val message: String) : Result
    }

    private val BREAK = Regex("""\s*(\r\n|\r|\n)\s*""")

    fun fold(line: String): Result {
        if ('\n' !in line && '\r' !in line) return Result.Line(line, folded = false)
        if (line.trim().split(BREAK).drop(1).any { it.startsWith(":") }) {
            return Result.Refused("pasted several commands — paste one at a time")
        }
        val start = line.trimStart()
        val fhirPath = !start.startsWith(":") || start.takeWhile { !it.isWhitespace() } in PromptLine.EXPRESSION_COMMANDS
        if (!fhirPath) return Result.Line(line.replace(BREAK, " ").trim(), folded = true)
        return foldFhirPath(line)
    }

    private fun foldFhirPath(line: String): Result {
        val out = StringBuilder()
        var quote: Char? = null
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quote != null -> {
                    if (c == '\n' || c == '\r' || (c == '\\' && line.getOrNull(i + 1).let { it == '\n' || it == '\r' })) {
                        val what = if (quote == '\'') "a string" else "a quoted identifier"
                        return Result.Refused("a line break inside $what — write \\n for one, or join the lines")
                    }
                    out.append(c)
                    if (c == '\\' && i + 1 < line.length) out.append(line[++i]) else if (c == quote) quote = null
                }
                c == '\'' || c == '"' || c == '`' -> {
                    quote = c
                    out.append(c)
                }
                c == '/' && line.startsWith("/*", i) -> {
                    // Kept whole, quotes and // in it included, and made one line.
                    val end = line.indexOf("*/", i + 2).let { if (it < 0) line.length else it + 2 }
                    out.append(line.substring(i, end).replace(BREAK, " "))
                    i = end - 1
                }
                c == '/' && line.startsWith("//", i) -> {
                    // To the line break, which the next round folds.
                    while (i + 1 < line.length && line[i + 1] != '\n' && line[i + 1] != '\r') i++
                }
                c.isWhitespace() -> {
                    var end = i
                    while (end < line.length && line[end].isWhitespace()) end++
                    val run = line.substring(i, end)
                    if ('\n' in run || '\r' in run) {
                        // Also the space a dropped comment left before its line break.
                        while (out.isNotEmpty() && out.last().isWhitespace()) out.setLength(out.length - 1)
                        // `Specimen` / `  .where(` reads back as Specimen.where(, not
                        // Specimen .where(: no space where a path or a bracket joins.
                        val before = out.lastOrNull()
                        val after = line.getOrNull(end)
                        if (before != null && before !in ".([" && after !in ".)]".toList()) out.append(' ')
                    } else {
                        out.append(run)
                    }
                    i = end - 1
                }
                else -> out.append(c)
            }
            i++
        }
        return Result.Line(out.toString().trim(), folded = true)
    }
}
