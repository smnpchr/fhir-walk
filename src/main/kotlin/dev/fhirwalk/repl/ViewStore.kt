package dev.fhirwalk.repl

import dev.fhirwalk.eval.ViewBuilder.Column
import java.nio.file.Path

/**
 * Saved `:view`s, rerun as `:view @patients`.
 *
 * Like a name, a view holds its columns' expression text rather than their
 * numbers, so renumbering the log cannot change what it shows; each column keeps
 * the reference it was asked for by, which is what titles it. One
 * `name<TAB>ref<TAB>expression<TAB>ref<TAB>expression…` per line.
 *
 * @param file where views persist, or null to keep them for this session only.
 */
class ViewStore(file: Path?) {

    private val store = KeyedStore(
        file,
        encode = { columns: List<Column> -> columns.flatMap { listOf(it.ref, it.expression) } },
    ) { fields ->
        fields.takeIf { it.isNotEmpty() && it.size % 2 == 0 }
            ?.chunked(2) { (ref, expression) -> Column(ref, expression) }
            ?.takeIf { columns -> columns.all { it.expression.isNotEmpty() } }
    }

    val problem: String? get() = store.problem

    fun all(): Map<String, List<Column>> = store.all()

    fun get(name: String): List<Column>? = store.get(name)

    /** @return what [name] held before, or null when it is new. */
    fun set(name: String, columns: List<Column>): List<Column>? = store.set(name, columns)

    fun remove(name: String): List<Column>? = store.remove(name)
}
