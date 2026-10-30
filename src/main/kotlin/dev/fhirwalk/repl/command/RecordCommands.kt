package dev.fhirwalk.repl.command

import dev.fhirwalk.eval.ViewBuilder
import dev.fhirwalk.repl.ExpressionLog
import dev.fhirwalk.repl.KeyedStore
import dev.fhirwalk.repl.NameStore
import dev.fhirwalk.repl.Output
import dev.fhirwalk.repl.ViewStore

/** The recorded expressions, their names, and the views built from them. */
internal class RecordCommands(private val state: ReplState) : CommandGroup {

    private val log: ExpressionLog get() = state.log
    private val names: NameStore get() = state.names
    private val views: ViewStore get() = state.views

    override val commands = listOf(
        command(":history", run = ::history),
        command(":view", run = ::view),
        command(":name-expr", run = ::name),
        command(":unname-expr", run = ::unname),
        command(":name-view", run = ::saveView),
        command(":unname-view", run = ::unsaveView),
    )

    private fun history(argument: String, out: Output) {
        val entries = log.entries()
        state.reportStorageProblems(out)
        val numbered = entries.mapIndexed { idx, expression -> idx + 1 to expression }
            .filter { (_, expression) -> expression.contains(argument, ignoreCase = true) }
            .asReversed()
        if (numbered.isEmpty()) {
            out.text(
                if (argument.isBlank()) {
                    "nothing recorded yet — pool-wide expressions that match something are kept here"
                } else {
                    "no recorded expression contains $argument"
                },
            )
            return
        }
        val width = "#${numbered.first().first}".length
        // Once per listing, not per row: each lookup checks the names file.
        val namesOf = names.all().entries.groupBy({ it.value }, { it.key })
        out.listing(
            header = emptyList(),
            rows = numbered.map { (number, expression) ->
                Output.line {
                    +"  "
                    index("#$number".padEnd(width))
                    +"  "
                    shrink(expression)
                    namesOf[expression]?.let { hint("  (${it.joinToString(", ")})") }
                }
            },
        )
    }

    /**
     * `:name-expr` lists, `:name-expr family` names the expression just run,
     * `:name-expr family #1` names a recorded one. A name may hold any expression; only `:view` asks for
     * primitives, and says so when it is used.
     */
    private fun name(argument: String, out: Output) {
        val parts = argument.split(Regex("\\s+")).filter { it.isNotEmpty() }
        state.reportStorageProblems(out)
        when (parts.size) {
            0 -> {
                val all = names.all()
                if (all.isEmpty()) {
                    out.text("no names yet — :name-expr <name> #n names a recorded expression")
                    return
                }
                val width = all.keys.maxOf { it.length }
                out.listing(
                    header = emptyList(),
                    rows = all.map { (name, expression) ->
                        Output.line {
                            +"  "
                            label(name.padEnd(width))
                            +"  "
                            shrink(expression)
                        }
                    },
                )
            }
            1, 2 -> {
                val name = parts[0]
                if (!NameStore.valid(name)) {
                    out.text("not a valid name: $name — ${KeyedStore.RULE}")
                    return
                }
                val expression = if (parts.size == 1) {
                    state.nameable ?: run {
                        out.text(
                            "nothing to name — run a pool-wide expression that matches something first, " +
                                "or :name-expr $name #n for one from :history",
                        )
                        return
                    }
                } else {
                    val number = parts[1].removePrefix("#").toIntOrNull() ?: run {
                        out.text("usage: :name-expr <name> [#n]  —  #n from :history")
                        return
                    }
                    log.get(number) ?: run {
                        out.text("nothing recorded as #$number — see :history")
                        return
                    }
                }
                val before = names.set(name, expression)
                state.reportStorageProblems(out)
                out.text(
                    when (before) {
                        null -> "$name  $expression"
                        expression -> "$name already names $expression"
                        else -> "$name  $expression  (was $before)"
                    },
                )
            }
            else -> out.text("usage: :name-expr [name [#n]]")
        }
    }

    private fun unname(argument: String, out: Output) {
        if (argument.isBlank()) {
            out.text("usage: :unname-expr <name>")
            return
        }
        val before = names.remove(argument)
        state.reportStorageProblems(out)
        out.text(if (before == null) "no name $argument — :name-expr lists them" else "forgot $argument  ($before)")
    }

