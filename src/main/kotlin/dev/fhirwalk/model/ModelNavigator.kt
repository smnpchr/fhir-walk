package dev.fhirwalk.model

import ca.uhn.fhir.context.BaseRuntimeChildDefinition
import ca.uhn.fhir.context.BaseRuntimeDeclaredChildDefinition
import ca.uhn.fhir.context.BaseRuntimeElementCompositeDefinition
import ca.uhn.fhir.context.BaseRuntimeElementDefinition
import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.context.RuntimeChildChoiceDefinition
import ca.uhn.fhir.context.RuntimeChildResourceDefinition
import dev.fhirwalk.eval.FhirPathTokenizer
import dev.fhirwalk.pool.Census

/**
 * The schema axis of completion: what is *legal* at a given path.
 *
 * Built entirely on `ca.uhn.fhir.context` runtime definitions, which are
 * version-independent. This is what replaces the version-specific `TypeDetails` /
 * `FHIRPathEngine.check()` route -- it gives element names, concrete choice-type
 * names, cardinality and the short definition text in one pass, and costs nothing
 * to support a new FHIR version.
 *
 * Where the static model runs out (`resolve()`), the [census] fills in from observed
 * data -- the two axes composing is the whole idea.
 */
class ModelNavigator(private val ctx: FhirContext, private val census: Census? = null) {

    data class Child(
        val name: String,
        val typeName: String,
        val isCollection: Boolean,
        val doc: String?,
    )

    fun resourceTypes(): List<String> =
        runCatching { ctx.resourceTypes.sorted() }.getOrDefault(emptyList())

    /** Resolves the declared type of [path], or null when it cannot be known statically. */
    fun resolveType(path: String): BaseRuntimeElementDefinition<*>? {
        val segments = FhirPathTokenizer.segments(path).filter { it.text.isNotBlank() }
        if (segments.isEmpty()) return null

        var current: BaseRuntimeElementDefinition<*> = resourceOrElement(segments.first().name) ?: return null
        var walked = segments.first().text

        for (segment in segments.drop(1)) {
            val next = if (segment.isCall) {
                applyCall(segment, current, walked)
            } else {
                childType(current, segment.name, "$walked.${segment.name}")
            } ?: return null
            current = next
            walked = "$walked.${segment.text}"
        }
        return current
    }

    fun childrenAt(path: String): List<Child> {
        if (path.isBlank()) return emptyList()
        return childrenOf(resolveType(path) ?: return emptyList())
    }

    fun childrenOf(def: BaseRuntimeElementDefinition<*>): List<Child> {
        val composite = def as? BaseRuntimeElementCompositeDefinition<*> ?: return emptyList()
        val out = mutableListOf<Child>()
        for (child in composite.children) {
            val doc = (child as? BaseRuntimeDeclaredChildDefinition)?.shortDefinition?.takeIf { it.isNotBlank() }
            val collection = child.max != 1
            // Only RuntimeChildChoiceDefinition marks a real choice element. Counting
            // valid child names would misclassify references, which carry a second
            // synthetic name (parent / parentResource) without being a choice.
            val types = if (child.isChoice()) child.distinctTypeNames() else emptyList()
            val typeName = when {
                // FHIRPath addresses a real choice by its base name and narrows with
                // ofType(), so offering the wire names (valueQuantity, …) would
                // suggest expressions HAPI refuses to evaluate.
                types.size > 1 ->
                    "choice: " + types.take(4).joinToString("|") + if (types.size > 4) "|…" else ""
                // Extension is modelled as a choice with a single type; calling that
                // a choice would be noise.
                types.size == 1 -> types.first()
                else -> child.soleTypeName()
            }
            out += Child(child.elementName, typeName, collection, doc)
        }
        return out.distinctBy { it.name }.sortedBy { it.name }
    }

    /**
     * @param pathSoFar the full path *including* [name], so a choice element can be
     *   narrowed against what the pool actually holds there.
     */
    private fun childType(
        parent: BaseRuntimeElementDefinition<*>,
        name: String,
        pathSoFar: String,
    ): BaseRuntimeElementDefinition<*>? {
        val composite = parent as? BaseRuntimeElementCompositeDefinition<*> ?: return null
        val child: BaseRuntimeChildDefinition = composite.children.firstOrNull { it.elementName == name }
            ?: composite.getChildByName(name)
            ?: composite.children.firstOrNull { name in it.validChildNames }
            ?: return null

        if (!child.isChoice()) {
            // References and single-typed elements: resolve by whichever of the
            // child's own names matches, which for a reference yields Reference.
            val lookup = if (name in child.validChildNames) {
                name
            } else {
                child.validChildNames.firstOrNull() ?: name
            }
            return runCatching { child.getChildByName(lookup) }.getOrNull()
        }
        // An explicitly spelled wire name still types correctly.
        if (name in child.validChildNames) return runCatching { child.getChildByName(name) }.getOrNull()

        // Single-typed "choice" (Extension): no ambiguity to resolve.
        child.distinctTypeNames().singleOrNull()?.let { return resourceOrElement(it) }

        // Base name on a real choice: statically ambiguous, so fall back to the corpus
        // and only commit when every observed value at this path agrees.
        val observed = census?.choiceTypesAt(pathSoFar).orEmpty()
        return observed.singleOrNull()?.let { resourceOrElement(it.value) }
    }

    /** The distinct element types this child can hold, in declaration order. */
    private fun BaseRuntimeChildDefinition.distinctTypeNames(): List<String> =
        validChildNames
            .mapNotNull { runCatching { getChildByName(it)?.name }.getOrNull() }
            .distinct()

    private fun BaseRuntimeChildDefinition.soleTypeName(): String {
        if (this is RuntimeChildResourceDefinition) return "Reference"
        val only = validChildNames.firstOrNull() ?: elementName
        return runCatching { getChildByName(only)?.name }.getOrNull() ?: "?"
    }

    /** Tracks the type across a function call using the static effect table. */
    private fun applyCall(
        segment: FhirPathTokenizer.Segment,
        current: BaseRuntimeElementDefinition<*>,
        pathSoFar: String,
    ): BaseRuntimeElementDefinition<*>? {
        val fn = FhirPathFunctions[segment.name] ?: return null
        return when (fn.typeEffect) {
            FhirPathFunction.TypeEffect.PRESERVES -> current
            FhirPathFunction.TypeEffect.EXTENSION -> resourceOrElement("Extension")
            FhirPathFunction.TypeEffect.FROM_ARGUMENT ->
                segment.argText?.trim()?.trim('\'', '"')?.let { resourceOrElement(it) }
            FhirPathFunction.TypeEffect.RESOLVE -> {
                // The static model cannot answer this; the corpus can, when every
                // observed reference at this path points at the same type.
                val observed = census?.referenceTargetsAt(pathSoFar).orEmpty()
                observed.singleOrNull()?.let { resourceOrElement(it.value) }
            }
            else -> null
        }
    }

    /**
     * A genuine `[x]` choice element. `RuntimeChildAny` (Extension.value) extends
     * the choice definition, so both are covered.
     */
    private fun BaseRuntimeChildDefinition.isChoice(): Boolean = this is RuntimeChildChoiceDefinition

    private fun resourceOrElement(name: String): BaseRuntimeElementDefinition<*>? {
        if (name.isBlank()) return null
        runCatching { ctx.getResourceDefinition(name) }.getOrNull()?.let { return it }
        return runCatching { ctx.getElementDefinition(name) }.getOrNull()
    }
}
