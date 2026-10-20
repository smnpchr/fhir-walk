package dev.fhirwalk.pool

import ca.uhn.fhir.context.BaseRuntimeElementCompositeDefinition
import ca.uhn.fhir.context.FhirContext
import org.hl7.fhir.instance.model.api.IBase
import org.hl7.fhir.instance.model.api.IBaseExtension
import org.hl7.fhir.instance.model.api.IBaseHasExtensions
import org.hl7.fhir.instance.model.api.IBaseHasModifierExtensions
import org.hl7.fhir.instance.model.api.IBaseResource
import org.hl7.fhir.instance.model.api.IIdType
import org.hl7.fhir.instance.model.api.IPrimitiveType

/**
 * What changed between two versions of a resource, element by element: the paths
 * where something was added, removed or changed, and those holding the same
 * repetitions in another order.
 *
 * Paths are in the shape the user types -- base names for choices,
 * `extension('url')` for extensions -- so a path here can be pasted into an
 * expression. A modifier extension is `modifierExtension.where(url='…')`, kept
 * apart from an extension with the same URL.
 *
 * The two versions are compared as trees, each repetition in its place, because a
 * value only means something with the repetition it is in: two codings whose codes
 * were swapped are a recoding, two codings swapped whole are a reorder. So at each
 * repeating element the repetitions are compared whole. The same ones in another
 * order are a reorder there; otherwise those still identical are set aside and the
 * rest compared pair by pair, by position, the extra ones being added or removed.
 * Extensions are told apart by URL, so their order is compared on its own: swapping
 * two leaves each URL's extensions as they were.
 *
 * A reorder is told apart from a change because it is so often the only one: an
 * exporter that writes extensions in no fixed order makes a commit whose resource
 * reads differently and means the same.
 *
 * A value is compared with its type, so `valueString` becoming `valueCode` with the
 * same text is a change, and a resource's id without the version HAPI keeps in it.
 *
 * Uses only `ca.uhn.fhir.context` runtime definitions, so it works unchanged on any
 * FHIR version.
 */
class ResourceDiff(private val ctx: FhirContext) {

    enum class Kind { ADDED, REMOVED, CHANGED, REORDERED }

    /**
     * @param path from the resource's type: `Specimen.type.coding.code`. For a
     *   [Kind.REORDERED] of extensions, the element they are on followed by
     *   `.extension`.
     */
    data class Difference(val path: String, val kind: Kind)

    /** What [after] changed from [before], in the order a walk of the two meets them; each once. */
    fun between(before: IBaseResource, after: IBaseResource): List<Difference> {
        val found = linkedSetOf<Difference>()
        compare(ctx.getResourceType(after), node(before), node(after), found)
        return found.toList()
    }

    /**
     * One element: its [type], its [value] if it is a primitive, its children by the
     * step that reaches them, and the steps of its extensions in the order written.
     * Equal when the whole of it is, which is what a repetition is matched by.
     */
    private class Node(val type: String, val value: String?, val children: Map<String, List<Node>>, val extensionOrder: List<String>) {
        private val hash by lazy { listOf(type, value, children, extensionOrder).hashCode() }

        override fun hashCode(): Int = hash

        override fun equals(other: Any?): Boolean =
            other is Node && other.hash == hash && other.type == type && other.value == value &&
                other.extensionOrder == extensionOrder && other.children == children
    }

