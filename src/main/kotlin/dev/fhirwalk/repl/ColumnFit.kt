package dev.fhirwalk.repl

/**
 * Widths for table columns whose widest cells are [natural], sharing out [room]
 * characters between them.
 *
 * A column that fits its share keeps its natural width and hands what it does not
 * use to the others, so a table that fits is drawn in full and only the widest
 * columns of one that does not are shortened. A shortened column is never narrower
 * than [minimum], even if that overruns [room].
 */
internal fun fitColumns(natural: List<Int>, room: Int, minimum: Int): List<Int> {
    val widths = natural.toMutableList()
    var open = natural.indices.toList()
    var left = room
    while (open.isNotEmpty()) {
        val fitting = open.filter { natural[it] <= left / open.size }
        if (fitting.isEmpty()) {
            // Split what is left evenly, the remainder going one each to the first.
            open.forEachIndexed { i, idx ->
                val share = left / open.size + if (i < left % open.size) 1 else 0
                widths[idx] = minOf(natural[idx], share.coerceAtLeast(minimum))
            }
            break
        }
        fitting.forEach { left -= natural[it] }
        open = open - fitting.toSet()
    }
    return widths
}
