package dev.fhirwalk.pool

import ca.uhn.fhir.context.BaseRuntimeElementCompositeDefinition
import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.context.RuntimeChildChoiceDefinition
import org.hl7.fhir.instance.model.api.IBase
import org.hl7.fhir.instance.model.api.IBaseCoding
import org.hl7.fhir.instance.model.api.IBaseExtension
import org.hl7.fhir.instance.model.api.IBaseHasExtensions
import org.hl7.fhir.instance.model.api.IBaseHasModifierExtensions
import org.hl7.fhir.instance.model.api.IBaseReference
import org.hl7.fhir.instance.model.api.IBaseResource
import org.hl7.fhir.instance.model.api.IIdType
import org.hl7.fhir.instance.model.api.IPrimitiveType

/**
 * The corpus axis of completion: what is actually *present* in the imported data,
 * with occurrence counts.
 *
 * The schema axis (see `model.ModelNavigator`) can only tell you what is legal, and
 * for extension URLs it cannot even do that without the right IG package loaded. The
 * census answers from the data instead, which is both cheaper and more useful --
 * suggestions ranked by how often they really occur, and extension URLs scoped to
 * the element they were observed on.
 *
 * Paths are recorded in the same shape the user types, including
 * `extension('url')` steps, so a cursor context matches a census key directly.
 */
class Census {

    data class ExtKey(val path: String, val url: String)

    class Stat {
        var count: Int = 0
            private set
        val valueTypes: MutableSet<String> = linkedSetOf()

        fun record(valueType: String?) {
            count++
            valueType?.let { valueTypes += it }
        }
    }

    val extensions: MutableMap<ExtKey, Stat> = linkedMapOf()

    /** path -> referenced resource type -> count, from every Reference element. */
    val referenceTargets: MutableMap<String, MutableMap<String, Int>> = linkedMapOf()

    /**
     * References whose string names no type (`urn:uuid:…`, a bare id), keyed by the
     * path they sit on. Their target is usually not loaded yet when they are walked,
     * so the owning pool settles them into [referenceTargets] once an import is done
     * (see `ResourcePool.settleReferences`). Unresolved ones stay queued for a later
     * load to settle.
     */
    val pendingReferences: MutableList<Pair<String, IIdType>> = mutableListOf()

    /** path -> Coding.system -> count. */
    val codeSystems: MutableMap<String, MutableMap<String, Int>> = linkedMapOf()

    /**
     * path -> observed value -> count, for coded primitives only.
     *
     * The first axis whose size follows the data's content rather than the schema's
     * shape, hence two guards. Only `code` values are recorded: they are what a
     * comparison wants suggested, and leaving out strings, dates and ids keeps names,
     * birth dates and identifiers out of the completion menu altogether. And a path
     * past [MAX_VALUES_PER_PATH] distinct values moves to [highCardinality] and stops
     * being recorded -- no list that long helps anyone pick.
     */
    val values: MutableMap<String, MutableMap<String, Int>> = linkedMapOf()

    /** Paths whose values were dropped for exceeding [MAX_VALUES_PER_PATH]. */
    val highCardinality: MutableSet<String> = linkedSetOf()

    fun recordValue(path: String, value: String) {
        if (path in highCardinality) return
        val seen = values.getOrPut(path) { linkedMapOf() }
        seen.merge(value, 1, Int::plus)
        if (seen.size > MAX_VALUES_PER_PATH) {
            values.remove(path)
            highCardinality += path
        }
    }

    /** resource type -> meta.profile -> count. */
    val profiles: MutableMap<String, MutableMap<String, Int>> = linkedMapOf()

    /**
     * path -> concrete type observed for a choice (`[x]`) element -> count.
     *
     * The static model cannot say what `Observation.value` is; the data can. This is
     * the same schema/corpus composition that handles `resolve()`.
     */
    val choiceTypes: MutableMap<String, MutableMap<String, Int>> = linkedMapOf()

    /** How many times each element path was populated; drives frequency ranking. */
    val pathCounts: MutableMap<String, Int> = linkedMapOf()

    data class Suggestion(val value: String, val count: Int, val detail: String?)

    fun extensionsAt(path: String): List<Suggestion> =
        extensions.entries
            .filter { it.key.path == path }
            .map { Suggestion(it.key.url, it.value.count, it.value.valueTypes.joinToString("|").ifBlank { null }) }
            .sortedByDescending { it.count }

    /** Every observed URL regardless of context, for when the base path is unknown. */
    fun allExtensions(): List<Suggestion> =
        extensions.entries
            .groupBy { it.key.url }
            .map { (url, entries) ->
                Suggestion(
                    url,
                    entries.sumOf { it.value.count },
                    entries.joinToString(", ") { it.key.path }.take(60),
                )
            }
            .sortedByDescending { it.count }

    fun referenceTargetsAt(path: String): List<Suggestion> =
        referenceTargets[path].orEmpty().entries
            .map { Suggestion(it.key, it.value, null) }
            .sortedByDescending { it.count }

    fun valuesAt(path: String): List<Suggestion> =
        values[path].orEmpty().entries
            .map { Suggestion(it.key, it.value, null) }
            .sortedByDescending { it.count }

    fun codeSystemsAt(path: String): List<Suggestion> =
        codeSystems[path].orEmpty().entries
            .map { Suggestion(it.key, it.value, null) }
            .sortedByDescending { it.count }

    fun profilesFor(type: String): List<Suggestion> =
        profiles[type].orEmpty().entries
            .map { Suggestion(it.key, it.value, null) }
            .sortedByDescending { it.count }

