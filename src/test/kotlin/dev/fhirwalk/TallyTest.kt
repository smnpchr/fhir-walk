package dev.fhirwalk

import dev.fhirwalk.model.FhirVersion
import dev.fhirwalk.repl.Output
import dev.fhirwalk.repl.Repl
import dev.fhirwalk.repl.Workspaces
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `:tally-join` and `:tally-split` over `samples/basic`, whose Patient/pat1 has three
 * given names: the one case where the two differ.
 */
class TallyTest {

    private val repl = Repl(FhirVersion.R4, emptyList(), Workspaces(null)).also {
        it.outputOf(":load samples/basic")
        for ((expression, name) in listOf("Patient.gender" to "gender", "Patient.name.given" to "given", "Patient.birthDate" to "born")) {
            it.outputOf(expression)
            it.outputOf(":name-expr $name")
        }
    }

    private fun text(line: Output.Line) = line.spans.joinToString("") { it.text }

    private fun rows(line: String): List<String> = (repl.outputOf(line).listing as Output.Listing).rows.map(::text)

    private fun whole(line: String): List<String> = (repl.outputOf(line).listing as Output.Listing).full!!.map(::text)

    @Test
    fun `join counts a cell as it reads, each resource once per level`() {
        assertEquals(
            listOf("  1  female", "      1  Erika, Maria, Rika", "  1  male", "      1  Hans"),
            rows(":tally-join gender given"),
        )
        assertEquals("2 Patient(s) by gender › given", repl.outputOf(":tally-join gender given").lines().first())
    }

    @Test
    fun `split counts each value of a cell on its own`() {
        assertEquals(
            listOf("  1  female", "      1  Erika", "      1  Maria", "      1  Rika", "  1  male", "      1  Hans"),
            rows(":tally-split gender given"),
        )
        assertTrue("a cell's values counted one by one" in repl.outputOf(":tally-split gender given").lines().first())
        // Whole, each innermost group lists the resources it counts.
        val more = whole(":tally-split gender given")
        assertEquals("          Patient/pat1", more[more.indexOf("      1  Maria") + 1])
    }

    @Test
    fun `with one value per cell the two answer the same`() {
        assertEquals(rows(":tally-join gender born"), rows(":tally-split gender born"))
        assertTrue("one by one" !in repl.outputOf(":tally-split gender born").lines().first())
    }

    @Test
    fun `split first by a cell's values, each value is a group of its own`() {
        // Grouped by given name first: four names, each held once.
        assertEquals(listOf("  1  Erika", "      1  female"), rows(":tally-split given gender").take(2))
        assertEquals(4, rows(":tally-split given gender").count { !it.startsWith("      ") })
    }

    @Test
    fun `they take what view takes, and their columns save as a view`() {
        repl.outputOf(":tally-join gender given")
        assertTrue(repl.outputOf(":name-view people").lines().first().startsWith("saved @people: 2 column(s)"))
        assertEquals(rows(":tally-join gender given"), rows(":tally-join @people"))
        val refused = repl.outputOf(":tally-join gender Observation.status").lines().joinToString("\n")
        assertTrue("no :history number or name Observation.status" in refused, refused)
        assertTrue("usage: :tally-join" in repl.outputOf(":tally-join").lines().first())
    }

    @Test
    fun `columns from different types are refused, as by view`() {
        repl.outputOf("Observation.status")
        repl.outputOf(":name-expr status")
        val refused = repl.outputOf(":tally-split gender status").lines().joinToString("\n")
        assertTrue("columns start from different types" in refused, refused)
    }

    @Test
    fun `a cell with no value is a group, and one that failed is too`() {
        // Recorded because Hans matches; pat1 has no value for it. A group with no
        // value comes after the named ones that hold as many.
        repl.outputOf("Patient.name.given.where(\$this = 'Hans')")
        repl.outputOf(":name-expr hans")
        assertEquals(listOf("  1  Hans", "      1  male", "  1  —", "      1  female"), rows(":tally-join hans gender"))

        repl.outputOf("Patient.name.given.single()")
        repl.outputOf(":name-expr one")
        val failed = repl.outputOf(":tally-join one")
        assertTrue("1 error(s)" in failed.lines().first(), failed.lines().first())
        assertTrue(rows(":tally-join one").any { it.endsWith("  !") }, rows(":tally-join one").toString())
    }
}
