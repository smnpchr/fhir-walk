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

    private fun commit(change: () -> Unit) {
        change()
        commit()
    }

    private fun remove(path: String) {
        git.rm().addFilepattern(path).call()
    }

    /** A git pool over the repository, not loaded yet, with the session it fills. */
    private fun unloaded(): Pair<Session, GitPool> {
        val session = Session(FhirVersion.R4)
        val source = GitSource.open(root).also { opened += it }
        return session to GitPool(source, "main", session.importer, session.pool)
    }

    /** A pool at main's tip, with the session it fills. */
    private fun loaded(): Pair<Session, GitPool> = unloaded().also { (_, pool) -> pool.load(pool.source.resolve("main")!!) }

    private fun main(back: Int = 0) = "main" + if (back == 0) "" else "~$back"

    /** No instance in [pool] twice, and each maps back to its own entry. */
    private fun assertOwnInstances(pool: dev.fhirwalk.pool.ResourcePool) {
        val seen = IdentityHashMap<Any, Boolean>()
        pool.all().forEach { assertTrue(seen.put(it.resource, true) == null, "${it.label} (${it.origin}) shares an instance") }
        pool.all().forEach { assertTrue(pool.entryFor(it.resource) === it, it.toString()) }
    }

    private val unnamed = """{"resourceType":"Patient","name":[{"family":"X"}]}"""

    @Test
    fun `a copy is its own file, with its own resources`() {
        write("a.json", bundle)
        write("copy.json", bundle)
        commit()
        val (session, _) = loaded()
        val origins = session.pool.all().map { it.origin }
        assertEquals(listOf("a.json#entry[0]", "a.json#entry[1]", "copy.json#entry[0]", "copy.json#entry[1]"), origins)
        // No instance is held twice: resolve() maps each back to its own entry.
        assertOwnInstances(session.pool)
    }

    @Test
    fun `a copy's problems name the copy`() {
        write("broken.json", """{"resourceType":"Patient","id":""")
        write("broken-too.json", """{"resourceType":"Patient","id":""")
        commit()
        val (_, pool) = unloaded()
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
        // Only the copy is in the delta: its resources are added, named by the copy; the
        // untouched original, though the same blob, is neither paired with them nor named.
        assertTrue("(copy.json#entry[0])" in full && "(copy.json#entry[1])" in full, full)
        assertTrue(full.lines().none { "(a.json" in it }, full)
    }

    @Test
    fun `the base snapshot holds a copy as its own file too`() {
        write("a.json", bundle)
        write("copy.json", bundle)
        write("other.json", unnamed)
        commit()
        write("other.json", unnamed.replace("X", "Y"))
        commit()
        val (_, pool) = loaded()
        val base = pool.before().base.pool
        assertOwnInstances(base)
        assertEquals(setOf("a.json#entry[0]", "copy.json#entry[0]"), base.all().filter { it.key == "Patient/p1" }.mapTo(HashSet()) { it.origin })
    }

    @Test
    fun `a blob held twice, then once, then twice again, is never added twice`() {
        commit { write("a.json", bundle); write("copy.json", bundle) }
        commit { remove("copy.json") }
        commit { write("copy.json", bundle) }
        val (session, pool) = loaded()
        assertOwnInstances(session.pool)
        val parsed = pool.parsedBlobs
        repeat(2) {
            pool.step(-1)
            assertOwnInstances(session.pool)
        }
        repeat(2) {
            pool.step(+1)
            assertOwnInstances(session.pool)
        }
        // Each parse was kept: none of the moves parsed anything.
        assertEquals(parsed, pool.parsedBlobs)
    }

    @Test
    fun `a copy renamed between revisions is named by its new file`() {
        commit { write("a.json", bundle); write("copy.json", bundle) }
        commit { remove("copy.json"); write("renamed.json", bundle) }
        val (session, pool) = unloaded()
        pool.load(pool.source.resolve(main(1))!!)
        pool.load(pool.source.resolve(main())!!)
        assertEquals(setOf("a.json", "renamed.json"), session.pool.all().mapTo(HashSet()) { it.origin.substringBefore('#') })
        assertOwnInstances(session.pool)
    }

    @Test
    fun `a deleted file is named by itself, not by a copy that stays`() {
        commit { write("a.json", unnamed); write("copy.json", unnamed) }
        commit { remove("a.json") }
        val (_, pool) = loaded()
        assertEquals(listOf("Patient (a.json)"), pool.delta.deleted)
    }

    @Test
    fun `the same bytes under another format are parsed as that format`() {
        val lines = """{"resourceType":"Patient","id":"n1"}""" + "\n" + """{"resourceType":"Patient","id":"n2"}"""
        commit { write("a.ndjson", lines) }
        commit { remove("a.ndjson"); write("b.json", lines) }
        // Walked to, as a fresh load reads it: two lines of NDJSON are no JSON document.
        val (fresh, freshPool) = unloaded()
        val expected = freshPool.load(freshPool.source.resolve(main())!!).problems.size to fresh.pool.size
        val (session, pool) = unloaded()
        pool.load(pool.source.resolve(main(1))!!)
        val walked = pool.load(pool.source.resolve(main())!!)
        assertEquals(expected, walked.problems.size to session.pool.size)
        assertEquals(0, session.pool.size)
    }

    @Test
    fun `trace says a problem under each file that holds it`() {
        commit { write("a.json", """{"resourceType":"Patient","id":"p1"}""") }
        commit {
            write("a.json", """{"resourceType":"Patient","id":"p1",""")
            write("copy.json", """{"resourceType":"Patient","id":"p1",""")
        }
        val repl = Repl(FhirVersion.R4, emptyList(), Workspaces(null), "w1")
        repl.dispatch(":open reg $root@main", PrintWriter(StringWriter()))
        val trace = repl.outputOf(":trace Patient/p1").lines().joinToString("\n")
        assertTrue(trace.lines().any { it.startsWith("! ") && "a.json:" in it }, trace)
        assertTrue(trace.lines().any { it.startsWith("! ") && "copy.json:" in it }, trace)
    }

    @Test
    fun `every origin and problem the importer writes starts with the file's path`() {
        // What re-basing an origin onto another file relies on.
        val session = Session(FhirVersion.R4)
        val nested = """{"resourceType":"Bundle","type":"collection","entry":[{"resource":$bundle}]}"""
        val texts = mapOf(
            "dir/x.json" to nested,
            "dir/x.ndjson" to """{"resourceType":"Patient","id":"n1"}""" + "\n" + "not json",
            "dir/x.xml" to "<Patient",
            "dir/y.json" to "{",
        )
        for ((name, text) in texts) {
            val report = dev.fhirwalk.pool.Importer.Report()
            val parsed = session.importer.parse(name, text, report)
            (parsed.map { it.origin } + report.problems).forEach { assertTrue(it.startsWith(name), "$name: $it") }
        }
    }
}
