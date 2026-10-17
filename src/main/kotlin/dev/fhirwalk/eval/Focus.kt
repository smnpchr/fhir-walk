package dev.fhirwalk.eval

import dev.fhirwalk.model.ModelNavigator
import dev.fhirwalk.pool.PooledResource
import org.hl7.fhir.instance.model.api.IBase

/**
 * What a bare expression runs against: a whole resource from the pool, or one
 * element inside it.
 *
 * Focusing by expression drills down from the current focus, so each focus keeps the
 * one it was reached from in [parent] and `:focus ..` can step back up.
 *
 * @param node the value expressions are evaluated against; for a resource focus,
 *   the resource itself.
 * @param typeName the FHIR type of [node], e.g. `Observation` or `CodeableConcept`.
 * @param path where [node] sits, rooted at the resource type and spelled the way
 *   completion walks it (`Observation.component.code`), so census lookups match
 *   with no rewriting. When the expression that reached the node is not a path, this
 *   falls back to [typeName]: the schema survives, the corpus counts do not.
 * @param label how the focus shows in the prompt.
 */
class Focus(
    val resource: PooledResource,
    val node: IBase,
    val typeName: String,
    val path: String,
    val label: String,
    val parent: Focus?,
) {
    val isResource: Boolean get() = node === resource.resource

    /**
     * [expression] with any leading `$this` or type-name step removed, since both
     * name the focus itself: against a CodeableConcept, `CodeableConcept.coding` and
     * `coding` are the same expression.
     */
    fun step(expression: String): String {
        val segments = FhirPathTokenizer.segments(expression)
        val first = segments.firstOrNull() ?: return expression
        if (first.isCall || first.text.contains('[')) return expression.trim()
        if (first.name != "\$this" && first.name != typeName) return expression.trim()
        return expression.substring((segments.getOrNull(1)?.start ?: expression.length)).trim()
    }

    /**
     * The resource-rooted path completion should walk for [expression] typed
     * against this focus. A step that starts with a type name or `%variable` is
     * left alone: it does not navigate from the focus.
     */
    fun absolute(expression: String): String {
        val relative = step(expression)
        if (relative.isEmpty()) return path
        if (relative.first().isUpperCase() || relative.startsWith("%")) return relative
        return "$path.$relative"
    }

    companion object {
        fun of(resource: PooledResource, parent: Focus? = null) =
            Focus(resource, resource.resource, resource.type, resource.type, resource.label, parent)

        /**
         * The element [expression] reached, from [parent] or, with none, across the
         * pool (where [owner] is the resource it was found in).
         *
         * The completion path is only kept when the navigator agrees on its type: an
         * expression that is not a path (`a | b`), or a choice the census cannot
         * type, would otherwise complete against the wrong thing. A choice gets one
         * more try narrowed with `ofType()`, which keeps its census spelling.
         */
        fun reached(
            owner: PooledResource,
            node: IBase,
            typeName: String,
            expression: String,
            parent: Focus?,
            navigator: ModelNavigator,
        ): Focus {
            val step = (parent ?: of(owner)).step(expression)
            val from = parent?.path ?: owner.type
            val claimed = if (step.isEmpty()) from else "$from.$step"
            // ofType() types whatever precedes it, so it would vouch for a non-path.
            val pathShaped = FhirPathTokenizer.segments(step).all { IDENTIFIER.matches(it.name) }
            val path = listOf(claimed, "$claimed.ofType($typeName)")
                .takeIf { pathShaped }
                ?.firstOrNull { navigator.resolveType(it)?.name.equals(typeName, ignoreCase = true) }
                ?: typeName
            val base = parent?.label ?: owner.label
            val label = if (step.isEmpty()) base else "$base.$step"
            return Focus(owner, node, typeName, path, label, parent)
        }

        private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")
    }
}
