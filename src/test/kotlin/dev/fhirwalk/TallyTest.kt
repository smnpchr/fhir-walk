package dev.fhirwalk

import dev.fhirwalk.model.FhirVersion
import dev.fhirwalk.repl.Output
import dev.fhirwalk.repl.Repl
import dev.fhirwalk.repl.Workspaces
import java.nio.file.Files
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
        // Whole, the resources under each innermost group.
        val more = whole(":tally-join gender given")
        assertEquals("          Patient/pat2", more[more.indexOf("      1  Hans") + 1])
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
        assertEquals(listOf("  1  Hans", "      1  male", "  1  (no value)", "      1  female"), rows(":tally-join hans gender"))

        // single() fails on pat1's three names. Its row is still grouped further.
        repl.outputOf("Patient.name.given.single()")
        repl.outputOf(":name-expr one")
        val failed = repl.outputOf(":tally-join one gender")
        assertTrue("1 error(s)" in failed.lines().first(), failed.lines().first())
        assertEquals(listOf("  1  Hans", "      1  male", "  1  (failed)", "      1  female"), rows(":tally-join one gender"))
    }

    @Test
    fun `a tally leaves the last expression nameable and clears picks`() {
        repl.outputOf("Patient.gender")
        repl.outputOf(":tally-join gender")
        assertTrue(repl.outputOf(":name-expr sex").lines().first().contains("sex"))
        assertEquals(rows(":tally-join gender"), rows(":tally-join sex"))
        repl.outputOf("Patient.name.given")
        repl.outputOf(":tally-split given")
        assertTrue("nothing numbered to pick from" in repl.outputOf(":focus [1]").lines().first())
    }

    /** Patients made for the case: padded, repeated, blank and missing given names. */
    private val made = Repl(FhirVersion.R4, emptyList(), Workspaces(null)).also { repl ->
        val dir = Files.createTempDirectory("tally")
        val given = listOf("p1" to """["  Ann  "]""", "p2" to """["Ann"]""", "p3" to """["Bo","Bo"]""", "p4" to null, "p5" to """["Cy","Di"]""")
        for ((id, names) in given) {
            val name = names?.let { ""","name":[{"given":$it}]""" }.orEmpty()
            Files.writeString(dir.resolve("$id.json"), """{"resourceType":"Patient","id":"$id"$name}""")
        }
        repl.outputOf(":load $dir")
        for ((expression, label) in listOf("Patient.name.given" to "given", "Patient.name.given.single()" to "one")) {
            repl.outputOf(expression)
            repl.outputOf(":name-expr $label")
        }
    }

    private fun madeRows(line: String) = (made.outputOf(line).listing as Output.Listing).rows.map(::text)

    @Test
    fun `values count as view shows them, so padding does not split them as in tally`() {
        assertEquals(listOf("  2  Ann", "  1  Bo", "  1  Cy", "  1  Di", "  1  (no value)"), madeRows(":tally-split given"))
        assertEquals(listOf("  2  Ann", "  1  Bo, Bo", "  1  Cy, Di", "  1  (no value)"), madeRows(":tally-join given"))
        // :tally keeps the padded one apart.
        val tally = madeRows(":tally Patient.name.given")
        assertTrue(tally.none { it.trimStart().startsWith("2 ") && "Ann" in it }, tally.toString())
        assertTrue("one by one" !in made.outputOf(":tally-join given").lines().first())
    }

    @Test
    fun `groups that count alike list values, then no value, then failed`() {
        // + fails on several values and gives nothing for none: p3 and p5 fail, p2
        // (only Ann) and p4 have none, and p1's padded Ann reads as :view shows it.
        made.outputOf("Patient.name.given.where(\$this != 'Ann') + 'x'")
        made.outputOf(":name-expr plus")
        assertEquals(listOf("  2  (no value)", "  2  (failed)", "  1  Ann x"), madeRows(":tally-join plus"))
        // single() fails on Bo, Bo: grouped by given first, the failure sits under Bo.
        // p3 holds Bo twice and counts once under it.
        assertEquals("  1  Bo", madeRows(":tally-split given one").first { "Bo" in it })
        assertEquals("      1  (failed)", madeRows(":tally-split given one").let { it[it.indexOf("  1  Bo") + 1] })
    }
}
