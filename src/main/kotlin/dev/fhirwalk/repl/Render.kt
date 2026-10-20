package dev.fhirwalk.repl

import ca.uhn.fhir.context.FhirContext
import org.hl7.fhir.instance.model.api.IBase
import org.hl7.fhir.instance.model.api.IBaseResource
import org.hl7.fhir.instance.model.api.IPrimitiveType

/**
 * Compact one-line rendering of evaluation results.
 *
 * Unbounded output is what makes a REPL unpleasant, so everything here truncates and
 * the caller decides how many rows to print.
 */
class Render(private val ctx: FhirContext) {

    private val json by lazy { ctx.newJsonParser() }

    fun typeOf(value: IBase): String =
        runCatching { ctx.getElementDefinition(value.javaClass)?.name }.getOrNull()
            ?: value.javaClass.simpleName

    /**
     * @param width the most characters to print, or 0 for no limit. The limit exists
     *   so a listing fits the window; `:more` reprints the same rows with 0 so a
     *   value is never shortened beyond recovery.
     */
    fun value(value: IBase, width: Int = 0): String {
        val text = when {
            value is IPrimitiveType<*> -> value.valueAsString ?: "(null)"
            value is IBaseResource -> runCatching { json.encodeResourceToString(value) }.getOrDefault("<resource>")
            else -> runCatching { json.encodeToString(value) }.getOrElse { describe(value) }
        }
        // Onto one line, and no more: compact JSON has no whitespace outside its
        // strings, and the spaces inside them are part of the value.
        val flat = text.replace(Regex("\\s*\\R\\s*"), " ").trim()
        return if (width <= 0 || flat.length <= width) flat else flat.take(width - 1) + "…"
    }

    /**
     * The expanded rendering, as one or more lines: a primitive stays a single line,
     * anything structured becomes pretty-printed JSON.
     *
     * Previews have to stay one row per result to remain tabular, so indentation is
     * only ever worth it once a value is shown in full.
     */
    fun pretty(value: IBase): List<String> {
        val text = when {
            value is IPrimitiveType<*> -> return listOf(value.valueAsString ?: "(null)")
            value is IBaseResource ->
                runCatching { json.encodeResourceToString(value) }.getOrDefault("<resource>")
            else -> runCatching { json.encodeToString(value) }.getOrElse { return listOf(describe(value)) }
        }
        return indentJson(text)
    }

    /**
     * Indents compact JSON.
     *
     * HAPI's `setPrettyPrint(true)` is not honoured by `encodeToString` for datatype
     * elements -- only for whole resources -- and most values here are datatypes, so
     * formatting the compact output is the only way to get this consistently.
     */
    private fun indentJson(text: String): List<String> {
        val trimmed = text.trim()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return listOf(trimmed)

        val lines = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        var inString = false
        var escaped = false

        fun breakLine() {
            if (current.isNotEmpty()) {
                lines += current.toString()
                current.clear()
            }
        }

        fun startLine() {
            if (current.isEmpty()) current.append("  ".repeat(depth))
        }

        for (char in trimmed) {
            if (inString) {
                current.append(char)
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
                continue
            }
            when (char) {
                '"' -> {
                    startLine()
                    inString = true
                    current.append(char)
                }
                '{', '[' -> {
                    startLine()
                    current.append(char)
                    breakLine()
                    depth++
                }
                '}', ']' -> {
                    breakLine()
                    depth--
                    startLine()
                    current.append(char)
                }
                ',' -> {
                    current.append(char)
                    breakLine()
                }
                ':' -> current.append(": ")
                ' ', '\t', '\r', '\n' -> Unit
                else -> {
                    startLine()
                    current.append(char)
                }
            }
        }
        breakLine()
        return lines
    }

    /** Fallback for element types the parser will not encode standalone. */
    private fun describe(value: IBase): String = buildString {
        append(typeOf(value))
        val composite = ctx.getElementDefinition(value.javaClass) as? ca.uhn.fhir.context.BaseRuntimeElementCompositeDefinition<*>
            ?: return@buildString
        val parts = composite.children.asSequence()
            .mapNotNull { child ->
                val values = runCatching { child.accessor.getValues(value) }.getOrNull().orEmpty()
                val first = values.firstOrNull() as? IPrimitiveType<*> ?: return@mapNotNull null
                "${child.elementName}=${first.valueAsString}"
            }
            .take(4)
            .toList()
        if (parts.isNotEmpty()) append(parts.joinToString(", ", prefix = "{", postfix = "}"))
    }
}