    private fun node(element: IBase, isResource: Boolean = true): Node {
        val children = linkedMapOf<String, MutableList<Node>>()
        val extensionOrder = mutableListOf<String>()
        for ((step, ext) in extensionsOf(element)) {
            extensionOrder += step
            children.getOrPut(step) { mutableListOf() } += node(ext, isResource = false)
        }
        val def = ctx.getElementDefinition(element.javaClass) as? BaseRuntimeElementCompositeDefinition<*>
        for (child in def?.children.orEmpty()) {
            if (child.elementName in EXTENSION_CHILDREN) continue
            // `url` is in the step already, as extension('url').
            if (element is IBaseExtension<*, *> && child.elementName == "url") continue
            val values = runCatching { child.accessor.getValues(element) }.getOrNull().orEmpty().filterNotNull()
            for (value in values) {
                // HAPI keeps the version in a resource's id, where FHIRPath's id has none.
                val node = if (isResource && child.elementName == "id" && value is IIdType) {
                    Node(typeOf(value), value.idPart, emptyMap(), emptyList())
                } else {
                    node(value, isResource = value is IBaseResource)
                }
                children.getOrPut(child.elementName) { mutableListOf() } += node
            }
        }
        val value = (element as? IPrimitiveType<*>)?.valueAsString
        return Node(typeOf(element), value, children, extensionOrder)
    }

    private fun compare(path: String, before: Node, after: Node, found: MutableSet<Difference>) {
        if (before == after) return
        // Another type is another thing: a choice that became another type, a
        // contained resource that became another kind. What is under it is not
        // the same element's.
        if (before.type != after.type) {
            found += Difference(path, Kind.CHANGED)
            return
        }
        if (before.value != after.value) found += Difference(path, Kind.CHANGED)
        if (before.extensionOrder != after.extensionOrder && before.extensionOrder.sorted() == after.extensionOrder.sorted()) {
            found += Difference("$path.extension", Kind.REORDERED)
        }
        for (step in (after.children.keys + before.children.keys)) {
            compareRepetitions("$path.$step", before.children[step].orEmpty(), after.children[step].orEmpty(), found)
        }
    }

    private fun compareRepetitions(path: String, before: List<Node>, after: List<Node>, found: MutableSet<Difference>) {
        when {
            before == after -> return
            before.isEmpty() -> found += Difference(path, Kind.ADDED)
            after.isEmpty() -> found += Difference(path, Kind.REMOVED)
            counts(before) == counts(after) -> found += Difference(path, Kind.REORDERED)
            else -> {
                // Set aside what is still there whole, then compare the rest in place.
                val unmatched = counts(after).toMutableMap()
                val left = before.filter { node ->
                    val count = unmatched[node] ?: 0
                    if (count > 0) unmatched[node] = count - 1
                    count == 0
                }
                val kept = counts(before).toMutableMap()
                val right = after.filter { node ->
                    val count = kept[node] ?: 0
                    if (count > 0) kept[node] = count - 1
                    count == 0
                }
                left.zip(right).forEach { (was, now) -> compare(path, was, now, found) }
                if (right.size > left.size) found += Difference(path, Kind.ADDED)
                if (left.size > right.size) found += Difference(path, Kind.REMOVED)
            }
        }
    }

    private fun counts(nodes: List<Node>): Map<Node, Int> = nodes.groupingBy { it }.eachCount()

    private fun typeOf(element: IBase): String =
        runCatching { ctx.getElementDefinition(element.javaClass)?.name }.getOrNull() ?: element.javaClass.simpleName

    /** Each extension with the step that reaches it, in the order written: extensions first, then modifier ones. */
    @Suppress("UNCHECKED_CAST")
    private fun extensionsOf(element: IBase): List<Pair<String, IBaseExtension<*, *>>> {
        val direct = (element as? IBaseHasExtensions)?.extension.orEmpty() as List<IBaseExtension<*, *>>
        val modifier = (element as? IBaseHasModifierExtensions)?.modifierExtension.orEmpty() as List<IBaseExtension<*, *>>
        return direct.map { ext -> (ext.url?.let { "extension('$it')" } ?: "extension") to ext } +
            modifier.map { ext -> (ext.url?.let { "modifierExtension.where(url='$it')" } ?: "modifierExtension") to ext }
    }

    private companion object {
        val EXTENSION_CHILDREN = setOf("extension", "modifierExtension")
    }
}
