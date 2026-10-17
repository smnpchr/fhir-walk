package dev.fhirwalk.repl

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime

/**
 * Names for expressions, so a `:view` column can be asked for as `family` rather
 * than `#1`, and is titled that way.
 *
 * A name holds the expression's text, not its number in the [ExpressionLog]: a hand
 * edit that renumbers the log must not make `family` mean something else. One
 * `name<TAB>expression` per line; a line without a tab, or with a name that is not
 * [valid], is skipped.
 *
 * Like the log, the file is re-read whenever it has changed, so sessions sharing it
 * see each other's names, and once reading or writing has failed it is left alone.
 *
 * @param file where names persist, or null to keep them for this session only.
 */
class NameStore(private val file: Path?) {

    private var names: Map<String, String> = emptyMap()

    /** Modification time and size at the last read, to skip re-reading an unchanged file. */
    private var stamp: Pair<FileTime, Long>? = null

    /** Why the file is not being used, once reading or writing it has failed. */
    var problem: String? = null
        private set

    /** In the order they were first named. */
    fun all(): Map<String, String> {
        refresh()
        return names
    }

    fun get(name: String): String? = all()[name]

    /** The names that hold [expression], for showing beside it in `:history`. */
    fun namesOf(expression: String): List<String> = all().filterValues { it == expression }.keys.toList()

    /** @return what [name] held before, or null when it is new. */
    fun set(name: String, expression: String): String? {
        require(valid(name)) { "not a valid name: $name" }
        val before = all()[name]
        update(all() + (name to expression))
        return before
    }

    /** @return what [name] held, or null when there was no such name. */
    fun remove(name: String): String? {
        val before = all()[name] ?: return null
        update(all() - name)
        return before
    }

    private fun update(changed: Map<String, String>) {
        names = changed
        if (file == null || problem != null) return
        try {
            file.parent?.let { Files.createDirectories(it) }
            // Rewritten whole, through a sibling, so a failed write cannot leave half a file.
            val temp = Files.createTempFile(file.parent ?: Paths.get("."), ".names", ".tmp")
            Files.writeString(temp, changed.entries.joinToString("") { (name, expression) -> "$name\t$expression\n" })
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            stamp = null
        } catch (e: IOException) {
            problem = "cannot write $file: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun refresh() {
        if (file == null || problem != null) return
        try {
            if (!Files.exists(file)) {
                names = emptyMap()
                stamp = null
                return
            }
            val current = Files.getLastModifiedTime(file) to Files.size(file)
            if (current == stamp) return
            names = Files.readAllLines(file).mapNotNull { line ->
                val name = line.substringBefore('\t', "").trim()
                val expression = line.substringAfter('\t', "").trim()
                (name to expression).takeIf { valid(name) && expression.isNotEmpty() }
            }.toMap()
            stamp = current
        } catch (e: IOException) {
            problem = "cannot read $file: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    companion object {
        /**
         * A letter first, so a name can never be read as a `:history` number;
         * then letters, digits, `-` and `_`.
         */
        private val NAME = Regex("[A-Za-z][A-Za-z0-9_-]*")

        fun valid(name: String): Boolean = NAME.matches(name)

        /** `~/.fhir-walk/names`. */
        fun default(): NameStore = NameStore(Paths.get(System.getProperty("user.home"), ".fhir-walk", "names"))
    }
}
