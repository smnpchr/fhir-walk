package dev.fhirwalk.repl

/**
 * What a command has to say, before it is styled or fitted to a window.
 *
 * Commands write into one as they once wrote to the terminal, and [Printer] turns it
 * into text. The decisions every listing shares -- colour, which part of a row may be
 * shortened and by how much, how many rows to show and what waits for `:more` -- are
 * then made in one place, and tests can look at what a command found rather than at
 * how it reads in a window of a particular size.
 *
 * A [Line] is made of [Span]s that say what each part is, not how it looks. Commands
 * not yet written this way add lines they formatted and styled themselves, with
 * [text]; those are printed exactly as given.
 */
class Output {

    /** The parts of a line, in the order they are printed. */
    enum class Kind { PLAIN, INDEX, LABEL, HINT, COUNT, ERROR }

    /**
     * @param shrinks the part that gives way when the line is wider than the window:
     *   shortened in the middle, and printed whole by `:more`. Pad a column before it
     *   is a span, so the padding counts towards the width.
     */
    data class Span(val text: String, val kind: Kind = Kind.PLAIN, val shrinks: Boolean = false)

    sealed interface Block

    data class Line(val spans: List<Span>) : Block {
        /** The line unstyled and unshortened. */
        val text: String get() = spans.joinToString("") { it.text }
    }

    /**
     * Rows that may not all fit: as many as the row limit allows are printed, each
     * shortened to the window, and `:more` prints the rest.
     *
     * @param full what `:more` prints in place of [rows], when the whole of a row is
     *   more than its parts unshortened (a value's JSON, a pool's paths); null for the
     *   rows themselves.
     * @param fullHeader what `:more` prints above [full]; null for [header].
     */
    data class Listing(
        val header: List<Line>,
        val rows: List<Line>,
        val full: List<Line>? = null,
        val fullHeader: List<Line>? = null,
    ) : Block

    /** A listing printed whole, as `:more` does. */
    data class Whole(val listing: Listing) : Block

    private val added = mutableListOf<Block>()

    val blocks: List<Block> get() = added

    /** The last listing, the one `:more` goes on to print. */
    val listing: Listing? get() = added.filterIsInstance<Listing>().lastOrNull()

    fun line(build: LineBuilder.() -> Unit) {
        added += Output.line(build)
    }

    /** A line as it is: plain, or already styled by a command not written with spans. */
    fun text(text: String) {
        added += Line(listOf(Span(text)))
    }

    /** `! message`, in the error colour. */
    fun error(message: String) = line { error("! $message") }

    fun hint(text: String) = line { hint(text) }

    fun listing(header: List<Line>, rows: List<Line>, full: List<Line>? = null, fullHeader: List<Line>? = null) {
        added += Listing(header, rows, full, fullHeader)
    }

    fun whole(listing: Listing) {
        added += Whole(listing)
    }

    /**
     * Everything said, unstyled, with nothing shortened or held back: what a command
     * found, independent of the window it would be printed in.
     */
    fun lines(): List<String> = added.flatMap { block ->
        when (block) {
            is Line -> listOf(block.text)
            is Listing -> (block.header + block.rows).map { it.text }
            is Whole -> ((block.listing.fullHeader ?: block.listing.header) + (block.listing.full ?: block.listing.rows)).map { it.text }
        }
    }

    class LineBuilder {
        internal val spans = mutableListOf<Span>()

        operator fun String.unaryPlus() {
            spans += Span(this)
        }

        fun index(text: String) = add(text, Kind.INDEX)
        fun label(text: String) = add(text, Kind.LABEL)
        fun hint(text: String) = add(text, Kind.HINT)
        fun count(text: String) = add(text, Kind.COUNT)
        fun error(text: String) = add(text, Kind.ERROR)

        /** The part that is shortened to fit the window; see [Span.shrinks]. */
        fun shrink(text: String, kind: Kind = Kind.PLAIN) {
            spans += Span(text, kind, shrinks = true)
        }

        private fun add(text: String, kind: Kind) {
            spans += Span(text, kind)
        }
    }

    companion object {
        fun line(build: LineBuilder.() -> Unit): Line = Line(LineBuilder().apply(build).spans.toList())

        /** A line formatted and styled by its command; see [text]. */
        fun text(text: String): Line = Line(listOf(Span(text)))
    }
}
