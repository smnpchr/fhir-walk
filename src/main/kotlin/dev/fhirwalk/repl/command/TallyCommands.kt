package dev.fhirwalk.repl.command

import dev.fhirwalk.eval.ViewBuilder
import dev.fhirwalk.repl.Output

/**
 * Counting across several recorded expressions at once: `:tally-join gender given`
 * groups a view's rows by its first column, then by the second within each group,
 * and counts at every level, most first.
 *
 * The two commands differ only where a cell holds several values. `:tally-join`
 * counts the cell as it reads in `:view` (`Erika, Maria, Rika` is one value), so every
 * resource is counted once at each level. `:tally-split` counts each value on its own,
 * as `:tally` does, so one resource can sit under several groups. Where every column
 * holds one value per resource, they answer the same.
 *
 * Like `:view`, they take recorded expressions only (`#n`, names, `@view`); `:tally`
 * is the one that takes an expression.
 */
internal class TallyCommands(private val state: ReplState, private val records: RecordCommands) : CommandGroup {

    override val commands = listOf(
        command(":tally-join") { argument, out -> tally(argument, out, split = false) },
        command(":tally-split") { argument, out -> tally(argument, out, split = true) },
    )

    /** One group: the value it stands for, the rows under it, and its groups by the next column. */
    private class Node(val key: String, val failed: Boolean, val rows: List<ViewBuilder.Row>, val children: List<Node>) {
        val count: Int get() = rows.size
    }

    private fun tally(argument: String, out: Output, split: Boolean) {
        val name = if (split) ":tally-split" else ":tally-join"
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
                out.error("${result.message}")
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
            when {
                node.failed -> error(ViewBuilder.ERROR_CELL)
                node.key.isEmpty() -> hint("—")
                else -> shrink(node.key, Output.Kind.PREVIEW, cutsEnd = true)
            }
        }
        val rows = mutableListOf<Output.Line>()
        val full = mutableListOf<Output.Line>()
        fun walk(nodes: List<Node>, level: Int) {
            for (node in nodes) {
                rows += line(node, level)
                full += line(node, level)
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
     * next column. A row whose cell there failed is one group, `!`; one with no value
     * there is one group, shown `—`.
     */
    private fun group(rows: List<ViewBuilder.Row>, level: Int, depth: Int, split: Boolean): List<Node> {
        class Key(val text: String, val failed: Boolean)
        val byKey = LinkedHashMap<Pair<String, Boolean>, MutableList<ViewBuilder.Row>>()
        for (row in rows) {
            val keys = when {
                level in row.failed -> listOf(Key("", failed = true))
                split -> row.values[level].distinct().ifEmpty { listOf("") }.map { Key(it, failed = false) }
                else -> listOf(Key(row.cells[level], failed = false))
            }
            keys.forEach { key -> byKey.getOrPut(key.text to key.failed) { mutableListOf() } += row }
        }
        return byKey.map { (key, under) ->
            Node(key.first, key.second, under, if (level + 1 < depth) group(under, level + 1, depth, split) else emptyList())
        }.sortedWith(compareByDescending<Node> { it.count }.thenBy { it.failed }.thenBy { it.key.isEmpty() }.thenBy { it.key })
    }
}
