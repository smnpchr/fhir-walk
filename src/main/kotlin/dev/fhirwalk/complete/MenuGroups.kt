package dev.fhirwalk.complete

/**
 * The completion menu's group names, and the order the groups appear in.
 *
 * JLine orders groups by name unless told otherwise, whatever the candidates' sort
 * keys. Most menus are fine that way. After `:view` they are not: a long log would
 * push the few hand-picked entries, saved views and names, below dozens of numbers.
 * So the groups listed in [FIRST] come first, in that order, and every other group
 * follows by name as before.
 */
object MenuGroups {
    const val SAVED_VIEWS = "saved views"
    const val NAMED = "named expressions"
    const val RECORDED = "recorded expressions"

    val FIRST = listOf(SAVED_VIEWS, NAMED, RECORDED)

    /** What JLine sorts groups with; see `LineReaderImpl.getGroupComparator`. */
    val ORDER: Comparator<String> = compareBy<String> { group ->
        FIRST.indexOf(group).let { if (it < 0) FIRST.size else it }
    }.thenBy { it.lowercase() }
}
