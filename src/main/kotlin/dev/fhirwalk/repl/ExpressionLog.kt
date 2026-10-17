package dev.fhirwalk.repl

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime

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
class ExpressionLog(private val file: Path?) {

    /** What the file held when last read. */
    private var stored: List<String> = emptyList()

    /** Modification time and size at the last read, to skip re-reading an unchanged file. */
    private var stamp: Pair<FileTime, Long>? = null

    /** Entries recorded while the file could not be used, numbered after [stored]. */
    private val sessionOnly = mutableListOf<String>()

    /**
     * Why the file is not being used, once reading or writing it has failed. From
     * then on nothing is written to it: appending to a file whose contents could not
     * be read would hand out numbers that disagree with it.
     */
    var problem: String? = null
        private set

    /** Oldest first; entry n is at index n - 1. */
    fun entries(): List<String> {
        refresh()
        return stored + sessionOnly
    }

    /** @param number one-based, as `:history` prints it. */
    fun get(number: Int): String? = entries().getOrNull(number - 1)

    /** @return true when the expression was new and has been added. */
    fun record(expression: String): Boolean {
        if (expression.isBlank() || expression in entries()) return false
        if (file == null || problem != null) {
            sessionOnly += expression
            return true
        }
        try {
            file.parent?.let { Files.createDirectories(it) }
            // A hand edit may leave no final newline; appending would join two lines.
            val separator = if (endsWithoutNewline(file)) "\n" else ""
            Files.writeString(file, separator + expression + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
            stamp = null
        } catch (e: IOException) {
            problem = "cannot write $file: ${e.message ?: e.javaClass.simpleName}"
            sessionOnly += expression
        }
        return true
    }

    private fun refresh() {
        if (file == null || problem != null) return
        try {
            if (!Files.exists(file)) {
                stored = emptyList()
                stamp = null
                return
            }
            val current = Files.getLastModifiedTime(file) to Files.size(file)
            if (current == stamp) return
            stored = Files.readAllLines(file).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            stamp = current
        } catch (e: IOException) {
            problem = "cannot read $file: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun endsWithoutNewline(file: Path): Boolean {
        if (!Files.exists(file) || Files.size(file) == 0L) return false
        Files.newByteChannel(file).use { channel ->
            val last = ByteBuffer.allocate(1)
            channel.position(channel.size() - 1).read(last)
            return last.get(0) != '\n'.code.toByte()
        }
    }

    companion object {
        /** `~/.fhir-walk/expressions`. */
        fun default(): ExpressionLog =
            ExpressionLog(Paths.get(System.getProperty("user.home"), ".fhir-walk", "expressions"))
    }
}
