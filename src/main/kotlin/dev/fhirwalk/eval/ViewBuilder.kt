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
     * @param ref how the column was asked for: `#3`, a number in the expression log;
     *   a name; or empty, for a saved view's column whose expression is no longer in
     *   the log under any number.
     */
    data class Column(val ref: String, val expression: String) {
        private val named: Boolean get() = ref.isNotEmpty() && !ref.startsWith("#")

        /**
         * A name is its own title. A number is followed by the expression without its
         * resource-type root, which every column shares.
         */
        fun title(type: String): String =
            if (named) ref else listOf(ref, expression.removePrefix("$type.").ifBlank { expression }).joinToString(" ").trim()

        /** For messages: the reference with the expression it stands for. */
        val described: String get() = if (named) "$ref ($expression)" else "$ref $expression".trim()
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
            val spread = roots.joinToString(", ") { (column, type) -> "${column.ref.ifEmpty { column.expression }} $type" }
            return Result.Refused("columns start from different types ($spread) — a view has one row per resource of one type")
        }
        val type = types.single()
        val resources = pool.ofType(type)
        if (resources.isEmpty()) {
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
                    is Outcome.Failure -> {
                        errors++
                        if (firstError == null) firstError = "${column.described}: ${outcome.message}"
                        ERROR_CELL
                    }
                }
            }
        }

        val rows = resources.mapIndexed { idx, resource -> Row(resource, cellsByColumn.map { it[idx] }) }
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
