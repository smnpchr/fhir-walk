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

    /**
     * What a part of a line is, which decides its colour. [PREVIEW] is a value packed
     * onto one row, where only JSON keys are picked out; [VALUE] a value shown whole;
     * [JSON] one line of a pretty-printed one.
     */
    enum class Kind { PLAIN, INDEX, LABEL, TYPE, HINT, COUNT, URL, ERROR, PREVIEW, VALUE, JSON }

    /**
     * @param shrinks the part that gives way when the line is wider than the window,
     *   printed whole by `:more`. Pad a column before it is a span, so the padding
     *   counts towards the width.
     * @param cutsEnd shortened at its end rather than in the middle: a value, whose
     *   start says most, rather than a URL or a path, which say most at both ends.
     */
    data class Span(val text: String, val kind: Kind = Kind.PLAIN, val shrinks: Boolean = false, val cutsEnd: Boolean = false)

    sealed interface Block

    /** A block that `:more` can print whole. */
    sealed interface Listed : Block

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
    ) : Listed

    /**
     * A table: [header], a row of [titles], and a row per resource. The printer
     * shares the window between the columns, shortening the widest, and `:more`
     * prints every cell whole.
     */
    data class Table(val header: List<Line>, val titles: List<String>, val rows: List<Row>) : Listed {
        /**
         * @param errors the indexes of cells that hold an error rather than a value.
         * @param wholeLabel what `:more` prints for [label], when that leaves something out.
         * @param note printed after the cells, or in their place for a row with none:
         *   what a tree says of a row, such as where else it is shown.
         */
        data class Row(
            val label: String,
            val cells: List<String>,
            val errors: Set<Int> = emptySet(),
            val wholeLabel: String = label,
            val note: Line? = null,
        )
    }

    /** A listing or table printed whole, as `:more` does. */
    data class Whole(val listing: Listed) : Block

    private val added = mutableListOf<Block>()

    val blocks: List<Block> get() = added

    /** The last listing or table, the one `:more` goes on to print. */
    val listing: Listed? get() = added.filterIsInstance<Listed>().lastOrNull()

    fun line(build: LineBuilder.() -> Unit) = add(Output.line(build))

    fun add(line: Line) {
        added += line
    }

    /** A plain line, printed as it is. */
    fun text(text: String) {
        added += Line(listOf(Span(text)))
    }

    /** `! message`, in the error colour. */
    fun error(message: String) = line { error("! $message") }

    fun hint(text: String) = line { hint(text) }

    fun listing(header: List<Line>, rows: List<Line>, full: List<Line>? = null, fullHeader: List<Line>? = null) {
        added += Listing(header, rows, full, fullHeader)
    }

    fun table(header: List<Line>, titles: List<String>, rows: List<Table.Row>) {
        added += Table(header, titles, rows)
    }

    fun whole(listing: Listed) {
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
            is Table -> lines(block, whole = false)
            is Whole -> when (val listed = block.listing) {
                is Listing -> ((listed.fullHeader ?: listed.header) + (listed.full ?: listed.rows)).map { it.text }
                is Table -> lines(listed, whole = true)
            }
        }
    }

    private fun lines(table: Table, whole: Boolean): List<String> =
        table.header.map { it.text } + table.titles.joinToString("  ") +
            table.rows.map { row ->
                "${if (whole) row.wholeLabel else row.label}  ${row.cells.joinToString("  ")}" + row.note?.let { "  ${it.text}" }.orEmpty()
            }

    class LineBuilder {
        internal val spans = mutableListOf<Span>()

        operator fun String.unaryPlus() {
            spans += Span(this)
        }

        fun index(text: String) = add(text, Kind.INDEX)
        fun label(text: String) = add(text, Kind.LABEL)
        fun type(text: String) = add(text, Kind.TYPE)
        fun hint(text: String) = add(text, Kind.HINT)
        fun count(text: String) = add(text, Kind.COUNT)
        fun url(text: String) = add(text, Kind.URL)
        fun error(text: String) = add(text, Kind.ERROR)
        fun value(text: String) = add(text, Kind.VALUE)
        fun json(text: String) = add(text, Kind.JSON)

        /** The part that is shortened to fit the window; see [Span.shrinks] and [Span.cutsEnd]. */
        fun shrink(text: String, kind: Kind = Kind.PLAIN, cutsEnd: Boolean = false) {
            spans += Span(text, kind, shrinks = true, cutsEnd = cutsEnd)
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
