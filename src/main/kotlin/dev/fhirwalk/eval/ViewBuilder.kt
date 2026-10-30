package dev.fhirwalk.eval

import dev.fhirwalk.model.ModelNavigator
import dev.fhirwalk.pool.PooledResource
import dev.fhirwalk.pool.ResourcePool
import org.hl7.fhir.instance.model.api.IBase
import org.hl7.fhir.instance.model.api.IPrimitiveType

/**
 * A table over the pool: one row per resource of a type, one column per expression.
 *
 * Every column must start from the same resource type, which is what decides the
 * rows, and must yield primitives only. A cell holds text, and a primitive is the
 * one kind of value that is text without a choice about how to flatten it -- a
 * HumanName could be rendered a dozen ways, `name.family` only one. Several values
 * in one cell are joined with ", ".
 *
 * Every resource of the type gets a row, including one where every cell is empty:
 * which resources lack a value is as much the answer as which have one.
 */
class ViewBuilder(
    private val evaluator: Evaluator,
    private val pool: ResourcePool,
    private val navigator: ModelNavigator,
    private val typeOf: (IBase) -> String,
) {

    /**
     * One column, and how it was asked for -- which is what titles it.
     *
     * @param number its number in the expression log, if it was asked for by one or
     *   has one now; null for a saved view's column whose expression is no longer in
     *   the log.
     * @param name the name it was asked for by, which then is its title.
     */
    data class Column(val expression: String, val number: Int? = null, val name: String? = null) {
        /** How a message refers to it: the name, else the number, else the expression. */
        val ref: String get() = name ?: number?.let { "#$it" } ?: expression

        /**
         * A name is its own title. Otherwise the number, if any, and the expression
         * without its resource-type root, which every column shares.
         */
        fun title(type: String): String =
            name ?: listOfNotNull(number?.let { "#$it" }, expression.removePrefix("$type.").ifBlank { expression })
                .joinToString(" ")

        /** For messages: the reference with the expression it stands for. */
        val described: String
            get() = when {
                name != null -> "$name ($expression)"
                number != null -> "#$number $expression"
                else -> expression
            }
    }

    /** @param failed the columns whose expression failed here, shown as [ERROR_CELL]. */
    data class Row(val resource: PooledResource, val cells: List<String>, val failed: Set<Int> = emptySet())

    sealed interface Result {
        data class Table(
            val type: String,
            val columns: List<Column>,
            val rows: List<Row>,
            val errors: Int,
            val firstError: String?,
        ) : Result

        data class Refused(val message: String) : Result
    }

    /**
     * @param only the resources to make rows of, when not every one in scope of the
     *   columns' type: a tree's, which may reach outside the scope. Those of another
     *   type are left out, and none of the type is no refusal but no rows.
     */
    fun build(columns: List<Column>, only: List<PooledResource>? = null): Result {
        if (columns.isEmpty()) return Result.Refused("a view needs at least one column")
        columns.forEach { column ->
            evaluator.validate(column.expression)?.let { return Result.Refused("${column.described}: $it") }
        }

        val roots = columns.map { it to rootType(it.expression) }
        roots.firstOrNull { it.second == null }?.let { (column, _) ->
            return Result.Refused(
                "${column.described} does not start from a resource type — " +
                    "a view needs one to know what its rows are",
            )
        }
        val types = roots.mapNotNull { it.second }.distinct()
        if (types.size > 1) {
            val spread = roots.joinToString(", ") { (column, type) -> "${column.ref} $type" }
            return Result.Refused("columns start from different types ($spread) — a view has one row per resource of one type")
        }
        val type = types.single()
        val resources = only?.filter { it.type.equals(type, ignoreCase = true) } ?: pool.inScope(type)
        if (resources.isEmpty() && only == null) {
            if (pool.scope != null && pool.ofType(type).isNotEmpty()) {
                return Result.Refused("no $type in the scope — :scope all for the whole pool")
            }
            return Result.Refused("no $type in the pool — it holds ${pool.types().joinToString(", ")}")
        }

        var errors = 0
        var firstError: String? = null
        // Column by column, so a structured value is refused before the columns after
        // it have run -- resolve() across a large pool is not free. Refused as a whole
        // rather than cell by cell: a column that is primitive in some rows and
        // structured in others would read as missing data.
        val cellsByColumn = columns.map { column ->
            resources.map { resource ->
                when (val outcome = evaluator.evaluate(resource.resource, column.expression)) {
                    is Outcome.Values -> {
                        outcome.values.firstOrNull { it !is IPrimitiveType<*> }?.let { structured ->
                            return Result.Refused(
                                "${column.described} yields ${typeOf(structured)}, " +
                                    "not a primitive — only primitive values fit a column; navigate one level further",
                            )
                        }
                        cell(outcome.values)
                    }
                    // Null until the rows are made, so a value that reads "!" is not taken for one.
                    is Outcome.Failure -> {
                        errors++
                        if (firstError == null) firstError = "${column.described}: ${outcome.message}"
                        null
                    }
                }
            }
        }

        val rows = resources.mapIndexed { idx, resource ->
            Row(resource, cellsByColumn.map { it[idx] ?: ERROR_CELL }, cellsByColumn.indices.filter { cellsByColumn[it][idx] == null }.toSet())
        }
        return Result.Table(type, columns, rows, errors, firstError)
    }

    /**
     * One line, whatever the values hold: a narrative `div` or a note with line breaks
     * would otherwise spread one row over several and break the column alignment.
     */
    private fun cell(values: List<IBase>): String =
        values.joinToString(", ") { (it as IPrimitiveType<*>).valueAsString.orEmpty() }
            .replace(WHITESPACE, " ").trim()

    private fun rootType(expression: String): String? =
        FhirPathTokenizer.segments(expression).firstOrNull()?.name
            ?.takeIf { it in navigator.resourceTypes() }

    companion object {
        /** What a cell shows when its expression failed against that resource. */
        const val ERROR_CELL = "!"

        private val WHITESPACE = Regex("\\s+")
    }
}
