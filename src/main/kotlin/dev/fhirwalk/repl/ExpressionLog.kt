package dev.fhirwalk.repl

import java.nio.file.Path

/**
 * Pool-wide expressions that parsed and matched something, kept across sessions.
 *
 * Append-only and free of duplicates, so an entry's number never changes: `:view #3`
 * means the same column tomorrow as today. Running an old expression again does not
 * move it. Only pool-wide expressions are kept, because an expression typed under an
 * element focus (`coding.code`) means nothing once that focus is gone.
 *
 * One expression per line, in a plain file you can read and edit by hand. Blank and
 * repeated lines are skipped, so entry n is the n-th distinct expression -- which
 * means deleting a line renumbers everything after it. Appending, or editing a line
 * in place, keeps the numbers.
 *
 * The file is the one source of numbers: it is re-read whenever it has changed, so
 * two sessions appending to it agree on what `#5` is.
 *
 * @param file where entries persist, or null to keep them for this session only.
 */
class ExpressionLog(file: Path?) {

    private val file = WatchedFile(file)

    /** What the file held when last read. */
    private var stored: List<String> = emptyList()

    /** Entries recorded while the file could not be used, numbered after [stored]. */
    private val sessionOnly = mutableListOf<String>()

    /** Why the file is not being used, once reading or writing it has failed. */
    val problem: String? get() = file.problem

    /** Oldest first; entry n is at index n - 1. */
    fun entries(): List<String> {
        file.readIfChanged()?.let { lines -> stored = lines.map { it.trim() }.filter { it.isNotEmpty() }.distinct() }
        return stored + sessionOnly
    }

    /** @param number one-based, as `:history` prints it. */
    fun get(number: Int): String? = entries().getOrNull(number - 1)

    /** @return true when the expression was new and has been added. */
    fun record(expression: String): Boolean {
        if (expression.isBlank() || expression in entries()) return false
        if (!file.appendLine(expression)) sessionOnly += expression
        return true
    }
}
