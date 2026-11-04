package dev.fhirwalk

import dev.fhirwalk.model.FhirVersion
import dev.fhirwalk.pool.GitSource
import dev.fhirwalk.repl.GitPool
import dev.fhirwalk.repl.Output
import dev.fhirwalk.repl.Repl
import dev.fhirwalk.repl.Session
import dev.fhirwalk.repl.Workspaces
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.time.Instant
import java.util.IdentityHashMap
import java.util.TimeZone
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Two files of one revision with the same content are one git blob. Each must still
 * be its own file in the pool: its own origin, its own problems, its own resources.
 */
class SharedBlobTest {

    private val root = Files.createTempDirectory("shared-blob")
    private val git = Git.init().setDirectory(root.toFile()).setInitialBranch("main").call()
    private var clock = Instant.parse("2026-10-01T08:00:00Z")
    private val opened = mutableListOf<GitSource>()

    @AfterTest
    fun close() = opened.forEach { it.close() }

    private fun write(path: String, text: String) {
        val file = root.resolve(path)
        Files.createDirectories(file.parent)
        Files.writeString(file, text + "\n")
    }

    private fun commit() {
        clock = clock.plusSeconds(3600)
        val who = PersonIdent(PersonIdent("fixture", "fixture@example.org"), clock.toEpochMilli(), TimeZone.getTimeZone("UTC").rawOffset)
        git.add().addFilepattern(".").call()
        git.commit().setMessage("change").setAuthor(who).setCommitter(who).call()
    }

    private val bundle = """{"resourceType":"Bundle","type":"collection","entry":[""" +
        """{"resource":{"resourceType":"Patient","id":"p1","name":[{"family":"A"}]}},""" +
        """{"resource":{"resourceType":"Patient","id":"p2","name":[{"family":"B"}]}}]}"""

    /** A pool at main's tip, with the session it fills. */
    private fun loaded(): Pair<Session, GitPool> {
        val session = Session(FhirVersion.R4)
        val source = GitSource.open(root).also { opened += it }
        val pool = GitPool(source, "main", session.importer, session.pool)
        pool.load(source.resolve("main")!!)
        return session to pool
    }

    @Test
    fun `a copy is its own file, with its own resources`() {
        write("a.json", bundle)
        write("copy.json", bundle)
        commit()
        val (session, _) = loaded()
        val origins = session.pool.all().map { it.origin }
        assertEquals(listOf("a.json#entry[0]", "a.json#entry[1]", "copy.json#entry[0]", "copy.json#entry[1]"), origins)
        // No instance is held twice: resolve() maps each back to its own entry.
        val seen = IdentityHashMap<Any, Boolean>()
        session.pool.all().forEach { assertTrue(seen.put(it.resource, true) == null, "${it.label} shares an instance") }
        session.pool.all().forEach { assertTrue(session.pool.entryFor(it.resource) === it, it.toString()) }
    }

    @Test
    fun `a copy's problems name the copy`() {
        write("broken.json", """{"resourceType":"Patient","id":""")
        write("broken-too.json", """{"resourceType":"Patient","id":""")
        commit()
        val (_, pool) = loaded()
        val report = pool.load(pool.source.resolve("main")!!)
        assertEquals(1, report.problems.count { it.startsWith("broken.json:") }, report.problems.toString())
        assertEquals(1, report.problems.count { it.startsWith("broken-too.json:") }, report.problems.toString())
    }

    @Test
    fun `a step still reuses the parses of a blob held twice`() {
        write("a.json", bundle)
        write("copy.json", bundle)
        write("other.json", """{"resourceType":"Patient","id":"o","name":[{"family":"O"}]}""")
        commit()
        write("other.json", """{"resourceType":"Patient","id":"o","name":[{"family":"O2"}]}""")
        commit()
        val (session, pool) = loaded()
        assertEquals(3, pool.parsedBlobs)
        pool.step(-1)
        // Only the other file differs.
        assertEquals(4, pool.parsedBlobs)
        pool.step(+1)
        assertEquals(4, pool.parsedBlobs)
        assertEquals(5, session.pool.all().mapTo(HashSet()) { System.identityHashCode(it.resource) }.size)
    }

    @Test
    fun `changes names each holder of a copied resource by its own file`() {
        write("a.json", bundle)
        commit()
        write("copy.json", bundle)
        commit()
        val repl = Repl(FhirVersion.R4, emptyList(), Workspaces(null), "w1")
        repl.dispatch(":open reg $root@main", PrintWriter(StringWriter()))
        val output = repl.outputOf(":changes Patient.name.family")
        val full = (output.listing as Output.Listing).full!!.joinToString("\n") { line -> line.spans.joinToString("") { it.text } }
        // Only the copy changed, but its labels are held twice now: set aside, each by its own file.
        assertTrue("(copy.json#entry[0])" in full, full)
    }
}
