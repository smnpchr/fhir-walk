package dev.fhirwalk.repl.command

import dev.fhirwalk.eval.FhirPathTokenizer
import dev.fhirwalk.eval.ReferenceTree
import dev.fhirwalk.eval.ViewBuilder
import dev.fhirwalk.pool.PooledResource
import dev.fhirwalk.repl.Output
import dev.fhirwalk.repl.Session

/**
 * The links an expression makes, as a forest. `:tree` takes FHIRPath, as `:all`
 * does; `:tree-view` takes only references, as `:view` does: a recorded or named
 * link, and the columns to show along it.
 */
internal class TreeCommands(private val state: ReplState, private val records: RecordCommands) : CommandGroup {

    private val session: Session get() = state.session

    override val commands = listOf(
        command(":tree", run = ::tree),
        command(":tree-view", run = ::treeView),
    )

    /**
     * `:tree Specimen.parent`: what in scope the expression links to its parents,
     * roots first. One that links something is recorded, as `:all` records what
     * matches, so `:name-expr` can name it for `:tree-view`.
     */
    private fun tree(argument: String, out: Output) {
        val expression = argument.trim()
        state.lastPicks = null
        state.nameable = null
        if (expression.isEmpty()) {
            out.text("usage: :tree <expr>  —  an expression naming each resource's parents, e.g. :tree Specimen.parent")
            return
        }
        // `#3` begins no FHIRPath: it can only mean the recorded one.
        if (expression.startsWith("#") && expression.drop(1).toIntOrNull() != null) {
            out.text("$expression is a :history number — :tree-view $expression runs it")
            return
        }
        if (state.reportSyntaxError(expression, out)) return
        // A name that links nothing as FHIRPath was meant as the name, not :why's question.
        val instead = state.names.get(expression)?.let { "$expression is a named expression — :tree-view $expression" }
        if (show(expression, null, null, out, instead)) {
            state.nameable = expression
            state.record(expression, out)
        }
    }

    /** `:tree-view parents kind collected`: the tree of a named link, with `:view`'s columns along it. */
    private fun treeView(argument: String, out: Output) {
        val refs = argument.split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
        state.lastPicks = null
        state.nameable = null
        if (refs.isEmpty()) {
            out.text("usage: :tree-view <#n|name> [#n|name|@view …]  —  a recorded link, and the columns to show along it")
            return
        }
        state.reportStorageProblems(out)
        if (refs.first().startsWith("@")) {
            out.text("a view gives columns, not the link — :tree-view <#n|name> ${refs.first()}")
            return
        }
        val link = records.column(refs.first(), out) ?: return
        val columns = records.columns(refs.drop(1), out) ?: return
        if (state.reportSyntaxError(link.expression, out)) return
        show(link.expression, link.ref, columns.ifEmpty { null }, out)
    }

