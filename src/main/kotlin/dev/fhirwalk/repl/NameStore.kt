package dev.fhirwalk.repl

import java.nio.file.Path

/**
 * Names for expressions, so a `:view` column can be asked for as `family` rather
 * than `#1`, and is titled that way.
 *
 * A name holds the expression's text, not its number in the [ExpressionLog]: a hand
 * edit that renumbers the log must not make `family` mean something else. One
 * `name<TAB>expression` per line; names are case-sensitive.
 *
 * @param file where names persist, or null to keep them for this session only.
 */
class NameStore(file: Path?) {

    // Everything after the name is the expression, tabs and all: a hand edit may
    // have put one inside a string literal.
    private val store = KeyedStore(file, encode = { expression: String -> listOf(expression) }) { fields ->
        fields.joinToString("\t").trim().takeIf { it.isNotEmpty() }
    }

    /** Why the file is not being used, once reading or writing it has failed. */
    val problem: String? get() = store.problem

    /** In the order they were first named. */
    fun all(): Map<String, String> = store.all()

    fun get(name: String): String? = store.get(name)

    /** @return what [name] held before, or null when it is new. */
    fun set(name: String, expression: String): String? = store.set(name, expression)

    /** @return what [name] held, or null when there was no such name. */
    fun remove(name: String): String? = store.remove(name)

    companion object {
        fun valid(name: String): Boolean = KeyedStore.valid(name)
    }
}
