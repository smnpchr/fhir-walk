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
 * What each way of following one resource counts as a change, on a history built to
 * tell them apart: plain `:log` goes by the files git changed, `:log <label>` and
 * `:trace <label>` by the resource's content, `:trace <label> <expr>` by the answer.
 *
 * ```
 * added ── spacing ── reformat ── renamed ── reordered ── removed
 *          "Anna  Muster" → "Anna Muster", inside a string
 *                     the same resource, indented and its keys reordered
 *                                 the family, not the text
 *                                            the given names swapped
 * ```
 */
class TraceTest {

    private val root = Files.createTempDirectory("trace-registry")
    private val git = Git.init().setDirectory(root.toFile()).setInitialBranch("main").call()
    private var clock = Instant.parse("2026-10-01T08:00:00Z")

    private val added = patient("""{"resourceType":"Patient","id":"p1","name":[{"family":"Muster","text":"Anna  Muster"}]}""", "added")
    private val spacing = patient("""{"resourceType":"Patient","id":"p1","name":[{"family":"Muster","text":"Anna Muster"}]}""", "spacing")
    private val reformat = patient(
        """
        {
          "id": "p1",
          "resourceType": "Patient",
          "name": [ { "text": "Anna Muster", "family": "Muster" } ]
        }
        """.trimIndent(),
        "reformat",
    )
    private val renamed = patient(
        """{"resourceType":"Patient","id":"p1","name":[{"family":"Mustermann","given":["Anna","Maria"],"text":"Anna Muster"}]}""",
        "renamed",
    )
    private val reordered = patient(
        """{"resourceType":"Patient","id":"p1","name":[{"family":"Mustermann","given":["Maria","Anna"],"text":"Anna Muster"}]}""",
        "reordered",
    )
    private val removed = commit("removed") { git.rm().addFilepattern("Patient/p1.json").call() }

    private val repl = Repl(FhirVersion.R4, emptyList(), Workspaces(Files.createTempDirectory("trace-home")), "w1").also {
        it.dispatch(":open reg $root@main", PrintWriter(StringWriter()))
    }

    private fun patient(json: String, message: String): RevCommit = commit(message) {
        Files.createDirectories(root.resolve("Patient"))
        Files.writeString(root.resolve("Patient/p1.json"), json + "\n")
    }

    private fun commit(message: String, change: () -> Unit): RevCommit {
        change()
        clock = clock.plusSeconds(3600)
        val who = PersonIdent(PersonIdent("fixture", "fixture@example.org"), clock.toEpochMilli(), TimeZone.getTimeZone("UTC").rawOffset)
        git.add().addFilepattern(".").call()
        return git.commit().setMessage(message).setAuthor(who).setCommitter(who).call()
    }

    /** Each row's commit and what follows its time. */
    private fun rowsOf(line: String): List<Pair<String, String>> = (repl.outputOf(line).listing as Output.Listing).rows.map { row ->
        row.spans.single { it.kind == Output.Kind.INDEX }.text to row.spans.drop(3).joinToString("") { it.text }.trim()
    }

    private fun shorts(vararg commits: RevCommit) = commits.map { it.name.take(7) }

    @Test
    fun `log alone goes by the files git changed`() {
        val log = repl.outputOf(":log").lines().joinToString("\n")
        // The reformat changed the file, so git counts it, though the resource is the same.
        listOf(removed, reordered, renamed, reformat, spacing, added).forEach { assertTrue(it.name.take(7) in log, log) }
    }

    @Test
    fun `log and trace of a label go by the resource's content`() {
        // Order is content: the swapped names count, the reordered keys do not.
        val expected = shorts(removed, reordered, renamed, spacing, added)
        assertEquals(expected, rowsOf(":log Patient/p1").map { it.first })
        val trace = rowsOf(":trace Patient/p1")
        assertEquals(expected, trace.map { it.first })
        assertEquals(listOf("deleted", "modified", "modified", "modified", "added"), trace.map { it.second.substringBefore(' ') })
        // Whitespace inside a string is part of the value, on the row too.
        assertTrue("Anna  Muster" in trace.last().second, trace.last().second)
    }

    @Test
    fun `trace with an expression goes by the answer, whitespace in strings included`() {
        assertEquals(
            listOf(
                removed.name.take(7) to "deleted  (was Anna Muster)",
                spacing.name.take(7) to "Anna Muster  (was Anna  Muster)",
                added.name.take(7) to "Anna  Muster  (added)",
            ),
            rowsOf(":trace Patient/p1 name.text"),
        )
        assertEquals(shorts(removed, renamed, added), rowsOf(":trace Patient/p1 name.family").map { it.first })
        assertEquals(shorts(removed, reordered, renamed, added), rowsOf(":trace Patient/p1 name.given").map { it.first })
    }

    @Test
    fun `more prints a deleted row alone and a failed one as its error`() {
        repl.outputOf(":trace Patient/p1")
        val whole = repl.outputOf(":more").lines()
        val deleted = whole.indexOfFirst { removed.name.take(7) in it }
        assertTrue("deleted" in whole[deleted], whole.joinToString("\n"))
        // Nothing under a deletion: the next line is the commit before it.
        assertTrue(reordered.name.take(7) in whole[deleted + 1], whole.joinToString("\n"))

        val failed = rowsOf(":trace Patient/p1 (name.family | name.text).single()")
        assertTrue(failed.isNotEmpty() && failed.last().second.startsWith("! "), "$failed")
        val errors = repl.outputOf(":more").lines()
        assertTrue(errors.any { it.trim().startsWith("! ") }, errors.joinToString("\n"))
    }
}
