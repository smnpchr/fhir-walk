package dev.fhirwalk.model

/**
 * FHIRPath function signatures for completion and inline help.
 *
 * Deliberately a static table rather than anything derived from HAPI: FHIRPath the
 * language is defined independently of the FHIR version, so this needs no per-version
 * variant. [typeEffect] lets the navigator keep tracking types across a call.
 */
data class FhirPathFunction(
    val name: String,
    val signature: String,
    val doc: String,
    val typeEffect: TypeEffect = TypeEffect.UNKNOWN,
) {
    /** What the call does to the type of its input collection. */
    enum class TypeEffect {
        /** Output type equals input type (filters, ordinal selectors). */
        PRESERVES,

        /** Output type is named by the first argument, e.g. ofType(Patient). */
        FROM_ARGUMENT,

        /** Output is a FHIR Extension. */
        EXTENSION,

        /** Output is whatever the reference points at -- answered from the pool census. */
        RESOLVE,

        /** Output is a primitive; no further element navigation is meaningful. */
        PRIMITIVE,

        UNKNOWN,
    }

    val insertText: String get() = if (signature.endsWith("()")) "$name()" else "$name("
}

object FhirPathFunctions {
    private val all = listOf(
        // Existence
        f("empty", "empty()", "true if the collection is empty", TypeEffect.PRIMITIVE),
        f("exists", "exists([criteria])", "true if the collection has any member", TypeEffect.PRIMITIVE),
        f("all", "all(criteria)", "true if criteria holds for every member", TypeEffect.PRIMITIVE),
        f("allTrue", "allTrue()", "true if every boolean member is true", TypeEffect.PRIMITIVE),
        f("anyTrue", "anyTrue()", "true if any boolean member is true", TypeEffect.PRIMITIVE),
        f("allFalse", "allFalse()", "true if every boolean member is false", TypeEffect.PRIMITIVE),
        f("anyFalse", "anyFalse()", "true if any boolean member is false", TypeEffect.PRIMITIVE),
        f("count", "count()", "number of members", TypeEffect.PRIMITIVE),
        f("distinct", "distinct()", "members with duplicates removed", TypeEffect.PRESERVES),
        f("isDistinct", "isDistinct()", "true if there are no duplicates", TypeEffect.PRIMITIVE),
        f("subsetOf", "subsetOf(other)", "true if this is a subset of other", TypeEffect.PRIMITIVE),
        f("supersetOf", "supersetOf(other)", "true if this is a superset of other", TypeEffect.PRIMITIVE),

        // Filtering and projection
        f("where", "where(criteria)", "members for which criteria is true", TypeEffect.PRESERVES),
        f("select", "select(projection)", "evaluate projection for each member", TypeEffect.UNKNOWN),
        f("repeat", "repeat(projection)", "transitively apply projection", TypeEffect.UNKNOWN),
        f("ofType", "ofType(type)", "members of the given type", TypeEffect.FROM_ARGUMENT),
        f("as", "as(type)", "cast to type, or empty", TypeEffect.FROM_ARGUMENT),
        f("is", "is(type)", "true if the single member is of type", TypeEffect.PRIMITIVE),

        // Subsetting
        f("first", "first()", "first member", TypeEffect.PRESERVES),
        f("last", "last()", "last member", TypeEffect.PRESERVES),
        f("tail", "tail()", "all but the first member", TypeEffect.PRESERVES),
        f("skip", "skip(num)", "all but the first num members", TypeEffect.PRESERVES),
        f("take", "take(num)", "the first num members", TypeEffect.PRESERVES),
        f("single", "single()", "the one member, or error if more", TypeEffect.PRESERVES),
        f("intersect", "intersect(other)", "members present in both", TypeEffect.PRESERVES),
        f("exclude", "exclude(other)", "members not present in other", TypeEffect.PRESERVES),
        f("union", "union(other)", "members of either, deduplicated", TypeEffect.PRESERVES),
        f("combine", "combine(other)", "members of either, keeping duplicates", TypeEffect.PRESERVES),

        // Strings
        f("contains", "contains(substring)", "true if the string contains substring", TypeEffect.PRIMITIVE),
        f("startsWith", "startsWith(prefix)", "true if the string starts with prefix", TypeEffect.PRIMITIVE),
        f("endsWith", "endsWith(suffix)", "true if the string ends with suffix", TypeEffect.PRIMITIVE),
        f("matches", "matches(regex)", "true if the string matches the regex", TypeEffect.PRIMITIVE),
        f("replaceMatches", "replaceMatches(regex, substitution)", "regex replace", TypeEffect.PRIMITIVE),
        f("replace", "replace(pattern, substitution)", "literal replace", TypeEffect.PRIMITIVE),
        f("substring", "substring(start [, length])", "substring by offset", TypeEffect.PRIMITIVE),
        f("indexOf", "indexOf(substring)", "0-based offset of substring, or -1", TypeEffect.PRIMITIVE),
        f("length", "length()", "string length", TypeEffect.PRIMITIVE),
        f("upper", "upper()", "uppercased", TypeEffect.PRIMITIVE),
        f("lower", "lower()", "lowercased", TypeEffect.PRIMITIVE),
        f("trim", "trim()", "whitespace trimmed", TypeEffect.PRIMITIVE),
        f("split", "split(separator)", "split into a collection of strings", TypeEffect.PRIMITIVE),
        f("join", "join([separator])", "concatenate members into one string", TypeEffect.PRIMITIVE),
        f("toChars", "toChars()", "the string as a collection of characters", TypeEffect.PRIMITIVE),
        f("encode", "encode(format)", "encode as base64/hex/urlbase64", TypeEffect.PRIMITIVE),
        f("decode", "decode(format)", "decode from base64/hex/urlbase64", TypeEffect.PRIMITIVE),

        // Conversion
        f("toString", "toString()", "convert to string", TypeEffect.PRIMITIVE),
        f("toInteger", "toInteger()", "convert to integer", TypeEffect.PRIMITIVE),
        f("toDecimal", "toDecimal()", "convert to decimal", TypeEffect.PRIMITIVE),
        f("toBoolean", "toBoolean()", "convert to boolean", TypeEffect.PRIMITIVE),
        f("toQuantity", "toQuantity([unit])", "convert to quantity", TypeEffect.UNKNOWN),
        f("toDate", "toDate()", "convert to date", TypeEffect.PRIMITIVE),
        f("toDateTime", "toDateTime()", "convert to dateTime", TypeEffect.PRIMITIVE),
        f("toTime", "toTime()", "convert to time", TypeEffect.PRIMITIVE),
        f("convertsToInteger", "convertsToInteger()", "true if convertible to integer", TypeEffect.PRIMITIVE),
        f("convertsToDecimal", "convertsToDecimal()", "true if convertible to decimal", TypeEffect.PRIMITIVE),
        f("convertsToBoolean", "convertsToBoolean()", "true if convertible to boolean", TypeEffect.PRIMITIVE),
        f("convertsToDateTime", "convertsToDateTime()", "true if convertible to dateTime", TypeEffect.PRIMITIVE),

        // FHIR-specific
        f("extension", "extension(url)", "extensions with the given url", TypeEffect.EXTENSION),
        f("hasValue", "hasValue()", "true if the primitive has a value", TypeEffect.PRIMITIVE),
        f("getValue", "getValue()", "the primitive value", TypeEffect.PRIMITIVE),
        f("resolve", "resolve()", "follow a reference (pool-backed here)", TypeEffect.RESOLVE),
        f("memberOf", "memberOf(valueset)", "true if the code is in the value set", TypeEffect.PRIMITIVE),
        f("subsumes", "subsumes(code)", "true if this code subsumes the argument", TypeEffect.PRIMITIVE),
        f("subsumedBy", "subsumedBy(code)", "true if this code is subsumed by the argument", TypeEffect.PRIMITIVE),
        f("htmlChecks", "htmlChecks()", "true if the narrative xhtml is valid", TypeEffect.PRIMITIVE),
        f("conformsTo", "conformsTo(profile)", "true if the resource conforms to the profile", TypeEffect.PRIMITIVE),
        f("elementDefinition", "elementDefinition()", "the ElementDefinition for this element", TypeEffect.UNKNOWN),
        f("slice", "slice(structure, name)", "members matching the named slice", TypeEffect.PRESERVES),
        f("checkModifiers", "checkModifiers()", "verify modifier extensions are understood", TypeEffect.PRIMITIVE),

        // Tree / utility
        f("children", "children()", "immediate child nodes", TypeEffect.UNKNOWN),
        f("descendants", "descendants()", "all descendant nodes", TypeEffect.UNKNOWN),
        f("trace", "trace(name [, selector])", "log the collection and pass it through", TypeEffect.PRESERVES),
        f("iif", "iif(criterion, true-result [, otherwise])", "inline conditional", TypeEffect.UNKNOWN),
        f("not", "not()", "boolean negation", TypeEffect.PRIMITIVE),
        f("abs", "abs()", "absolute value", TypeEffect.PRIMITIVE),
        f("round", "round([precision])", "round to precision", TypeEffect.PRIMITIVE),
        f("sqrt", "sqrt()", "square root", TypeEffect.PRIMITIVE),
        f("truncate", "truncate()", "integer part", TypeEffect.PRIMITIVE),
        f("now", "now()", "current date and time", TypeEffect.PRIMITIVE),
        f("today", "today()", "current date", TypeEffect.PRIMITIVE),
        f("timeOfDay", "timeOfDay()", "current time", TypeEffect.PRIMITIVE),
        f("comparable", "comparable(quantity)", "true if the quantities are comparable", TypeEffect.PRIMITIVE),
    )

    private val byName = all.associateBy { it.name }

    fun all(): List<FhirPathFunction> = all

    operator fun get(name: String): FhirPathFunction? = byName[name]

    /**
     * Functions worth offering after a `.` on a collection. Nearly everything is
     * legal somewhere, so this is about ranking rather than correctness: the
     * navigational and filtering functions come first because they are what you
     * reach for while composing a path.
     */
    val commonFirst: Set<String> = setOf(
        "where", "exists", "first", "ofType", "extension", "resolve", "count", "empty", "select", "all",
    )
}

private fun f(
    name: String,
    signature: String,
    doc: String,
    effect: FhirPathFunction.TypeEffect,
) = FhirPathFunction(name, signature, doc, effect)

private typealias TypeEffect = FhirPathFunction.TypeEffect
