package dev.fhirwalk

import dev.fhirwalk.repl.Output
import dev.fhirwalk.repl.Printer
import dev.fhirwalk.repl.Style
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** How an [Output] is fitted to a window, held back for `:more`, and coloured. */
class PrinterTest {

    private fun printed(output: Output, columns: Int = 60, rows: Int = 0, style: Style = Style.PLAIN): List<String> {
        val text = StringWriter()
        PrintWriter(text).use { Printer(style, columns, rows).print(output, it) }
        return text.toString().lines().dropLast(1)
    }

    private val long = "x".repeat(30) + "-middle-" + "y".repeat(30)

    @Test
    fun `the part that shrinks takes what the others leave of the window`() {
        val output = Output().apply { line { +"  "; index("abc1234"); +"  "; shrink(long) } }
        val line = printed(output, columns = 60).single()
        assertEquals(60, line.length, line)
        assertTrue(line.startsWith("  abc1234  xxx") && line.endsWith("yyy") && "…" in line, line)
        assertEquals(output.lines().single(), printed(output, columns = 200).single(), "nothing cut when it fits")
    }

    @Test
    fun `a shrinking part keeps a readable width however narrow the window`() {
        val line = printed(Output().apply { line { +"a".repeat(50); shrink(long) } }, columns = 60).single()
        assertEquals(50 + Printer.MIN_VALUE_WIDTH, line.length)
    }

    @Test
    fun `several shrinking parts share the room`() {
        val line = printed(Output().apply { line { shrink(long); +" | "; shrink(long) } }, columns = 83).single()
        assertEquals(listOf(40, 40), line.split(" | ").map { it.length })
    }

    @Test
    fun `a table keeps room for a note after its cells`() {
        val note = Output.line { error("! refers to Specimen/x, not in the pool") }
        val output = Output().apply {
            table(
                emptyList(),
                listOf("a", "b"),
                listOf(
                    Output.Table.Row("Specimen/s1", listOf("x".repeat(40), "y".repeat(40))),
                    Output.Table.Row("Specimen/s2", listOf("x", "y"), note = note),
                    Output.Table.Row("Specimen/s3", emptyList(), note = Output.line { hint("(shown above)") }),
                ),
            )
        }
        val lines = printed(output, columns = 80)
        assertTrue(lines.all { it.length <= 80 }, lines.joinToString("\n"))
        assertTrue(lines.any { it.endsWith("! refers to Specimen/x, not in the pool") }, lines.joinToString("\n"))
        assertTrue("  Specimen/s3  (shown above)" in lines, lines.joinToString("\n"))
    }

    @Test
    fun `a listing past the row limit says how many wait for more`() {
        val output = Output().apply { listing(listOf(Output.text("head")), (1..5).map { Output.text("row $it") }) }
        assertEquals(listOf("head", "row 1", "row 2", "  … 3 more of 5 — :more"), printed(output, rows = 2))
    }

    @Test
    fun `a listing cut to the window says so, and its whole form does not`() {
        val listing = Output().apply { listing(emptyList(), listOf(Output.line { +"  "; shrink(long) })) }
        assertEquals("  … shortened to fit — :more", printed(listing, columns = 50).last())
        val whole = Output().apply { whole(listing.listing!!) }
        assertEquals(listOf("  $long"), printed(whole, columns = 50))
    }

    @Test
    fun `a full form that adds detail is not taken for a cut`() {
        val output = Output().apply {
            listing(emptyList(), listOf(Output.text("  Patient/p1  Erika")), full = listOf(Output.text("  Patient/p1"), Output.text("      {\"given\": \"Erika\"}")))
        }
        assertEquals(listOf("  Patient/p1  Erika"), printed(output))
    }

    @Test
    fun `a header cut to the window counts as shortened too`() {
        // A table whose title is longer than every cell is shortened in its header only.
        val output = Output().apply {
            listing(listOf(Output.text("  #1 a-long-title-shortened-to-fit…")), listOf(Output.text("  short")), fullHeader = listOf(Output.text("  #1 a-long-title-shortened-to-fit-the-window")))
        }
        assertEquals("  … shortened to fit — :more", printed(output).last())
    }

    @Test
    fun `a dot-dot-dot that is only text is not taken for a cut`() {
        // An error message may hold one; the header is the same in full.
        val output = Output().apply {
            listing(listOf(Output.text("2 of 6 matched; 1 error(s): extension('…') failed")), listOf(Output.text("  Patient/p1  x")), full = listOf(Output.text("  Patient/p1"), Output.text("      x")))
        }
        assertEquals(2, printed(output).size)
    }

    @Test
    fun `a share too small for anything still prints`() {
        val output = Output().apply { line { (1..50).forEach { shrink("abcdef") } } }
        assertTrue(printed(output, columns = 10).single().isNotEmpty())
    }

    @Test
    fun `colour comes after fitting, so escape codes take no width`() {
        val output = Output().apply { line { index("abc1234"); +" "; shrink(long) } }
        val coloured = printed(output, columns = 60, style = Style(true)).single()
        assertTrue(coloured.contains("\u001b["), coloured)
        assertEquals(printed(output, columns = 60).single(), coloured.replace(Regex("\u001b\\[[0-9;]*m"), ""))
    }

    private fun table(vararg rows: Output.Table.Row) =
        Output().apply { table(listOf(Output.text("2 Patient row(s)")), listOf("family", "#2 given"), rows.toList()) }

    @Test
    fun `a table that fits is laid out whole, its columns lined up`() {
        val printed = printed(table(Output.Table.Row("Patient/p1", listOf("Mustermann", "Erika")), Output.Table.Row("Patient/p2", listOf("Schmidt", ""))), columns = 80)
        assertEquals(
            listOf(
                "2 Patient row(s)",
                "              family      #2 given",
                "  Patient/p1  Mustermann  Erika",
                // An empty last cell leaves no padding behind.
                "  Patient/p2  Schmidt",
            ),
            printed,
        )
    }

    @Test
    fun `a narrow window shortens the widest column, and more lays it out whole`() {
        val output = table(Output.Table.Row("Patient/p1", listOf("Mustermann", "x".repeat(60))))
        val narrow = printed(output, columns = 50)
        assertTrue(narrow.drop(1).dropLast(1).all { it.length <= 50 }, narrow.joinToString("\n"))
        assertEquals("  … shortened to fit — :more", narrow.last())
        val whole = printed(Output().apply { whole(output.listing!!) }, columns = 50)
        assertTrue(whole.last().endsWith("x".repeat(60)), whole.last())
    }

    @Test
    fun `an error cell is coloured as one`() {
        val coloured = printed(table(Output.Table.Row("Patient/p1", listOf("!", "Erika"), errors = setOf(0))), style = Style(true))
        assertTrue(coloured.last().contains("\u001b[31m!"), coloured.last())
    }

    @Test
    fun `lines are what was found, whole and unstyled`() {
        val output = Output().apply {
            error("cannot read")
            listing(listOf(Output.text("h")), listOf(Output.line { count("Δ3"); shrink(long) }))
        }
        assertEquals(listOf("! cannot read", "h", "Δ3$long"), output.lines())
        assertFalse(output.lines().any { "…" in it })
    }
}
