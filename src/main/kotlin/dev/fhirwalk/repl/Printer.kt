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
                is Output.Table -> listing(laidOut(block), out)
                is Output.Whole -> {
                    val listing = when (val listed = block.listing) {
                        is Output.Listing -> listed
                        is Output.Table -> laidOut(listed)
                    }
                    (listing.fullHeader ?: listing.header).forEach { out.println(render(it, fit = false)) }
                    (listing.full ?: listing.rows).forEach { out.println(render(it, fit = false)) }
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
     * Whether `:more` would show something the listing did not. Rows given their own
     * [Output.Listing.full] count only when they also show a "…", since a full form
     * that adds detail (a value's JSON) is not a sign that anything was cut; the same
     * goes for a header. A "…" that is simply part of the text, such as in an error
     * message, never counts on its own.
     */
    private fun shortened(listing: Output.Listing): Boolean {
        fun cut(shown: List<Output.Line>, whole: List<Output.Line>): Boolean {
            val fitted = shown.map { plain(it, fit = true) }
            return fitted != whole.map { plain(it, fit = false) } && fitted.any { it.contains('…') }
        }
        return cut(listing.rows, listing.full ?: listing.rows) || cut(listing.header, listing.fullHeader ?: listing.header)
    }

    /**
     * [table] as a listing: in full each column is as wide as its widest cell, while
     * the window's share goes first to the columns that fit it; see [fitColumns].
     */
    private fun laidOut(table: Output.Table): Output.Listing {
        val labelWidth = table.rows.maxOfOrNull { it.label.length } ?: 0
        val wholeLabelWidth = table.rows.maxOfOrNull { it.wholeLabel.length } ?: 0
        val natural = table.titles.indices.map { idx -> maxOf(table.titles[idx].length, table.rows.maxOfOrNull { it.cells.getOrNull(idx)?.length ?: 0 } ?: 0) }
        val room = columns - 2 - labelWidth - COLUMN_GAP * table.titles.size
        val fitting = fitColumns(natural, room, MIN_CELL_WIDTH)
        fun line(label: String, cells: List<String>, widths: List<Int>, errors: Set<Int>, titles: Boolean, labelWidth: Int, note: Output.Line? = null) = Output.line {
            +"  "
            if (titles) hint(label.padEnd(labelWidth)) else label(label.padEnd(labelWidth))
            cells.zip(widths).forEachIndexed { idx, (cell, width) ->
                // Padded after shortening, so the columns line up; the last needs none.
                val text = abbreviate(cell, width).let { if (idx == cells.lastIndex) it else it.padEnd(width) }
                +" ".repeat(COLUMN_GAP)
                when {
                    titles -> hint(text)
                    idx in errors -> error(text)
                    else -> +text
                }
            }
            note?.let { +"  "; spans += it.spans }
        }
        fun header(widths: List<Int>, labelWidth: Int) = table.header + trimmed(line("", table.titles, widths, emptySet(), titles = true, labelWidth))
        fun rows(widths: List<Int>, whole: Boolean) = table.rows.map {
            // A row with no cells keeps no padding: its note follows the label.
            val width = if (it.cells.isEmpty() && it.note != null) 0 else if (whole) wholeLabelWidth else labelWidth
            trimmed(line(if (whole) it.wholeLabel else it.label, it.cells, widths.take(it.cells.size), it.errors, titles = false, width, it.note))
        }
        return Output.Listing(header(fitting, labelWidth), rows(fitting, whole = false), rows(natural, whole = true), header(natural, wholeLabelWidth))
    }

    /** [line] without the padding of empty cells at its end. */
    private fun trimmed(line: Output.Line): Output.Line {
        val spans = line.spans.toMutableList()
        while (spans.isNotEmpty() && spans.last().text.isBlank()) spans.removeAt(spans.lastIndex)
        spans.lastOrNull()?.let { spans[spans.lastIndex] = it.copy(text = it.text.trimEnd()) }
        return Output.Line(spans)
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
            (if (span.cutsEnd) cutEnd(span.text, share) else abbreviate(span.text, share)) to span.kind
        }
    }

    private fun paint(text: String, kind: Output.Kind): String = when (kind) {
        Output.Kind.PLAIN -> text
        Output.Kind.INDEX -> style.index(text)
        Output.Kind.LABEL -> style.label(text)
        Output.Kind.TYPE -> style.type(text)
        Output.Kind.HINT -> style.hint(text)
        Output.Kind.COUNT -> style.count(text)
        Output.Kind.URL -> style.url(text)
        Output.Kind.ERROR -> style.error(text)
        Output.Kind.PREVIEW -> style.value(text, keysOnly = true)
        Output.Kind.VALUE -> style.value(text)
        Output.Kind.JSON -> style.json(text)
    }

    companion object {
        /** The least a shortened value is given, however little the window leaves. */
        const val MIN_VALUE_WIDTH = 40
        const val COLUMN_GAP = 2
        const val MIN_CELL_WIDTH = 12
    }
}

/** Keeps the start, as a value is shortened: its opening says what it is. */
internal fun cutEnd(text: String, width: Int): String =
    if (text.length <= width) text else text.take((width - 1).coerceAtLeast(0)) + "…"

/** Elides the middle, since the informative parts of a URL are both ends; never below the "…" itself. */
internal fun abbreviate(text: String, width: Int): String {
    if (text.length <= width) return text
    if (width <= 1) return "…"
    val keepTail = (width - 1) / 2
    val keepHead = width - 1 - keepTail
    return text.take(keepHead) + "…" + text.takeLast(keepTail)
}