    fun choiceTypesAt(path: String): List<Suggestion> =
        choiceTypes[path].orEmpty().entries
            .map { Suggestion(it.key, it.value, null) }
            .sortedByDescending { it.count }

    fun countAt(path: String): Int = pathCounts[path] ?: 0

    fun isEmpty(): Boolean = pathCounts.isEmpty()

    companion object {
        const val MAX_VALUES_PER_PATH = 50
    }

    fun clear() {
        extensions.clear()
        referenceTargets.clear()
        pendingReferences.clear()
        codeSystems.clear()
        values.clear()
        highCardinality.clear()
        profiles.clear()
        choiceTypes.clear()
        pathCounts.clear()
    }
}

/**
 * Walks a resource once at import time and folds its structure into a [Census].
 *
 * Uses only `ca.uhn.fhir.context` runtime definitions, so it works unchanged on any
 * FHIR version.
 */
class CensusWalker(private val ctx: FhirContext, private val census: Census) {

    fun visit(resource: IBaseResource, type: String) {
        resource.meta?.profile?.forEach { profile ->
            profile.valueAsString?.let { url ->
                census.profiles.getOrPut(type) { linkedMapOf() }.merge(url, 1, Int::plus)
            }
        }
        walk(resource, type, 0)
    }

    private fun walk(element: IBase, path: String, depth: Int) {
        if (depth > MAX_DEPTH) return
        census.pathCounts.merge(path, 1, Int::plus)
        recordLeafFacts(element, path)

        for (ext in extensionsOf(element)) {
            val url = ext.url ?: continue
            census.extensions
                .getOrPut(Census.ExtKey(path, url)) { Census.Stat() }
                .record(typeName(ext.value))
            // Recording nested extensions under the path the user would actually type
            // means the completer can match a cursor context without any rewriting.
            walk(ext, "$path.extension('$url')", depth + 1)
        }

        val def = ctx.getElementDefinition(element.javaClass) as? BaseRuntimeElementCompositeDefinition<*> ?: return
        for (child in def.children) {
            if (child.elementName in EXTENSION_CHILDREN) continue
            val values = runCatching { child.accessor.getValues(element) }.getOrNull() ?: continue
            // Only a real [x] choice, not a reference (which carries a second
            // synthetic valid name without being a choice).
            val isChoice = child is RuntimeChildChoiceDefinition
            for (value in values) {
                if (value == null) continue
                // Paths use the element's base name, because that is what FHIRPath
                // navigates: `Observation.value`, never `Observation.valueQuantity`.
                // The concrete type observed goes into the choice census instead.
                val childPath = "$path.${child.elementName}"
                if (isChoice) {
                    typeName(value)?.let { concrete ->
                        census.choiceTypes.getOrPut(childPath) { linkedMapOf() }.merge(concrete, 1, Int::plus)
                    }
                }
                walk(value, childPath, depth + 1)
            }
        }
    }

    private fun recordLeafFacts(element: IBase, path: String) {
        if (element is IBaseReference) {
            val reference = runCatching { element.referenceElement }.getOrNull()
            val target = reference?.resourceType?.takeIf { it.isNotBlank() } ?: declaredType(element)
            when {
                target != null -> census.referenceTargets.getOrPut(path) { linkedMapOf() }.merge(target, 1, Int::plus)
                !reference?.value.isNullOrBlank() -> census.pendingReferences += path to reference!!
            }
        }
        if (element is IPrimitiveType<*> && typeName(element) in VALUE_TYPES) {
            element.valueAsString?.takeIf { it.isNotBlank() }?.let { census.recordValue(path, it) }
        }
        if (element is IBaseCoding) {
            val system = runCatching { element.system }.getOrNull()
            if (!system.isNullOrBlank()) {
                census.codeSystems.getOrPut(path) { linkedMapOf() }.merge(system, 1, Int::plus)
            }
        }
    }

    /**
     * `Reference.type`, for references whose string does not name the type
     * (identifier-only, `urn:uuid:…`). `IBaseReference` does not expose it, so it is
     * read through the runtime child like any other element; versions without the
     * element (DSTU3) simply yield null. It may be a full StructureDefinition URL.
     */
    private fun declaredType(reference: IBaseReference): String? {
        val def = ctx.getElementDefinition(reference.javaClass) as? BaseRuntimeElementCompositeDefinition<*> ?: return null
        val child = def.getChildByName("type") ?: return null
        val value = runCatching { child.accessor.getFirstValueOrNull<IBase>(reference).orElse(null) }.getOrNull()
        return (value as? IPrimitiveType<*>)?.valueAsString?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun extensionsOf(element: IBase): List<IBaseExtension<*, *>> {
        val direct = (element as? IBaseHasExtensions)?.extension.orEmpty()
        val modifier = (element as? IBaseHasModifierExtensions)?.modifierExtension.orEmpty()
        return (direct + modifier) as List<IBaseExtension<*, *>>
    }

    private fun typeName(value: IBase?): String? {
        if (value == null) return null
        return runCatching { ctx.getElementDefinition(value.javaClass)?.name }.getOrNull()
            ?: value.javaClass.simpleName
    }

    private companion object {
        const val MAX_DEPTH = 15
        val EXTENSION_CHILDREN = setOf("extension", "modifierExtension")

        /** Types whose values the census records; see [Census.values] for why so few. */
        val VALUE_TYPES = setOf("code")
    }
}