    /** @param argument recorded expressions by number or name: `#3 family`, `3, 5`. */
    private fun view(argument: String, out: Output) {
        val refs = argument.split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
        if (refs.isEmpty()) {
            out.text("usage: :view #n|name|@view …  —  numbers from :history, names from :name-expr, views from :name-view")
            return
        }
        state.reportStorageProblems(out)
        val picked = columns(refs, out) ?: return
        val table = when (val result = state.session.viewBuilder.build(picked)) {
            is ViewBuilder.Result.Refused -> {
                out.error("${result.message}")
                return
            }
            is ViewBuilder.Result.Table -> result
        }
        state.lastView = picked
        out.table(
            header = listOf(
                Output.line {
                    +"${table.rows.size} ${table.type} row(s)"
                    if (table.errors > 0) error("; ${table.errors} error(s): ${table.firstError}")
                },
            ),
            titles = table.columns.map { it.title(table.type) },
            rows = table.rows.map { row ->
                Output.Table.Row(
                    state.shown(row.resource),
                    row.cells,
                    errors = row.failed,
                    wholeLabel = row.resource.label,
                )
            },
        )
    }

    /**
     * The columns [refs] ask for, as `:view` reads them: `#3` from `:history`, a name
     * from `:name-expr`, `@view` for a saved view's. Null, having said why, when one
     * of them is not there. Also what `:tree-view` reads its columns with.
     */
    fun columns(refs: List<String>, out: Output): List<ViewBuilder.Column>? = refs.flatMap { ref ->
        if (ref.startsWith("@")) {
            return@flatMap savedColumns(ref.drop(1)) ?: run {
                out.text("no saved view ${ref.drop(1)} — :name-view lists them")
                return null
            }
        }
        listOf(column(ref, out) ?: return null)
    }

    /** One recorded expression, by `:history` number or name; null, having said why, when there is none. */
    fun column(ref: String, out: Output): ViewBuilder.Column? {
        val number = ref.removePrefix("#").toIntOrNull()
        if (number != null) {
            val expression = log.get(number) ?: run {
                out.text("nothing recorded as #$number — see :history")
                return null
            }
            return ViewBuilder.Column(expression, number = number)
        }
        val expression = names.get(ref) ?: run {
            out.text("no :history number or name $ref — see :history and :name-expr")
            return null
        }
        return ViewBuilder.Column(expression, name = ref)
    }

    /**
     * A saved view's columns as they stand now. A named column follows its name while
     * the name exists, so its title and what it computes cannot disagree; once the
     * name is gone it keeps the expression it was saved with. Every other column is
     * renumbered against the log: `#3` in a title must be its number today, and one
     * no longer in the log has no number rather than a wrong one.
     */
    private fun savedColumns(view: String): List<ViewBuilder.Column>? {
        val saved = views.get(view) ?: return null
        val entries = log.entries()
        val named = names.all()
        return saved.map { column ->
            column.name?.let { name -> named[name]?.let { return@map ViewBuilder.Column(it, name = name) } }
            val number = entries.indexOf(column.expression) + 1
            ViewBuilder.Column(column.expression, number = number.takeIf { it > 0 })
        }
    }

    /** `:name-view` lists, `:name-view patients` saves the last `:view` under that name. */
    private fun saveView(argument: String, out: Output) {
        state.reportStorageProblems(out)
        if (argument.isBlank()) {
            val all = views.all()
            if (all.isEmpty()) {
                out.text("no saved views — :name-view <name> after a :view saves it")
                return
            }
            val width = all.keys.maxOf { it.length } + 1
            out.listing(
                header = emptyList(),
                rows = all.map { (name, columns) ->
                    Output.line {
                        +"  "
                        label("@$name".padEnd(width))
                        +"  "
                        shrink(columns.joinToString("  ") { it.ref })
                    }
                },
                full = all.flatMap { (name, columns) ->
                    listOf(Output.line { +"  "; label("@$name") }) + columns.map { Output.text("      ${it.described}") }
                },
            )
            return
        }
        // `@people` is how a view is written everywhere else, so it is taken here too.
        val name = argument.removePrefix("@")
        if (!KeyedStore.valid(name)) {
            out.text("not a valid view name: $name — ${KeyedStore.RULE}")
            return
        }
        val columns = state.lastView ?: run {
            out.text("nothing to save — run a :view or a :tree-view with columns first")
            return
        }
        val before = views.set(name, columns)
        state.reportStorageProblems(out)
        val replaced = if (before != null && before != columns) "  (replaced ${before.size} column(s))" else ""
        out.text("saved @$name: ${columns.size} column(s) — :view @$name reruns it$replaced")
    }

    private fun unsaveView(argument: String, out: Output) {
        val name = argument.removePrefix("@")
        if (name.isBlank()) {
            out.text("usage: :unname-view <view>")
            return
        }
        val before = views.remove(name)
        state.reportStorageProblems(out)
        out.text(if (before == null) "no saved view $name — :name-view lists them" else "forgot @$name")
    }
}
