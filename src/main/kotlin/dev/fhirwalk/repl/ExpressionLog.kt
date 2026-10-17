package dev.fhirwalk.repl

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

/**
 * Pool-wide expressions that parsed and matched something, kept across sessions.
 *
 * Append-only and free of duplicates, so an entry's number never changes: `:view #3`
 * means the same column tomorrow as today. Running an old expression again does not
 * move it. Only pool-wide expressions are kept, because an expression typed under an
 * element focus (`coding.code`) means nothing once that focus is gone.
 *
 * One expression per line, in a plain file you can read, edit or prune by hand.
 *
 * @param file where entries persist, or null to keep them for this session only.
 */
class ExpressionLog(private val file: Path?) {

    private val entries: MutableList<String> = load()

    /** Set once a write has failed, so the failure is reported once rather than per entry. */
    var writeError: String? = null
        private set

    /** Oldest first; entry n is at index n - 1. */
    fun entries(): List<String> = entries

    /** @param number one-based, as `:history` prints it. */
    fun get(number: Int): String? = entries.getOrNull(number - 1)

    /** @return true when the expression was new and has been added. */
    fun record(expression: String): Boolean {
        if (expression.isBlank() || expression in entries) return false
        entries += expression
        if (file != null && writeError == null) {
            try {
                file.parent?.let { Files.createDirectories(it) }
                Files.writeString(
                    file,
                    expression + "\n",
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                )
            } catch (e: IOException) {
                writeError = "cannot write $file: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        return true
    }

    private fun load(): MutableList<String> {
        if (file == null || !Files.isRegularFile(file)) return mutableListOf()
        return runCatching {
            Files.readAllLines(file).map { it.trim() }.filter { it.isNotEmpty() }.distinct().toMutableList()
        }.getOrDefault(mutableListOf())
    }

    companion object {
        /** `~/.fhir-walk/expressions`. */
        fun default(): ExpressionLog =
            ExpressionLog(Paths.get(System.getProperty("user.home"), ".fhir-walk", "expressions"))
    }
}
