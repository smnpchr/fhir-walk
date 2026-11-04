package dev.fhirwalk

import dev.fhirwalk.model.FhirVersion
import dev.fhirwalk.repl.Repl
import dev.fhirwalk.repl.Workspaces
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Several pools in one session, driven line by line: the default pool holds all of
 * `samples/basic`, and `tree` only its tree, whose Observations reference Patients
 * that live in the bundle.
 */
class PoolsTest {

    private val repl = Repl(FhirVersion.R4, emptyList(), Workspaces(null))

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
    fun `open adds a pool, switches to it and loads into it alone`() {
        assertContains(run(":open tree samples/basic/tree"), "loaded 3 resource(s) from 2 file(s); pool tree now 3")
        assertContains(run(":pool"), "pool tree: 3 resource(s)")
        assertContains(run(":pool pool"), "pool pool: 6 resource(s)")
        val listed = run(":pools")
        assertContains(listed, "* pool")
        assertContains(listed, "  tree")
    }

    @Test
    fun `resolve stays inside the pool`() {
        assertContains(run("Observation.subject.resolve()"), "3 of 6 resource(s) matched")
        run(":open tree samples/basic/tree")
        assertContains(run("Observation.subject.resolve()"), "0 of 3 resource(s) matched")
    }

    @Test
    fun `each pool keeps its own focus`() {
        run(":focus Patient/pat1")
        run(":open tree samples/basic/tree")
        assertContains(run(":focus Observation/obs2"), "focus Observation/obs2")
        assertContains(run(":pool pool"), "focus Patient/pat1")
        assertContains(run(":pool tree"), "focus Observation/obs2")
    }

    @Test
    fun `rows from another pool cannot be picked`() {
        run(":focus Observation.code.coding")
        run(":open tree samples/basic/tree")
        assertFalse(run(":focus [0]").contains("focus Observation/obs1"))
    }

    @Test
    fun `recorded expressions and names are shared between pools`() {
        run("Observation.status")
        run(":name-expr status")
        run(":open tree samples/basic/tree")
        assertContains(run(":view status"), "2 Observation row(s)")
    }

    @Test
    fun `an open pool is not opened again, and a name must be valid`() {
        run(":open tree")
        assertContains(run(":open tree samples/basic"), "already open")
        assertContains(run(":open a\tb"), "not a valid pool name")
        assertContains(run(":pool nope"), "no pool nope")
    }

    @Test
    fun `an empty pool says how to fill it`() {
        assertContains(run(":open empty"), "pool empty — empty, :load <path> to import")
        assertContains(run(":load samples/basic/tree"), "pool empty now 3")
    }

    @Test
    fun `the last pool cannot be closed, and closing the current one moves on`() {
        assertContains(run(":close pool"), "the only pool")
        run(":open tree samples/basic/tree")
        assertContains(run(":close tree"), "closed pool tree — now in pool pool")
        assertContains(run(":pool"), "6 resource(s)")
        assertContains(run(":close nope"), "no pool nope")
    }

    @Test
    fun `switching version keeps the pools and empties them`() {
        run(":open tree samples/basic/tree")
        assertContains(run(":fhir r5"), "2 pools emptied")
        assertContains(run(":pool"), "pool is empty")
        val listed = run(":pools")
        assertContains(listed, "* tree")
        assertContains(listed, "nothing loaded")
    }

    @Test
    fun `loading what is loaded is refused, a file in it or a directory around it too`() {
        assertContains(run(":load samples/basic"), "samples/basic: already loaded in pool — loading it again would hold its resources twice")
        assertContains(run(":load samples/basic/bundle.json"), "overlaps ")
        assertContains(run(":load samples"), "overlaps ")
        assertContains(run(":pool"), "6 resource(s)")
    }

    @Test
    fun `a label held twice is counted, listed, and picked from when focused`() {
        val copy = java.nio.file.Files.createTempDirectory("copy")
        java.nio.file.Files.copy(java.nio.file.Paths.get("samples/basic/bundle.json"), copy.resolve("copy.json"))
        run(":load $copy")

        val pool = repl.outputOf(":pool")
        assertContains(pool.lines().first(), "3 label(s) held more than once — :focus one to pick, :more lists them")
        val whole = (pool.listing as dev.fhirwalk.repl.Output.Listing).full!!.joinToString("\n") { line -> line.spans.joinToString("") { it.text } }
        assertContains(whole, "held more than once:\n  Patient/pat1\n      (bundle.json#entry[0])\n      (copy.json#entry[0])")

        val listed = repl.outputOf(":focus Patient/pat1").lines()
        assertEquals("Patient/pat1 is held 2 times; focus needs one — pick it with  :focus [n]", listed.first())
        assertTrue(listed[1].contains("[0]") && listed[1].contains("(bundle.json#entry[0])"), listed.toString())
        assertTrue(listed[2].contains("[1]") && listed[2].contains("(copy.json#entry[0])"), listed.toString())
        assertContains(run(":focus [1]"), "focus Patient/pat1  (copy.json#entry[0])")

        // Held once, a label focuses straight away, as before.
        run(":focus ..")
        assertContains(run(":focus Encounter/enc1"), "focus Encounter/enc1")
    }
}
