package dev.fhirwalk.repl

import java.nio.file.Path

/**
 * Values under names, one `name<TAB>field<TAB>field…` per line: the storage under
 * [NameStore] and [ViewStore]. A line whose name is not [valid], or whose fields
 * [decode] refuses, is skipped, so a hand edit gone wrong costs that line only.
 *
 * Shared by sessions, so the file is re-read whenever it has changed, and always
 * right before a rewrite: a write based on a stale copy would drop what another
 * session has just added.
 *
 * @param file where values persist, or null to keep them for this session only.
 * @param encode a value as its fields, none of which may contain a tab or newline.
 */
class KeyedStore<V>(
    file: Path?,
    private val encode: (V) -> List<String>,
    private val decode: (List<String>) -> V?,
) {

    private val file = WatchedFile(file)

    private var values: Map<String, V> = emptyMap()

    /** Why the file is not being used, once reading or writing it has failed. */
    val problem: String? get() = file.problem

    /** In the order they were first set. */
    fun all(): Map<String, V> {
        refresh(force = false)
        return values
    }

    fun get(name: String): V? = all()[name]

    /** @return what [name] held before, or null when it is new. */
    fun set(name: String, value: V): V? {
        require(valid(name)) { "not a valid name: $name" }
        refresh(force = true)
        val before = values[name]
        update(values + (name to value))
        return before
    }

    /** @return what [name] held, or null when there was no such name. */
    fun remove(name: String): V? {
        refresh(force = true)
        val before = values[name] ?: return null
        update(values - name)
        return before
    }

    private fun update(changed: Map<String, V>) {
        values = changed
        file.replace(changed.entries.joinToString("") { (name, value) -> (listOf(name) + encode(value)).joinToString("\t") + "\n" })
    }

    private fun refresh(force: Boolean) {
        val lines = file.readIfChanged(force) ?: return
        values = lines.mapNotNull { line ->
            val fields = line.split('\t').map { it.trim() }
            val name = fields.first()
            if (!valid(name)) return@mapNotNull null
            decode(fields.drop(1))?.let { name to it }
        }.toMap()
    }

    companion object {
        /**
         * A letter first, so a name can never be read as a `:history` number;
         * then letters, digits, `-` and `_`.
         */
        private val NAME = Regex("[A-Za-z][A-Za-z0-9_-]*")

        fun valid(name: String): Boolean = NAME.matches(name)
    }
}
