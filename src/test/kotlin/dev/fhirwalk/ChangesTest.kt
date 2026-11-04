package dev.fhirwalk

import dev.fhirwalk.model.FhirVersion
import dev.fhirwalk.repl.Output
import dev.fhirwalk.repl.Repl
import dev.fhirwalk.repl.Workspaces
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.time.Instant
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * How `:changes` pairs what a delta touched when a label does not name one resource
 * on each side: duplicates, resources without id, files holding several, resources
 * that moved. Each test commits its own two revisions; the pool is at the second,
 * compared with the first.
 */
class ChangesTest {

    private val root = Files.createTempDirectory("changes-registry")
    private val git = Git.init().setDirectory(root.toFile()).setInitialBranch("main").call()
    private var clock = Instant.parse("2026-10-01T08:00:00Z")

    private fun write(path: String, text: String) {
        val file = root.resolve(path)
        Files.createDirectories(file.parent)
        Files.writeString(file, text + "\n")
    }

    private fun remove(path: String) {
        git.rm().addFilepattern(path).call()
    }

    private fun commit() {
        clock = clock.plusSeconds(3600)
        val who = PersonIdent(PersonIdent("fixture", "fixture@example.org"), clock.toEpochMilli(), TimeZone.getTimeZone("UTC").rawOffset)
        git.add().addFilepattern(".").call()
        git.commit().setMessage("change").setAuthor(who).setCommitter(who).call()
    }

    private fun patient(id: String?, family: String, extra: String = "") =
        """{"resourceType":"Patient"${id?.let { ""","id":"$it"""" }.orEmpty()},"name":[{"family":"$family"}]$extra}"""

    /** The repository as committed so far, opened at main's tip. */
    private fun opened(): Repl = Repl(FhirVersion.R4, emptyList(), Workspaces(null), "w1").also {
        it.dispatch(":open reg $root@main", PrintWriter(StringWriter()))
    }

    private fun Repl.run(line: String): String = outputOf(line).lines().joinToString("\n")

    private fun header(output: Output): String = output.lines().first { !it.startsWith("! ") }

    private fun pairsOf(output: Output): List<Pair<String, String>> = (output.listing as Output.Listing).rows.map { row ->
        row.spans.single { it.kind == Output.Kind.COUNT }.text.trim() to row.spans.last().text
    }

    private fun full(output: Output): String =
        (output.listing as Output.Listing).full!!.joinToString("\n") { line -> line.spans.joinToString("") { it.text } }

    private fun assertContains(text: String, expected: String) = assertTrue(text.contains(expected), "expected \"$expected\" in:\n$text")

    @Test
    fun `a label held twice is set aside, never paired across files`() {
        write("a.json", patient("p1", "A"))
        write("b.json", patient("p1", "B"))
        commit()
        write("a.json", patient("p1", "A2"))
        write("b.json", patient("p1", "B2"))
        commit()
        val output = opened().outputOf(":changes Patient.name.family")
        assertContains(header(output), "0 Patient(s) in the delta")
        assertContains(header(output), "not compared: Patient/p1 held more than once — :more lists them")
        assertEquals(emptyList(), pairsOf(output))
        val full = full(output)
        listOf("not compared, a label held more than once on a side:", "before", "after", "(a.json)", "(b.json)").forEach { assertContains(full, it) }
    }

    @Test
    fun `an added resource is added, though a file left alone holds its label`() {
        write("e.json", patient("r", "R"))
        commit()
        write("f.json", patient("r", "S"))
        commit()
        val output = opened().outputOf(":changes Patient.name.family")
        assertContains(header(output), "1 Patient(s) in the delta")
        assertEquals(listOf("1" to "— → S"), pairsOf(output))
    }

    @Test
    fun `a resource without id is set aside, counted once`() {
        write("c.ndjson", patient(null, "X"))
        commit()
        write("c.ndjson", patient(null, "X", extra = ""","gender":"female""""))
        commit()
        val output = opened().outputOf(":changes Patient.name.family")
        assertContains(header(output), "0 Patient(s) in the delta")
        assertContains(header(output), "not compared: 1 resource(s) without id or fullUrl")
        assertContains(full(output), "not compared, without id or fullUrl:")
    }

    @Test
    fun `a resource that moved file pairs with itself`() {
        write("g.json", patient("m", "M"))
        commit()
        remove("g.json")
        write("h.json", patient("m", "M"))
        commit()
        assertContains(opened().run(":changes Patient.name.family"), "1 Patient(s) in the delta from ")
        assertContains(opened().run(":changes Patient.name.family"), "; no answer changed")
    }

    @Test
    fun `a file of several resources pairs each one`() {
        fun bundle(second: String) = """{"resourceType":"Bundle","type":"collection","entry":[""" +
            """{"resource":${patient("b1", "B1")}},{"resource":${patient("b2", second)}}]}"""
        write("bundle.json", bundle("B2"))
        commit()
        write("bundle.json", bundle("B2x"))
        commit()
        val output = opened().outputOf(":changes Patient.name.family")
        assertContains(header(output), "2 Patient(s) in the delta")
        assertContains(header(output), "; 1 answer(s) changed")
        assertEquals(listOf("1" to "B2 → B2x"), pairsOf(output))
    }

    @Test
    fun `a value of another type with the same text is a change, in changes and trace`() {
        write("o.json", """{"resourceType":"Observation","id":"o1","status":"final","code":{"text":"n"},"valueString":"5"}""")
        commit()
        write("o.json", """{"resourceType":"Observation","id":"o1","status":"final","code":{"text":"n"},"valueInteger":5}""")
        commit()
        val repl = opened()
        val output = repl.outputOf(":changes Observation.value")
        assertContains(header(output), "1 answer(s) changed")
        val (count, pair) = pairsOf(output).single()
        assertEquals("1", count)
        // `5 → 5` would say nothing: the two are spelled with their types.
        assertTrue(pair.contains("string") && pair.contains("integer"), pair)
        val trace = repl.run(":trace Observation/o1 value")
        assertContains(trace, "2 change(s)")
    }

    @Test
    fun `only the touched files' problems are reported, once`() {
        write("broken.json", """{"resourceType":"Patient","id":""")
        write("bad.json", """{"resourceType":"Patient","id":"bad",""")
        write("p.json", patient("p", "P"))
        commit()
        write("p.json", patient("p", "Q"))
        write("bad.json", patient("bad", "Fixed"))
        commit()
        val output = opened().run(":changes Patient.name.family")
        assertFalse(output.contains("broken.json"), output)
        assertEquals(1, output.lines().count { it.contains("bad.json") }, output)
    }

    @Test
    fun `a root spelled in the wrong case is named`() {
        write("p.json", patient("p", "P"))
        commit()
        write("p.json", patient("p", "Q"))
        commit()
        assertContains(opened().run(":changes patient.name.family"), "patient is not a resource type — FHIRPath spells it Patient")
    }
}
