package dev.fhirwalk.repl

import java.nio.file.Path

/**
 * Names for expressions, so a `:view` column can be asked for as `family` rather
 * than `#1`, and is titled that way.
 *
 * A name holds the expression's text, not its number in the [ExpressionLog]: a hand
 * edit that renumbers the log must not make `family` mean something else. One
 * `name<TAB>expression` per line; a line without a tab, or with a name that is not
 * [valid], is skipped. Names are case-sensitive.
 *
 * Like the log, the file is re-read whenever it has changed, so sessions sharing it
 * see each other's names, and once reading or writing has failed it is left alone.
 *
 * @param file where names persist, or null to keep them for this session only.
 */
class NameStore(file: Path?) {

    private val file = WatchedFile(file)

    private var names: Map<String, String> = emptyMap()

    /** Why the file is not being used, once reading or writing it has failed. */
    val problem: String? get() = file.problem

    /** In the order they were first named. */
    fun all(): Map<String, String> {
        refresh(force = false)
        return names
    }

    fun get(name: String): String? = all()[name]

    /** @return what [name] held before, or null when it is new. */
    fun set(name: String, expression: String): String? {
        require(valid(name)) { "not a valid name: $name" }
        // Re-read regardless of the stamp: rewriting from a stale copy would drop a
        // name another session has just added.
        refresh(force = true)
        val before = names[name]
        update(names + (name to expression))
        return before
    }

    /** @return what [name] held, or null when there was no such name. */
    fun remove(name: String): String? {
        refresh(force = true)
        val before = names[name] ?: return null
        update(names - name)
        return before
    }

    private fun update(changed: Map<String, String>) {
        names = changed
        file.replace(changed.entries.joinToString("") { (name, expression) -> "$name\t$expression\n" })
    }

    private fun refresh(force: Boolean) {
        val lines = file.readIfChanged(force) ?: return
        names = lines.mapNotNull { line ->
            val name = line.substringBefore('\t', "").trim()
            val expression = line.substringAfter('\t', "").trim()
            (name to expression).takeIf { valid(name) && expression.isNotEmpty() }
        }.toMap()
    }

    companion object {
        /**
         * A letter first, so a name can never be read as a `:history` number;
         * then letters, digits, `-` and `_`.
         */
        private val NAME = Regex("[A-Za-z][A-Za-z0-9_-]*")

        fun valid(name: String): Boolean = NAME.matches(name)

        /** `~/.fhir-walk/names`. */
        fun default(): NameStore = NameStore(WatchedFile.inHome("names"))
    }
}
