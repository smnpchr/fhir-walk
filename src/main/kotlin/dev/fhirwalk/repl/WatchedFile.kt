package dev.fhirwalk.repl

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime

/**
 * A small text file that several sessions may share and the user may edit by hand:
 * the storage under [ExpressionLog] and [NameStore].
 *
 * It is re-read only when its modification time or size has changed. Once reading
 * or writing has failed, [problem] says why and the file is left alone for the rest
 * of the session: writing to a file whose contents could not be read would
 * overwrite, or number against, what it really holds.
 *
 * @param path the file, or null for none -- every read is then empty and every
 *   write is declined, so the owner keeps its state in memory.
 */
class WatchedFile(private val path: Path?) {

    /** Modification time and size at the last read; null when the file was absent. */
    private var stamp: Pair<FileTime, Long>? = null

    /** False until the first read, and again after each write, so the next read happens. */
    private var current = false

    var problem: String? = null
        private set

    /** Whether writes can still go to the file. */
    val usable: Boolean get() = path != null && problem == null

    /**
     * @param force read even if the file looks unchanged: modification times can be
     *   coarse, and a write must not be based on a stale copy.
     * @return the lines when the file changed since the last read (empty when it does
     *   not exist), or null when it did not, or when it is unusable.
     */
    fun readIfChanged(force: Boolean = false): List<String>? {
        if (path == null || problem != null) return null
        return try {
            val now = if (Files.exists(path)) Files.getLastModifiedTime(path) to Files.size(path) else null
            if (current && !force && now == stamp) return null
            val lines = if (now == null) emptyList() else Files.readAllLines(path)
            stamp = now
            current = true
            lines
        } catch (e: IOException) {
            fail("read", e)
            null
        }
    }

    /**
     * Appends one line, first ending the file's last line if a hand edit left it
     * without a newline -- appending would otherwise join the two.
     *
     * @return false when the file is unusable or the write failed.
     */
    fun appendLine(line: String): Boolean = write {
        val target = path!!
        target.parent?.let { Files.createDirectories(it) }
        val separator = if (endsWithoutNewline(target)) "\n" else ""
        Files.writeString(target, separator + line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    /**
     * Replaces the whole file, through a sibling so a failed write cannot leave half
     * a file. A symlink is written through, not replaced: the file may live in a
     * dotfiles repository.
     *
     * @return false when the file is unusable or the write failed.
     */
    fun replace(text: String): Boolean = write {
        val target = if (Files.isSymbolicLink(path!!)) path.toRealPath() else path
        val dir = target.parent ?: Paths.get(".")
        Files.createDirectories(dir)
        val temp = Files.createTempFile(dir, ".${target.fileName}", ".tmp")
        try {
            Files.writeString(temp, text)
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun write(action: () -> Unit): Boolean {
        if (!usable) return false
        return try {
            action()
            current = false
            true
        } catch (e: IOException) {
            fail("write", e)
            false
        }
    }

    private fun fail(what: String, e: IOException) {
        problem = "cannot $what $path: ${e.message ?: e.javaClass.simpleName}"
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
        /** `~/.fhir-walk/<name>`. */
        fun inHome(name: String): Path = Paths.get(System.getProperty("user.home"), ".fhir-walk", name)
    }
}
