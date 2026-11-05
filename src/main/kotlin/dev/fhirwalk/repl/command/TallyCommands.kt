package dev.fhirwalk.repl.command

import dev.fhirwalk.eval.ViewBuilder
import dev.fhirwalk.repl.Output

/**
 * Counting across several recorded expressions at once: `:tally-join gender given`
 * groups a view's rows by its first column, then by the second within each group,
 * and counts at every level, most first.
 *
 * Both count values as `:view` shows them: one line each, trimmed, without their
 * type. So, unlike `:tally`, they count `"Ann "` and `"Ann"` as one value.
 *
 * The two commands differ only where a cell holds several values. `:tally-join`
 * counts the cell as it reads in `:view` (`Erika, Maria, Rika` is one value), so every
 * resource is counted once at each level. `:tally-split` counts each value on its own,
 * so one resource can sit under several groups. Where every column holds one value per
 * resource, they answer the same.
 *
 * Like `:view`, they take recorded expressions only (`#n`, names, `@view`); `:tally`
 * is the one that takes an expression.
 */
internal class TallyCommands(private val state: ReplState, private val records: RecordCommands) : CommandGroup {

    override val commands = listOf(
        command(":tally-join") { argument, out -> tally(argument, out, split = false) },
        command(":tally-split") { argument, out -> tally(argument, out, split = true) },
    )

    /** What a group stands for. Declared in the order groups of one count are listed. */
    private enum class Kind { VALUE, NONE, FAILED }

    private data class Key(val kind: Kind, val text: String = "")

    /** One group: what it stands for, the rows under it, and its groups by the next column. */
    private class Node(val key: Key, val rows: List<ViewBuilder.Row>, val children: List<Node>) {
        val count: Int get() = rows.size
    }

    private fun tally(argument: String, out: Output, split: Boolean) {
        val name = if (split) ":tally-split" else ":tally-join"
        // A result with no numbered rows, so :focus [n] has nothing to pick (see
        // ReplState.lastPicks).
        state.lastPicks = null
        val refs = argument.split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
        if (refs.isEmpty()) {
            out.text("usage: $name #n|name|@view …  —  groups by the first, then the next within it; :manual tally")
            return
        }
        state.reportStorageProblems(out)
        val picked = records.columns(refs, out) ?: return
        val table = when (val result = state.session.viewBuilder.build(picked)) {
            is ViewBuilder.Result.Refused -> {
                out.error(result.message)
                return
            }
            is ViewBuilder.Result.Table -> result
        }
        // Its columns are a view's, so :name-view can save them, as after :view.
        state.lastView = picked
        val tree = group(table.rows, 0, table.columns.size, split)
        val width = table.rows.size.toString().length
        val titles = table.columns.map { it.title(table.type) }
        val header = Output.line {
            +"${table.rows.size} ${table.type}(s) by ${titles.joinToString(" › ")}"
            if (split && table.rows.any { row -> row.values.any { it.size > 1 } }) hint("; a cell's values counted one by one")
            if (table.errors > 0) error("; ${table.errors} error(s): ${table.firstError}")
        }
        fun line(node: Node, level: Int) = Output.line {
            +"  "
            +"    ".repeat(level)
            count(node.count.toString().padStart(width))
            +"  "
            when (node.key.kind) {
                Kind.FAILED -> error(FAILED)
                Kind.NONE -> hint(NO_VALUE)
                // A value that is only whitespace reads as nothing once trimmed.
                Kind.VALUE -> if (node.key.text.isEmpty()) hint("\"\"") else shrink(node.key.text, Output.Kind.PREVIEW, cutsEnd = true)
            }
        }
        val rows = mutableListOf<Output.Line>()
        val full = mutableListOf<Output.Line>()
        fun walk(nodes: List<Node>, level: Int) {
            for (node in nodes) {
                val shown = line(node, level)
                rows += shown
                full += shown
                if (node.children.isEmpty()) {
                    // Whole, each innermost group lists the resources counted in it.
                    node.rows.forEach { row -> full += Output.line { +"  "; +"    ".repeat(level + 1); label(row.resource.label) } }
                } else {
                    walk(node.children, level + 1)
                }
            }
        }
        walk(tree, 0)
        out.listing(listOf(header), rows, full)
    }

    /**
     * The rows grouped by column [level], most first, each group grouped again by the
     * next column. Rows whose cell there failed are one group, and rows with no value
     * there another; of groups that count alike, values come first, then those two.
     */
    private fun group(rows: List<ViewBuilder.Row>, level: Int, depth: Int, split: Boolean): List<Node> {
        val byKey = LinkedHashMap<Key, MutableList<ViewBuilder.Row>>()
        for (row in rows) {
            val values = row.values[level]
            val keys = when {
                level in row.failed -> listOf(Key(Kind.FAILED))
                values.isEmpty() -> listOf(Key(Kind.NONE))
                split -> values.distinct().map { Key(Kind.VALUE, it) }
                else -> listOf(Key(Kind.VALUE, row.cells[level]))
            }
            keys.forEach { key -> byKey.getOrPut(key) { mutableListOf() } += row }
        }
        return byKey.map { (key, under) ->
            Node(key, under, if (level + 1 < depth) group(under, level + 1, depth, split) else emptyList())
        }.sortedWith(compareByDescending<Node> { it.count }.thenBy { it.key.kind }.thenBy { it.key.text })
    }

    private companion object {
        /** Markers no value is likely to read as: a cell's value could be `!` or `—`. */
        const val NO_VALUE = "(no value)"
        const val FAILED = "(failed)"
    }
}
