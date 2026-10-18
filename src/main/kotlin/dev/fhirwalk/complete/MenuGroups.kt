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

    const val TYPES_IN_POOL = "in pool"
    const val TYPES_ALL = "all resource types"
    const val ELEMENTS_IN_POOL = "elements (in pool)"
    const val EXTENSIONS_OBSERVED = "extension() — observed here"
    const val OF_TYPE_REFERENCED = "ofType() — referenced here"
    const val ELEMENTS = "elements"
    const val FUNCTIONS = "functions"
    const val FUNCTIONS_MORE = "functions (more)"

    val FIRST = listOf(
        SAVED_VIEWS, NAMED, RECORDED,
        COMMITS, BRANCHES,
        TYPES_IN_POOL, TYPES_ALL,
        ELEMENTS_IN_POOL, EXTENSIONS_OBSERVED, OF_TYPE_REFERENCED, ELEMENTS, FUNCTIONS, FUNCTIONS_MORE,
    )

    /** What JLine sorts groups with; see `LineReaderImpl.getGroupComparator`. */
    val ORDER: Comparator<String> = compareBy<String> { group ->
        FIRST.indexOf(group).let { if (it < 0) FIRST.size else it }
    }.thenBy { it.lowercase() }
}
