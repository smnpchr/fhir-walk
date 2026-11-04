package dev.fhirwalk

import dev.fhirwalk.model.FhirVersion
import dev.fhirwalk.repl.Output
import dev.fhirwalk.repl.Repl
import dev.fhirwalk.repl.Workspaces
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.revwalk.RevCommit
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.time.Instant
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `:trace` and `:log <label>` where a commit's changed files hold the label more than
 * once: the commit says so and nothing is followed through it, and every row's
 * "was" is what its own commit started from.
 */
class TraceDuplicatesTest {

    private val root = Files.createTempDirectory("trace-duplicates")
    private val git = Git.init().setDirectory(root.toFile()).setInitialBranch("main").call()
    private var clock = Instant.parse("2026-10-01T08:00:00Z")

    private fun patient(family: String) = """{"resourceType":"Patient","id":"p1","name":[{"family":"$family"}]}"""

    private fun bundle(vararg resources: String) =
        """{"resourceType":"Bundle","type":"collection","entry":[${resources.joinToString(",") { """{"resource":$it}""" }}]}"""

    private fun write(path: String, text: String) {
        val file = root.resolve(path)
        Files.createDirectories(file.parent)
        Files.writeString(file, text + "\n")
    }

    private fun commit(change: () -> Unit): RevCommit {
        change()
        clock = clock.plusSeconds(3600)
        val who = PersonIdent(PersonIdent("fixture", "fixture@example.org"), clock.toEpochMilli(), TimeZone.getTimeZone("UTC").rawOffset)
        git.add().addFilepattern(".").call()
        return git.commit().setMessage("change").setAuthor(who).setCommitter(who).call()
    }

    private fun remove(path: String) {
        git.rm().addFilepattern(path).call()
    }

    private fun opened(): Repl = Repl(FhirVersion.R4, emptyList(), Workspaces(null), "w1").also {
        it.dispatch(":open reg $root@main", PrintWriter(StringWriter()))
    }

    /** Each row's commit and what follows its time, as `TraceTest` reads them. */
    private fun rowsOf(output: Output): List<Pair<String, String>> = (output.listing as Output.Listing).rows.map { row ->
        row.spans.single { it.kind == Output.Kind.INDEX }.text to row.spans.drop(3).joinToString("") { it.text }.trim()
    }

    private fun assertContains(text: String, expected: String) = assertTrue(expected in text, "expected \"$expected\" in:\n$text")

    private fun full(output: Output): String =
        (output.listing as Output.Listing).full!!.joinToString("\n") { line -> line.spans.joinToString("") { it.text } }

    private val RevCommit.short: String get() = name.take(7)

    /** A row's text with the kind column's padding run together. */
    private fun spaced(text: String) = text.replace(Regex(" {2,}"), "  ")

    @Test
    fun `a commit holding the label twice is said, not followed, and the trace goes on`() {
        val added = commit { write("a.json", patient("A")) }
        val copied = commit { write("a.json", patient("A2")); write("copy.json", patient("B")) }
        val dropped = commit { write("a.json", patient("A3")); remove("copy.json") }
        val edited = commit { write("a.json", patient("A4")) }
        val repl = opened()

        val trace = repl.outputOf(":trace Patient/p1 name.family")
        assertEquals(
            listOf(
                edited.short to "A4  (was A3)",
                // Held once after: that side is unambiguous, and says what the label became.
                dropped.short to "A3  (held 2 times before — not followed)",
                copied.short to "held 2 times after — not followed",
                added.short to "A  (added)",
            ),
            rowsOf(trace),
        )
        assertContains(trace.lines().first(), "2 change(s), 2 not followed in 4 commit(s)")
        assertContains(trace.lines()[1], ":more lists the files that held it more than once")
        val more = full(trace)
        assertTrue("after: a.json, copy.json" in more, more)
        assertTrue("before: a.json, copy.json" in more, more)
        // :more itself does not point at :more.
        assertTrue(repl.outputOf(":more").lines().none { ":more lists" in it })

        assertEquals(
            listOf("modified", "held ×2", "held ×2", "added"),
            rowsOf(repl.outputOf(":log Patient/p1")).map { spaced(it.second).substringBefore("  ") },
        )
        // Without an expression, the row says it is not followed rather than previewing a copy.
        val whole = rowsOf(repl.outputOf(":trace Patient/p1")).map { spaced(it.second) }
        assertTrue("held ×2  held 2 times after — not followed" in whole, whole.toString())
    }