    /**
     * Prints the tree [expression] makes, with [columns] when there are any.
     *
     * @param ref how the link was asked for, when not as itself: the header says what it stood for.
     * @param instead what to say in place of pointing to `:why` when nothing links.
     * @return whether it linked anything.
     */
    private fun show(expression: String, ref: String?, columns: List<ViewBuilder.Column>?, out: Output, instead: String? = null): Boolean {
        val pool = session.pool
        val from = session.focus?.resource
        val candidates = pool.inScope()
        val tree = ReferenceTree.build(session.evaluator, pool, expression, candidates, from)
        val table = columns?.let { asked ->
            when (val result = session.viewBuilder.build(asked, only = tree.rows.map { it.resource }.distinct())) {
                is ViewBuilder.Result.Refused -> {
                    out.error(result.message)
                    return false
                }
                is ViewBuilder.Result.Table -> result
            }
        }
        val meta = session.current.meta
        val header = Output.line {
            ref?.let { label(it); +" = "; shrink(expression); +": " }
            +"${tree.linked} resource(s) in ${tree.trees} tree(s)"
            if (from != null) +" below ${state.shown(from)}"
            if (meta != null && meta.scoped == null && from == null) {
                +", in ${tree.rows.mapTo(HashSet()) { it.resource.part }.size} of ${meta.members.size} bundle(s)"
            }
            // Of the type the expression starts at: every other resource is not what it was asked of.
            val type = FhirPathTokenizer.segments(expression).firstOrNull()?.name?.takeIf { it in session.navigator.resourceTypes() }
            if (type != null && from == null) {
                val linked = tree.rows.mapTo(HashSet()) { it.resource }
                val alone = candidates.count { it.type == type && it !in linked }
                if (alone > 0) hint("; $alone $type not linked")
            }
            if (tree.notReferences > 0) error("; ${tree.notReferences} value(s) not a Reference")
            if (tree.notPooled > 0) hint("; ${tree.notPooled} resource(s) not in the pool, such as contained ones")
            if (tree.errors > 0) error("; ${tree.errors} error(s): ${tree.firstError}")
            if (table != null && table.errors > 0) error("; ${table.errors} cell error(s): ${table.firstError}")
        }
        if (tree.rows.isEmpty()) {
            out.add(header)
            if (from != null) {
                out.text("  ${state.shown(from)} links to nothing by $expression, and nothing to it")
            } else if (instead != null) {
                out.text("  $instead")
            } else if (tree.errors == 0 && tree.notReferences == 0 && tree.notPooled == 0) {
                // Values that are no Reference are their own answer; only nothing at all is :why's question.
                out.text("  nothing links by $expression — :why $expression")
            }
            state.lastListing = null
            return false
        }
        if (table == null) {
            out.listing(
                listOf(header),
                rows = tree.rows.map { row -> listed(row, whole = false) },
                full = tree.rows.map { row -> listed(row, whole = true) },
            )
            return true
        }
        val cells = table.rows.associateBy { it.resource }
        val blank = List(table.columns.size) { "" }
        out.table(
            header = listOf(header),
            titles = table.columns.map { it.title(table.type) },
            rows = tree.rows.map { row ->
                // A row marked as shown elsewhere points there; its cells are on that row.
                val viewRow = cells[row.resource].takeIf { row.mark == ReferenceTree.Mark.None }
                Output.Table.Row(
                    label = indent(row) + name(row, row.resource, whole = false),
                    cells = if (row.mark != ReferenceTree.Mark.None) emptyList() else viewRow?.cells ?: blank,
                    errors = viewRow?.failed.orEmpty(),
                    wholeLabel = indent(row) + name(row, row.resource, whole = true),
                    note = note(row, whole = false),
                )
            },
        )
        return true
    }

    /** A row of the tree alone, indented below the listing's own two spaces. */
    private fun listed(row: ReferenceTree.Row, whole: Boolean): Output.Line = Output.line {
        +"  ${indent(row)}"
        label(name(row, row.resource, whole))
        note(row, whole)?.let { +"  "; spans += it.spans }
    }

    /** Two spaces a level: a table puts its own two before the label. */
    private fun indent(row: ReferenceTree.Row): String = "  ".repeat(row.depth)

    /**
     * Below a root, labels leave out the bundle: a link stays inside its bundle,
     * which the root's label names. [whole] keeps every label whole, for `:more`.
     */
    private fun name(row: ReferenceTree.Row, resource: PooledResource, whole: Boolean): String = when {
        whole -> resource.label
        row.depth == 0 -> state.shown(resource)
        else -> resource.part?.let { resource.label.removePrefix("$it:") } ?: resource.label
    }

    /** What a row says besides its label: where else it is shown, and what it refers to that is not here. */
    private fun note(row: ReferenceTree.Row, whole: Boolean): Output.Line? {
        val line = Output.line {
            when (val mark = row.mark) {
                ReferenceTree.Mark.None -> {}
                ReferenceTree.Mark.Cycle -> hint("↻ cycle")
                is ReferenceTree.Mark.Shown -> hint("(shown " + (mark.parent?.let { "under ${name(row, it, whole)}" } ?: "above") + ")")
            }
            if (row.missing.isNotEmpty()) {
                if (row.mark != ReferenceTree.Mark.None) +"  "
                val where = if (session.current.meta != null) "in this bundle" else "in the pool"
                shrink("! refers to ${row.missing.joinToString(", ")}, not $where", Output.Kind.ERROR, cutsEnd = true)
            }
            if (row.contained.isNotEmpty()) {
                if (row.mark != ReferenceTree.Mark.None || row.missing.isNotEmpty()) +"  "
                shrink("refers to contained ${row.contained.joinToString(", ")}", Output.Kind.HINT, cutsEnd = true)
            }
        }
        return line.takeIf { it.spans.isNotEmpty() }
    }
}
