package dev.fhirwalk

import dev.fhirwalk.model.FhirVersion
import dev.fhirwalk.repl.ExpressionLog
import dev.fhirwalk.repl.Repl
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The REPL's own wiring, driven line by line against `samples/basic`: what each
 * command leaves behind for the next. The rules themselves are in
 * [FocusResolverTest]; this is about which output keeps `:focus [n]` pickable.
 */
class ReplTest {

    private val log = ExpressionLog(null)
    private val repl = Repl(FhirVersion.R4, emptyList(), log)

    init {
        run(":load samples/basic")
    }

    /** @return what the line printed. */
    private fun run(line: String): String {
        val text = StringWriter()
        PrintWriter(text).use { repl.dispatch(line, it) }
        return text.toString()
    }

    private fun assertContains(output: String, expected: String) =
        assertTrue(output.contains(expected), "expected \"$expected\" in:\n$output")

    @Test
    fun `an expression under a focus numbers its rows for a pick`() {
        run(":focus Patient/pat1")
        run("name")
        assertContains(run(":focus [1]"), "focus Patient/pat1.name[1]")
    }

    @Test
    fun `a pick survives stepping back up, for trying the next row`() {
        run(":focus Observation.code.coding")
        assertContains(run(":focus [0]"), "focus Observation/obs1.code.coding")
        run(":focus ..")
        assertContains(run(":focus [1]"), "focus Observation/obs2.code.coding")
    }

    @Test
    fun `command output does not replace the rows`() {
        run(":focus Observation.code.coding")
        run(":pool")
        run(":more")
        assertContains(run(":focus [2]"), "focus Observation/obs3.code.coding")
    }

    @Test
    fun `a pool-wide listing replaces the rows with nothing`() {
        run(":focus Observation.code.coding")
        run(":all Patient.name")
        assertContains(run(":focus [1]"), "nothing numbered to pick from")
    }

    @Test
    fun `an empty result replaces the rows with nothing`() {
        run(":focus Patient/pat1")
        run("name.given")
        run("telecom.nope")
        assertContains(run(":focus [1]"), "nothing numbered to pick from")
    }

    @Test
    fun `a focus that did not pick replaces the rows`() {
        run(":focus Observation.code.coding")
        // A label: the focus changed and came back, through no pick.
        run(":focus Patient/pat1")
        run(":focus")
        assertContains(run(":focus [1]"), "nothing numbered to pick from")
    }

    @Test
    fun `a malformed or overlong pick is reported as a pick`() {
        run(":focus Observation.code.coding")
        assertContains(run(":focus [-1]"), "no [-1] — that listing has [0] to [2]")
        assertContains(run(":focus [99999999999]"), "no [99999999999]")
        assertContains(run(":focus [ 1 ]"), "focus Observation/obs2.code.coding")
    }

    @Test
    fun `switching version clears the rows`() {
        run(":focus Observation.code.coding")
        assertContains(run(":fhir r5"), "switched to FHIR R5")
        // The pool was replaced; pool-wide rows from it must not be pickable.
        assertContains(run(":focus [0]"), "nothing numbered to pick from")
    }

    // ----------------------------------------------------------- recorded / :view

    @Test
    fun `a pool-wide expression is recorded once, and only when it matched`() {
        run("Patient.name.family")
        run(":all Patient.birthDate")
        run("Patient.nmae")
        run("Patient.name.family")
        assertEquals(listOf("Patient.name.family", "Patient.birthDate"), log.entries())
    }

    @Test
    fun `an expression under a focus is not recorded`() {
        run(":focus Patient/pat1")
        run("name.family")
        assertTrue(log.entries().isEmpty(), "got ${log.entries()}")
        // :all ignores the focus, so it is pool-wide and does count.
        run(":all Patient.name.family")
        assertEquals(listOf("Patient.name.family"), log.entries())
    }

    @Test
    fun `history lists newest first with stable numbers`() {
        run("Patient.name.family")
        run("Patient.birthDate")
        val lines = run(":history").lines().filter { it.isNotBlank() }
        assertTrue(lines[0].contains("#2") && lines[0].contains("Patient.birthDate"), "got $lines")
        assertTrue(lines[1].contains("#1") && lines[1].contains("Patient.name.family"), "got $lines")
        val filtered = run(":history birth")
        assertFalse(filtered.contains("family"), filtered)
    }

    @Test
    fun `a view has a row per resource and joins several values`() {
        run("Patient.name.family")
        run("Patient.name.given")
        val table = run(":view #1 #2")
        assertContains(table, "2 Patient row(s)")
        assertContains(table, "#1 name.family")
        assertContains(table, "Erika, Maria, Rika")
        assertTrue(table.lines().any { it.contains("Patient/pat2") && it.contains("Schmidt") }, table)
        // Plain numbers and commas work as well as #n.
        assertEquals(table, run(":view 1, 2"))
    }

    @Test
    fun `a view keeps resources whose cells are all empty`() {
        run("Patient.contact.name.family")
        val table = run(":view #1")
        assertContains(table, "2 Patient row(s)")
        assertContains(table, "Patient/pat2")
    }

    @Test
    fun `a view refuses structured values and mixed types`() {
        run("Patient.name")
        run("Patient.name.family")
        run("Observation.status")
        assertContains(run(":view #1"), "#1 Patient.name yields HumanName, not a primitive")
        assertContains(run(":view #2 #3"), "columns start from different types")
        assertContains(run(":view #7"), "nothing recorded as #7")
        assertContains(run(":view family"), "not a :history number: family")
    }
}
