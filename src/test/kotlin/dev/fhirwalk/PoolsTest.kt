package dev.fhirwalk

import dev.fhirwalk.model.FhirVersion
import dev.fhirwalk.repl.Repl
import dev.fhirwalk.repl.Workspaces
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Paths
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
    fun `loading what the pool holds replaces it, by FHIR identity`() {
        // The pool holds samples/basic from init.
        assertContains(run(":load samples/basic"), "loaded 6 resource(s) from 3 file(s), 6 replaced; pool now 6")
        assertContains(run(":load samples/basic/bundle.json"), "3 replaced; pool now 6")
    }

    @Test
    fun `a directory after one of its files reads the rest, and is saved`() {
        val home = Files.createTempDirectory("home")
        val fresh = Repl(FhirVersion.R4, emptyList(), Workspaces(home))
        fun line(text: String) = fresh.outputOf(text).lines().joinToString("\n")
        assertContains(line(":load samples/basic/bundle.json"), "pool now 3")
        assertContains(line(":load samples/basic"), "loaded 6 resource(s) from 3 file(s), 3 replaced; pool now 6")
        val state = Files.readAllLines(home.resolve("state"))
        assertTrue(state.any { it.startsWith("load\t") && it.endsWith("samples/basic") }, state.toString())
    }

    @Test
    fun `a file edited since it was read updates the pool, and the focus follows`() {
        val dir = Files.createTempDirectory("edited")
        val file = dir.resolve("p.ndjson")
        Files.writeString(file, """{"resourceType":"Patient","id":"a","name":[{"family":"A"}]}""" + "\n" + """{"resourceType":"Patient","id":"b",""")
        assertContains(run(":load $file"), "loaded 1 resource(s) from 1 file(s); 1 problem(s); pool now 7")
        run(":focus Patient/a")

        // The broken line fixed, the other edited: both arrive, neither doubles.
        Files.writeString(file, """{"resourceType":"Patient","id":"a","name":[{"family":"A2"}]}""" + "\n" + """{"resourceType":"Patient","id":"b"}""")
        assertContains(run(":load $file"), "loaded 2 resource(s) from 1 file(s), 1 replaced; pool now 8")
        // The focus is found again, on the resource as it now reads, in its old place.
        assertContains(run("name.family"), "A2")
        assertContains(run(":focus #6"), "focus Patient/a")
    }

    private fun fresh(): Repl = Repl(FhirVersion.R4, emptyList(), Workspaces(null))

    private fun Repl.line(text: String) = outputOf(text).lines().joinToString("\n")

    @Test
    fun `one resource read twice in a load keeps the last read, and says so`() {
        val dir = Files.createTempDirectory("twice")
        Files.writeString(dir.resolve("a.json"), """{"resourceType":"Patient","id":"p1","birthDate":"1970-01-01"}""")
        Files.writeString(dir.resolve("b.json"), """{"resourceType":"Patient","id":"p1","name":[{"family":"B"}]}""")
        val repl = fresh()
        val loaded = repl.line(":load $dir")
        assertContains(loaded, "loaded 2 resource(s) from 2 file(s); pool now 1")
        assertContains(loaded, "Patient/p1 read 2 times in this load — the last read is kept")
        assertFalse("replaced" in loaded, loaded)
        assertContains(repl.line("Patient.name.family"), "B")
    }

    @Test
    fun `a resource with neither id nor fullUrl is added again`() {
        val dir = Files.createTempDirectory("idless")
        Files.writeString(dir.resolve("p.json"), """{"resourceType":"Patient","name":[{"family":"X"}]}""")
        val repl = fresh()
        assertContains(repl.line(":load $dir"), "pool now 1")
        assertContains(repl.line(":load $dir"), "pool now 2")
    }

    @Test
    fun `a urn-only bundle loaded again replaces each resource, and resolve still works`() {
        val repl = fresh()
        repl.line(":load samples/references/transaction.json")
        val before = repl.line("Observation.subject.resolve()")
        assertContains(before, "3 of 6 resource(s) matched")
        assertContains(repl.line(":load samples/references/transaction.json"), "6 replaced; pool now 6")
        // References are found again in the rebuilt pool, by the urn each resource is known by.
        assertEquals(before, repl.line("Observation.subject.resolve()"))
    }

    @Test
    fun `a replacing load keeps the listing, drops the picks, and restores a focus quietly`() {
        val dir = Files.createTempDirectory("reload")
        val file = dir.resolve("p.json")
        Files.writeString(file, """{"resourceType":"Patient","id":"p","birthDate":"1970-01-01","name":[{"given":["A","B"]}]}""")
        val repl = fresh()
        repl.line(":load $dir")
        repl.line(":focus Patient/p")
        assertContains(repl.line("name.given"), "[1]")
        val reloaded = repl.line(":load $dir")
        // Back where it was: nothing to say about the focus.
        assertFalse("focus " in reloaded, reloaded)
        assertContains(repl.line(":focus [0]"), "nothing numbered to pick from")
        assertContains(repl.line(":more"), "B")

        repl.line(":focus birthDate")
        Files.writeString(file, """{"resourceType":"Patient","id":"p"}""")
        assertContains(repl.line(":load $dir"), "focus restored only as far as Patient/p — birthDate no longer reaches one value")
    }
}
