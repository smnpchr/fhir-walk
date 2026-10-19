package dev.fhirwalk

import dev.fhirwalk.model.FhirVersion
import dev.fhirwalk.repl.ExpressionLog
import dev.fhirwalk.repl.NameStore
import dev.fhirwalk.repl.Output
import dev.fhirwalk.repl.Repl
import dev.fhirwalk.repl.Workspaces
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The REPL's own wiring, driven line by line against `samples/basic`: what each
 * command leaves behind for the next. The rules themselves are in
 * [FocusResolverTest]; this is about which output keeps `:focus [n]` pickable.
 */
class ReplTest {

    private val repl = Repl(FhirVersion.R4, emptyList(), Workspaces(null))
    private val log: ExpressionLog get() = repl.currentWorkspace.log
    private val names: NameStore get() = repl.currentWorkspace.names

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
    fun `a view that fits the window is not shortened`() {
        // Five columns split 100 characters evenly would cut the given names to 15.
        listOf("Patient.name.family", "Patient.name.given", "Patient.birthDate", "Patient.gender", "Patient.id")
            .forEach { run(it) }
        val table = run(":view #1 #2 #3 #4 #5")
        assertContains(table, "Erika, Maria, Rika")
        assertFalse(table.contains("…"), table)
    }

    @Test
    fun `a value row with an ordinary label stays within the window, however long its type name`() {
        // 100 columns, the default without a terminal.
        run(":focus Observation/obs1")
        val rows = run(":focus code").lines() + run("coding") + run(":all Observation.code").lines()
        assertTrue(rows.all { it.length <= 100 }, rows.filter { it.length > 100 }.joinToString("\n"))
    }

    @Test
    fun `a view is a table the printer lays out`() {
        run("Patient.name.family")
        val table = repl.outputOf(":view #1").blocks.single() as Output.Table
        assertEquals(listOf("#1 name.family"), table.titles)
        assertEquals(listOf("Patient/pat1", "Patient/pat2"), table.rows.map { it.label })
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
        assertContains(run(":view family"), "no :history number or name family")
    }

    // ------------------------------------------------------------------- :name-expr

    @Test
    fun `a named column is asked for and titled by its name`() {
        run("Patient.name.family")
        run("Patient.birthDate")
        assertContains(run(":name-expr family #1"), "family  Patient.name.family")
        val table = run(":view family #2")
        val titles = table.lines()[1]
        assertTrue(titles.contains("family") && titles.contains("#2 birthDate"), table)
        assertFalse(titles.contains("#1"), table)
        assertContains(table, "Mustermann")
    }

    @Test
    fun `rebinding a name says what it held`() {
        run("Patient.name.family")
        run("Patient.name.given")
        run(":name-expr n #1")
        assertContains(run(":name-expr n #1"), "n already names Patient.name.family")
        assertContains(run(":name-expr n 2"), "n  Patient.name.given  (was Patient.name.family)")
        assertEquals("Patient.name.given", names.get("n"))
    }

    @Test
    fun `names are listed, shown, refused and forgotten`() {
        run("Patient.name.family")
        assertContains(run(":name-expr"), "no names yet")
        assertContains(run(":name-expr 2x #1"), "not a valid name: 2x")
        assertContains(run(":name-expr family #9"), "nothing recorded as #9")
        run(":name-expr family #1")
        assertContains(run(":name-expr"), "family  Patient.name.family")
        assertContains(run(":history"), "(family)")
        assertContains(run(":unname-expr family"), "forgot family")
        assertContains(run(":unname-expr family"), "no name family")
    }

    @Test
    fun `a command word that is an element here is an expression, not a dropped colon`() {
        assertContains(run("name"), "2 of 6 resource(s) matched")
        assertContains(run("name = 'x'"), "matched")
        assertContains(run("view #1"), "did you mean  :view #1")
        run(":focus Observation/obs1")
        assertContains(run("name"), "did you mean  :name-expr")
    }

    @Test
    fun `name with no number names the pool-wide expression just run`() {
        assertContains(run(":name-expr early"), "nothing to name")
        run("Patient.birthDate")
        assertContains(run(":name-expr born"), "born  Patient.birthDate")
        run(":all Patient.name.family")
        assertContains(run(":name-expr family"), "family  Patient.name.family")
        // Structured is fine for a name; only :view asks for primitives.
        run("Patient.name")
        assertContains(run(":name-expr who"), "who  Patient.name")
        assertContains(run(":view who"), "who (Patient.name) yields HumanName")
    }

    @Test
    fun `an empty or focus-relative expression leaves nothing to name`() {
        run("Patient.birthDate")
        run("Patient.nmae")
        assertContains(run(":name-expr x"), "nothing to name")
        run("Patient.birthDate")
        run(":focus Patient/pat1")
        run("gender")
        assertContains(run(":name-expr x"), "nothing to name")
        run(":focus")
        run("Patient.birthDate")
        run(":focus Observation.code.coding")
        assertContains(run(":name-expr x"), "nothing to name")
        assertNull(names.get("x"))
    }

    @Test
    fun `a command word with arguments that do not parse is still a dropped colon`() {
        run("Patient.name.family")
        assertContains(run("name family #1"), "did you mean  :name-expr family #1")
        assertContains(run("focus Observation.subject"), "did you mean  :focus Observation.subject")
    }

    @Test
    fun `the old naming commands point to the new ones and do nothing`() {
        run("Patient.name.family")
        assertContains(run(":name family"), ":name is now :name-expr — did you mean  :name-expr family")
        assertNull(names.get("family"))
        assertContains(run(":unname family"), "did you mean  :unname-expr family")
        assertContains(run(":save"), ":save is now :name-view — did you mean  :name-view")
        assertContains(run(":unsave people"), "did you mean  :unname-view people")
        assertContains(run("save people"), "did you mean  :name-view people")
        // :help is the list completion offers; the old spellings are not in it.
        val commands = run(":help").lines().map { it.trim().substringBefore(' ') }
        assertTrue(commands.containsAll(listOf(":name-expr", ":unname-expr", ":name-view", ":unname-view")), "$commands")
        assertTrue(commands.none { it in setOf(":name", ":unname", ":save", ":unsave") }, "$commands")
    }

    @Test
    fun `a failed all leaves nothing to name`() {
        run("Patient.name.family")
        run(":all Patient.name.where(")
        assertContains(run(":name-expr fam"), "nothing to name")
    }

    @Test
    fun `focusing by label or index keeps the expression nameable`() {
        run("Patient.name.family")
        run(":focus #0")
        run(":focus Patient/pat1")
        assertContains(run(":name-expr fam"), "fam  Patient.name.family")
    }
}
