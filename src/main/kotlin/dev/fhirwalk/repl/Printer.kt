package dev.fhirwalk.repl

import java.io.PrintWriter

/**
 * Turns an [Output] into text for a window [columns] wide, holding back all but
 * [rowLimit] rows of a listing (0 for none) for `:more`.
 *
 * Measuring comes before colouring, as [Style] requires: a line is fitted to the
 * window on its plain text, then each part is painted.
 */
class Printer(private val style: Style, private val columns: Int, private val rowLimit: Int) {

    fun print(output: Output, out: PrintWriter) {
        for (block in output.blocks) {
            when (block) {
                is Output.Line -> out.println(render(block, fit = true))
                is Output.Listing -> listing(block, out)
                is Output.Whole -> {
                    (block.listing.fullHeader ?: block.listing.header).forEach { out.println(render(it, fit = false)) }
                    (block.listing.full ?: block.listing.rows).forEach { out.println(render(it, fit = false)) }
                }
            }
        }
    }

    private fun listing(listing: Output.Listing, out: PrintWriter) {
        listing.header.forEach { out.println(render(it, fit = true)) }
        val shown = if (rowLimit <= 0) listing.rows.size else minOf(rowLimit, listing.rows.size)
        listing.rows.take(shown).forEach { out.println(render(it, fit = true)) }
        val hidden = listing.rows.size - shown
        when {
            hidden > 0 -> out.println(style.hint("  … $hidden more of ${listing.rows.size} — :more"))
            // Nothing was held back by row count, but a part may still have been
            // shortened to fit the window; say so rather than leaving a dangling "…".
            shortened(listing) -> out.println(style.hint("  … shortened to fit — :more"))
        }
    }

    /**
     * Whether `:more` would show something the listing did not. A row given its own
     * [Output.Listing.full] counts only when it also shows a "…", since a full form
     * that adds detail (a value's JSON) is not a sign that anything was cut.
     */
    private fun shortened(listing: Output.Listing): Boolean {
        val shown = (listing.header + listing.rows).map { plain(it, fit = true) }
        val whole = ((listing.fullHeader ?: listing.header) + (listing.full ?: listing.rows)).map { plain(it, fit = false) }
        return shown != whole && shown.any { it.contains('…') }
    }

    /** [line] as printed: fitted to the window when [fit], then coloured. */
    fun render(line: Output.Line, fit: Boolean): String =
        fitted(line, fit).joinToString("") { (text, kind) -> paint(text, kind) }

    private fun plain(line: Output.Line, fit: Boolean): String = fitted(line, fit).joinToString("") { it.first }

    /**
     * Each part's text after fitting: the parts that shrink share what the others
     * leave of the window, never less than [MIN_VALUE_WIDTH] between them.
     */
    private fun fitted(line: Output.Line, fit: Boolean): List<Pair<String, Output.Kind>> {
        val shrinking = line.spans.count { it.shrinks }
        if (!fit || shrinking == 0) return line.spans.map { it.text to it.kind }
        val fixed = line.spans.filterNot { it.shrinks }.sumOf { it.text.length }
        val room = (columns - fixed).coerceAtLeast(MIN_VALUE_WIDTH)
        var index = 0
        return line.spans.map { span ->
            if (!span.shrinks) return@map span.text to span.kind
            val share = room / shrinking + if (index++ < room % shrinking) 1 else 0
            abbreviate(span.text, share) to span.kind
        }
    }

    private fun paint(text: String, kind: Output.Kind): String = when (kind) {
        Output.Kind.PLAIN -> text
        Output.Kind.INDEX -> style.index(text)
        Output.Kind.LABEL -> style.label(text)
        Output.Kind.HINT -> style.hint(text)
        Output.Kind.COUNT -> style.count(text)
        Output.Kind.ERROR -> style.error(text)
    }

    companion object {
        /** The least a shortened value is given, however little the window leaves. */
        const val MIN_VALUE_WIDTH = 40
    }
}

/** Elides the middle, since the informative parts of a URL are both ends. */
internal fun abbreviate(text: String, width: Int): String {
    if (text.length <= width) return text
    val keepTail = (width - 1) / 2
    val keepHead = width - 1 - keepTail
    return text.take(keepHead) + "…" + text.takeLast(keepTail)
}
