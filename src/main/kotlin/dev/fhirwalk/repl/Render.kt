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

    fun value(value: IBase, width: Int = 96): String {
        val text = when {
            value is IPrimitiveType<*> -> value.valueAsString ?: "(null)"
            value is IBaseResource -> runCatching { json.encodeResourceToString(value) }.getOrDefault("<resource>")
            else -> runCatching { json.encodeToString(value) }.getOrElse { describe(value) }
        }
        val flat = text.replace(Regex("\\s+"), " ").trim()
        return if (flat.length <= width) flat else flat.take(width - 1) + "…"
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
