package dev.fhirwalk

import dev.fhirwalk.model.FhirVersion
import dev.fhirwalk.repl.PastedLine
import dev.fhirwalk.repl.Repl
import dev.fhirwalk.repl.Workspaces
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A line pasted as several is run and kept as one (#103). */
class PastedLineTest {

    private fun folded(line: String) = assertIs<PastedLine.Result.Line>(PastedLine.fold(line)).text

    @Test
    fun `a line with no line break is left as it is`() {
        val line = "Patient.name.where(  given = 'Erika' )"
        assertEquals(PastedLine.Result.Line(line, folded = false), PastedLine.fold(line))
    }

    @Test
    fun `a break joining a path step or a bracket leaves no space`() {
        assertEquals("Patient.name.given", folded("Patient\n  .name\n  .given"))
        assertEquals(
            "Specimen.where(type.coding.code = 'PL1').where(parent.exists())",
            folded("Specimen\n  .where( \n    type.coding.code = 'PL1'\n  )\n  .where(parent.exists())\n"),
        )
    }

    @Test
    fun `between words a break is one space, and CRLF is a break too`() {
        assertEquals("Patient.where(gender = 'female' and birthDate.exists())", folded("Patient.where(gender = 'female'\r\n    and birthDate.exists())"))
        assertEquals("code in ('a' | 'b')", folded("code in (\n  'a' |\n  'b'\n)"))
    }

    @Test
    fun `a comment ends at its line break instead of swallowing the rest`() {
        assertEquals("Patient.name.given", folded("Patient.name // the names\n  .given"))
        // Inside a string, // is text.
        assertEquals("extension('http://x/y').value", folded("extension('http://x/y')\n.value"))
    }

    @Test
    fun `a block comment is kept, one line, with quotes and slashes in it as text`() {
        assertEquals("Patient.name /* don't */.given", folded("Patient.name /* don't */\n  .given"))
        assertEquals("Patient /* see http://x */.given", folded("Patient /* see http://x */\n  .given"))
        assertEquals("Patient /* a b */.name", folded("Patient /* a\n   b */\n.name"))
    }

    @Test
    fun `a command that takes no FHIRPath only has its breaks made spaces`() {
        assertEquals(":load /tmp/x//y more", folded(":load /tmp/x//y\nmore"))
        assertEquals(":load ~/O'Brien more", folded(":load ~/O'Brien\nmore"))
        // One that takes FHIRPath is folded as an expression.
        assertEquals(":all Patient.name.given", folded(":all Patient.name // names\n  .given"))
    }

    @Test
    fun `several commands pasted at once are refused`() {
        val refused = assertIs<PastedLine.Result.Refused>(PastedLine.fold(":pool\n:history"))
        assertTrue("paste one at a time" in refused.message, refused.message)
        assertIs<PastedLine.Result.Refused>(PastedLine.fold("Patient.name\n  :history"))
    }

    @Test
    fun `a break inside a string or a quoted identifier is refused`() {
        val refused = assertIs<PastedLine.Result.Refused>(PastedLine.fold("Patient.name.where(family = 'Muster\nmann')"))
        assertTrue("inside a string" in refused.message && "\\n" in refused.message, refused.message)
        assertIs<PastedLine.Result.Refused>(PastedLine.fold("Patient.`na\nme`"))
        // An escaped quote does not end the string.
        assertIs<PastedLine.Result.Refused>(PastedLine.fold("name.where(family = 'O\\'Bri\nen')"))
        // Nor does a backslash let a break through.
        assertIs<PastedLine.Result.Refused>(PastedLine.fold("name.where(family = 'a\\\nb')"))
        assertIs<PastedLine.Result.Refused>(PastedLine.fold("name.where(family = 'a\\\r\nb')"))
    }

    @Test
    fun `a pasted expression runs, is recorded once, and says what was recorded`() {
        val home = Files.createTempDirectory("paste")
        val repl = Repl(FhirVersion.R4, emptyList(), Workspaces(home)).also { it.outputOf(":load samples/basic") }
        val out = repl.outputOf("Patient\n  .name\n  .given").lines()
        assertEquals("read as one line: Patient.name.given", out.first())
        assertTrue(out.any { "2 of 6 resource(s) matched" in it }, out.toString())
        assertEquals(listOf("  #1  Patient.name.given"), repl.outputOf(":history").lines())
        // And so it is on disk, read back by a fresh start.
        val again = Repl(FhirVersion.R4, emptyList(), Workspaces(home))
        assertEquals(listOf("  #1  Patient.name.given"), again.outputOf(":history").lines())

        val refused = repl.outputOf("Patient.name.where(family = 'Muster\nmann')").lines()
        assertTrue(refused.single().startsWith("! a line break inside a string"), refused.toString())
        assertEquals(1, repl.outputOf(":history").lines().size)
    }

    @Test
    fun `a command pasted over lines folds too`() {
        val repl = Repl(FhirVersion.R4, emptyList(), Workspaces(null)).also { it.outputOf(":load samples/basic") }
        val out = repl.outputOf(":all\n  Patient.gender").lines()
        assertTrue(out.any { "2 of 6 resource(s) matched" in it }, out.toString())
    }
}
