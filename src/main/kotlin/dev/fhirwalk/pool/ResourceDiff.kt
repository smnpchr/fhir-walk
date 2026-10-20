package dev.fhirwalk.pool

import ca.uhn.fhir.context.BaseRuntimeElementCompositeDefinition
import ca.uhn.fhir.context.FhirContext
import org.hl7.fhir.instance.model.api.IBase
import org.hl7.fhir.instance.model.api.IBaseExtension
import org.hl7.fhir.instance.model.api.IBaseHasExtensions
import org.hl7.fhir.instance.model.api.IBaseHasModifierExtensions
import org.hl7.fhir.instance.model.api.IBaseResource
import org.hl7.fhir.instance.model.api.IPrimitiveType

/**
 * What changed between two versions of a resource, element by element: the paths
 * whose values were added, removed or changed, and those holding the same values in
 * another order.
 *
 * Paths are in the shape the user types and the census records -- base names for
 * choices, `extension('url')` for extensions -- so a path here can be pasted into an
 * expression. Values are compared exactly, as their text.
 *
 * Order is part of the content, and a reorder is told apart from a change because it
 * is so often the only one: an exporter that writes extensions in no fixed order
 * makes a commit whose resource reads differently and means the same. Values in
 * another order are one kind of reorder; extensions are the other, since each URL
 * has its own path, and swapping two leaves every path's values as they were. So the
 * order of the extension URLs on each element is compared too.
 *
 * Uses only `ca.uhn.fhir.context` runtime definitions, so it works unchanged on any
 * FHIR version.
 */
class ResourceDiff(private val ctx: FhirContext) {

    enum class Kind { ADDED, REMOVED, CHANGED, REORDERED }

    /**
     * @param path from the resource's type: `Specimen.type.coding.code`. For a
     *   [Kind.REORDERED] that came from the extensions, the element they are on,
     *   followed by `.extension`.
     */
    data class Difference(val path: String, val kind: Kind)

    /** What [after] changed from [before], in the order its paths first occur. */
    fun between(before: IBaseResource, after: IBaseResource): List<Difference> {
        val old = Flat().also { it.walk(before, typeOf(before), 0) }
        val new = Flat().also { it.walk(after, typeOf(after), 0) }
        val values = (new.values.keys + old.values.keys).mapNotNull { path ->
            val was = old.values[path].orEmpty()
            val now = new.values[path].orEmpty()
            when {
                was == now -> null
                was.isEmpty() -> Difference(path, Kind.ADDED)
                now.isEmpty() -> Difference(path, Kind.REMOVED)
                was.sorted() == now.sorted() -> Difference(path, Kind.REORDERED)
                else -> Difference(path, Kind.CHANGED)
            }
        }
        // A URL that came or went is a change of its own paths above; here only the
        // same URLs in another order.
        val extensions = (new.extensions.keys + old.extensions.keys).mapNotNull { path ->
            val was = old.extensions[path].orEmpty()
            val now = new.extensions[path].orEmpty()
            if (was != now && was.sorted() == now.sorted()) Difference("$path.extension", Kind.REORDERED) else null
        }
        return values + extensions
    }

    /** One version, flattened: each path's values, and each element's extension URLs, in order. */
    private inner class Flat {
        val values = linkedMapOf<String, MutableList<String>>()
        val extensions = linkedMapOf<String, MutableList<String>>()

        fun walk(element: IBase, path: String, depth: Int) {
            if (depth > MAX_DEPTH) return
            if (element is IPrimitiveType<*>) {
                element.valueAsString?.let { values.getOrPut(path) { mutableListOf() } += it }
            }
            for (ext in extensionsOf(element)) {
                val url = ext.url ?: continue
                extensions.getOrPut(path) { mutableListOf() } += url
                walk(ext, "$path.extension('$url')", depth + 1)
            }
            val def = ctx.getElementDefinition(element.javaClass) as? BaseRuntimeElementCompositeDefinition<*> ?: return
            for (child in def.children) {
                if (child.elementName in EXTENSION_CHILDREN) continue
                // `url` is in the path already, as extension('url').
                if (element is IBaseExtension<*, *> && child.elementName == "url") continue
                val children = runCatching { child.accessor.getValues(element) }.getOrNull() ?: continue
                children.filterNotNull().forEach { walk(it, "$path.${child.elementName}", depth + 1) }
            }
        }
    }

    private fun typeOf(resource: IBaseResource): String = ctx.getResourceType(resource)

    @Suppress("UNCHECKED_CAST")
    private fun extensionsOf(element: IBase): List<IBaseExtension<*, *>> {
        val direct = (element as? IBaseHasExtensions)?.extension.orEmpty()
        val modifier = (element as? IBaseHasModifierExtensions)?.modifierExtension.orEmpty()
        return (direct + modifier) as List<IBaseExtension<*, *>>
    }

    private companion object {
        /** As deep as the census walks. */
        const val MAX_DEPTH = 15
        val EXTENSION_CHILDREN = setOf("extension", "modifierExtension")
    }
}