    @Test
    fun `copies that read the same on both sides are no change to the label`() {
        val other = """{"resourceType":"Patient","id":"p2","name":[{"family":"X"}]}"""
        val changed = """{"resourceType":"Patient","id":"p2","name":[{"family":"Y"}]}"""
        val added = commit { write("a.json", bundle(patient("A"), other)); write("copy.json", bundle(patient("A"), other)) }
        commit { write("a.json", bundle(patient("A"), changed)); write("copy.json", bundle(patient("A"), changed)) }
        assertEquals(listOf(added.short to "held ×2  change"), rowsOf(opened().outputOf(":log Patient/p1")).map { it.first to spaced(it.second) })
    }

    @Test
    fun `a row's was is what its own commit started from, not the row before it`() {
        val added = commit { write("a.json", patient("A")) }
        // A copy beside an untouched original: the commit added a resource with this label.
        val copied = commit { write("copy.json", patient("B")) }
        val edited = commit { write("a.json", patient("A2")) }
        assertEquals(
            listOf(edited.short to "A2  (was A)", copied.short to "B  (added)", added.short to "A  (added)"),
            rowsOf(opened().outputOf(":trace Patient/p1 name.family")),
        )
    }

    @Test
    fun `after a held commit, what the label became is still said`() {
        commit { write("a.json", patient("D")) }
        commit { write("copy.json", patient("Q")) }
        val dropped = commit { write("a.json", patient("E")); remove("copy.json") }
        // Changes the resource but not the answer: no row, so the held row must say E.
        commit { write("a.json", """{"resourceType":"Patient","id":"p1","gender":"female","name":[{"family":"E"}]}""") }
        val rows = rowsOf(opened().outputOf(":trace Patient/p1 name.family"))
        assertEquals(dropped.short to "E  (held 2 times before — not followed)", rows.first())
    }

    @Test
    fun `two copies in one bundle are held, and listed by the file with a count`() {
        commit { write("b.json", bundle(patient("A"))) }
        val doubled = commit { write("b.json", bundle(patient("A"), patient("B"))) }
        val repl = opened()
        val trace = repl.outputOf(":trace Patient/p1 name.family")
        assertEquals(doubled.short to "held 2 times after — not followed", rowsOf(trace).first())
        assertContains(full(trace), "after: b.json ×2")
    }

    @Test
    fun `a copy deleted beside an untouched original reads as deleted`() {
        val added = commit { write("a.json", patient("A")) }
        val copied = commit { write("copy.json", patient("Z")) }
        val dropped = commit { remove("copy.json") }
        val edited = commit { write("a.json", patient("D")) }
        val repl = opened()
        val trace = repl.outputOf(":trace Patient/p1 name.family")
        assertEquals(
            listOf(edited.short to "D  (was A)", dropped.short to "deleted  (was Z)", copied.short to "Z  (added)", added.short to "A  (added)"),
            rowsOf(trace),
        )
        // On :more, what the edit started from is not the row below it, so it is said.
        val more = full(trace)
        assertContains(more.substringAfter(edited.short).substringBefore(dropped.short), "was")
    }

    @Test
    fun `an answer that changes back is a row each time, and the same failure is none`() {
        val a = commit { write("a.json", patient("A")) }
        val b = commit { write("a.json", patient("B")) }
        val back = commit { write("a.json", patient("A")) }
        assertEquals(
            listOf(back.short to "A  (was B)", b.short to "B  (was A)", a.short to "A  (added)"),
            rowsOf(opened().outputOf(":trace Patient/p1 name.family")),
        )
    }

    @Test
    fun `the same failure on both sides of a commit is no row`() {
        val twoNames = """{"resourceType":"Patient","id":"p1","name":[{"family":"A","text":"T"}]}"""
        val added = commit { write("a.json", twoNames) }
        commit { write("a.json", twoNames.replace("\"id\":\"p1\"", "\"id\":\"p1\",\"gender\":\"male\"")) }
        val rows = rowsOf(opened().outputOf(":trace Patient/p1 (name.family | name.text).single()"))
        assertEquals(listOf(added.short), rows.map { it.first }, rows.toString())
        assertTrue(rows.single().second.startsWith("! "), rows.toString())
    }

    @Test
    fun `a label held on deletion is said, and log pages across it`() {
        val added = commit { write("a.json", patient("A")); write("copy.json", patient("A")) }
        val gone = commit { remove("a.json"); remove("copy.json") }
        val repl = opened()
        assertEquals(
            listOf(gone.short to "deleted  (held 2 times before — not followed)", added.short to "held 2 times after — not followed"),
            rowsOf(repl.outputOf(":trace Patient/p1 name.family")),
        )
        val page = repl.outputOf(":log Patient/p1 1 +1")
        assertEquals(listOf(added.short to "held ×2  change"), rowsOf(page).map { it.first to spaced(it.second) })
        assertContains(full(page), "after: a.json, copy.json")
    }
}
