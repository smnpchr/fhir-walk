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

    /** @param number the column's number in the expression log, for messages and titles. */
    data class Column(val number: Int, val expression: String) {
        /** The expression without its resource-type root, which every column shares. */
        fun title(type: String): String = expression.removePrefix("$type.").ifBlank { expression }
    }

    data class Row(val resource: PooledResource, val cells: List<String>)

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

    fun build(columns: List<Column>): Result {
        if (columns.isEmpty()) return Result.Refused("a view needs at least one column")
        columns.forEach { column ->
            evaluator.validate(column.expression)?.let { return Result.Refused("#${column.number}: $it") }
        }

        val roots = columns.map { it to rootType(it.expression) }
        roots.firstOrNull { it.second == null }?.let { (column, _) ->
            return Result.Refused(
                "#${column.number} ${column.expression} does not start from a resource type — " +
                    "a view needs one to know what its rows are",
            )
        }
        val types = roots.mapNotNull { it.second }.distinct()
        if (types.size > 1) {
            val spread = roots.joinToString(", ") { (column, type) -> "#${column.number} $type" }
            return Result.Refused("columns start from different types ($spread) — a view has one row per resource of one type")
        }
        val type = types.single()
        val resources = pool.ofType(type)
        if (resources.isEmpty()) {
            return Result.Refused("no $type in the pool — it holds ${pool.types().joinToString(", ")}")
        }

        var errors = 0
        var firstError: String? = null
        val values = resources.map { resource ->
            columns.map { column ->
                when (val outcome = evaluator.evaluate(resource.resource, column.expression)) {
                    is Outcome.Values -> outcome.values
                    is Outcome.Failure -> {
                        errors++
                        if (firstError == null) firstError = "#${column.number}: ${outcome.message}"
                        null
                    }
                }
            }
        }

        // Refused as a whole rather than cell by cell: a column that is primitive in
        // some rows and structured in others would read as missing data.
        columns.forEachIndexed { idx, column ->
            val structured = values.firstNotNullOfOrNull { row -> row[idx]?.firstOrNull { it !is IPrimitiveType<*> } }
            if (structured != null) {
                return Result.Refused(
                    "#${column.number} ${column.expression} yields ${typeOf(structured)}, not a primitive — " +
                        "only primitive values fit a column; navigate one level further",
                )
            }
        }

        val rows = resources.zip(values).map { (resource, cells) ->
            Row(
                resource,
                cells.map { cell ->
                    cell?.joinToString(", ") { (it as IPrimitiveType<*>).valueAsString.orEmpty() } ?: ERROR_CELL
                },
            )
        }
        return Result.Table(type, columns, rows, errors, firstError)
    }

    private fun rootType(expression: String): String? =
        FhirPathTokenizer.segments(expression).firstOrNull()?.name
            ?.takeIf { it in navigator.resourceTypes() }

    companion object {
        /** What a cell shows when its expression failed against that resource. */
        const val ERROR_CELL = "!"
    }
}
