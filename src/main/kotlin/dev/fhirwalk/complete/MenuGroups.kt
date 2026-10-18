package dev.fhirwalk.complete

/**
 * The completion menu's group names, and the order the groups appear in.
 *
 * JLine orders groups by name unless told otherwise, whatever the candidates' sort
 * keys. Most menus are fine that way. After `:view` they are not: a long log would
 * push the few hand-picked entries, saved views and names, below dozens of numbers.
 * After `:at`, the commits are what you step to, so they come before the branches.
 * And in an expression, what the pool holds comes before what the schema merely
 * allows: `Specimen` from the pool before `SpecimenDefinition` from the schema, the
 * populated elements before the rest, functions last.
 * So the groups listed in [FIRST] come first, in that order, and every other group
 * follows by name as before.
 */
object MenuGroups {
    const val SAVED_VIEWS = "saved views"
    const val NAMED = "named expressions"
    const val RECORDED = "recorded expressions"
    const val COMMITS = "commits"
    const val BRANCHES = "branches and tags"

    /** In `ofType(`, the types references at this path were observed to point at. */
    const val TYPES_REFERENCED = "types referenced here"
    const val ELEMENTS_IN_POOL = "elements (in pool)"
    const val EXTENSIONS_OBSERVED = "extension() — observed here"
    const val OF_TYPE_REFERENCED = "ofType() — referenced here"
    const val ELEMENTS = "elements"
    const val TYPES_IN_POOL = "resource types (in pool)"
    const val TYPES_ALL = "all resource types"
    const val FUNCTIONS = "functions"
    const val FUNCTIONS_MORE = "functions (more)"

    /**
     * Values for a string argument. Each such menu holds one of these -- the URLs
     * observed elsewhere only when none were observed on the element -- so their
     * order never matters. [OBSERVED_ON] is followed by the path.
     */
    const val OBSERVED_ON = "observed on "
    const val OBSERVED_VALUES = "observed values"
    const val OBSERVED_ELSEWHERE = "observed elsewhere in pool"
    const val PROFILES = "profiles in pool"
    const val SYSTEMS = "systems in pool"

    fun observedOn(path: String) = OBSERVED_ON + path

    /**
     * One order for every menu, so it must suit each combination of groups that
     * appear together. With a resource in focus, an empty line offers its elements
     * and the resource types side by side; the elements come first, being what
     * `:focus` is for. Groups that never share a menu can sit anywhere relative to
     * each other.
     */
    val FIRST = listOf(
        SAVED_VIEWS, NAMED, RECORDED,
        COMMITS, BRANCHES,
        TYPES_REFERENCED,
        ELEMENTS_IN_POOL, EXTENSIONS_OBSERVED, OF_TYPE_REFERENCED, ELEMENTS,
        TYPES_IN_POOL, TYPES_ALL,
        FUNCTIONS, FUNCTIONS_MORE,
    )

    /** What JLine sorts groups with; see `LineReaderImpl.getGroupComparator`. */
    val ORDER: Comparator<String> = compareBy<String> { group ->
        FIRST.indexOf(group).let { if (it < 0) FIRST.size else it }
    }.thenBy { it.lowercase() }
}
