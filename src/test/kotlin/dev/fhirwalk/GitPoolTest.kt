package dev.fhirwalk

import dev.fhirwalk.model.FhirVersion
import dev.fhirwalk.pool.GitSource
import dev.fhirwalk.repl.GitPool
import dev.fhirwalk.repl.Repl
import dev.fhirwalk.repl.Session
import dev.fhirwalk.repl.Workspaces
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A pool that is a git revision of [RegistryFixture], stepped through line by line. */
class GitPoolTest {

    private val registry = RegistryFixture()
    private val home = Files.createTempDirectory("fhir-walk-home")

    private inner class Run(workspace: String = "w1", version: FhirVersion? = null) {
        val repl = Repl(version, emptyList(), Workspaces(home), workspace)
        val out: String = capture { repl.open(it) }

        fun run(line: String): String = capture { repl.dispatch(line, it) }
    }

    private fun capture(block: (PrintWriter) -> Unit): String {
        val text = StringWriter()
        PrintWriter(text).use(block)
        return text.toString()
    }

    private fun assertContains(output: String, expected: String) =
        assertTrue(output.contains(expected), "expected \"$expected\" in:\n$output")

    /** Opened at the tip of branch/out, as `spec`. */
    private fun opened(): Run = Run().apply { run(":open spec ${registry.spec}") }

    @Test
    fun `a git pool opens at the tip, reading only FHIR files under its directory`() {
        val output = Run().run(":open spec ${registry.spec}")
        assertContains(output, "pool spec at ${registry.out4.short} on branch/out")
        assertContains(output, "4 resource(s) from 4 file(s)")
    }

    @Test
    fun `log lists the line's first parents with what each changed`() {
        val log = opened().run(":log").lines()
        assertContains(log[0], "branch/out in ")
        assertContains(log[1], "* ${registry.out4.short}")
        assertContains(log[1], "Δ1")
        assertContains(log[3], "Δ2")
        assertContains(log[5], registry.m1.short)
        assertContains(log[5], "Δ3")
        assertEquals(6, log.count { it.isNotBlank() }, log.joinToString("\n"))
    }

    @Test
    fun `stepping back scopes to the delta, and resolve still sees everything`() {
        val session = opened()
        assertContains(session.run(":step"), "at ${registry.out3.short} on branch/out")
        assertContains(session.run(":delta"), "Δ1: 1 modified")
        assertContains(session.run("Specimen.type.coding.code"), "1 of 1 resource(s) matched")
        // The patient did not change in out3, but the changed specimen still reaches it.
        assertContains(session.run("Specimen.subject.resolve().name.family"), "F-p1")
        session.run(":name family")
        assertContains(session.run(":view family"), "1 Specimen row(s)")
    }

    @Test
    fun `scope all goes back to the whole revision`() {
        val session = opened()
        session.run(":step")
        assertContains(session.run(":scope"), "the delta, 1 of 5 resource(s)")
        assertContains(session.run(":scope all"), "all 5 resource(s)")
        assertContains(session.run("Specimen.type.coding.code"), "3 of 5 resource(s) matched")
        assertContains(session.run(":scope delta"), "the delta, 1 resource(s)")
    }

    @Test
    fun `a deletion is listed by label`() {
        val session = opened()
        val delta = session.run(":delta")
        assertContains(delta, "against ${registry.out3.short}")
        assertContains(delta, "- Patient/dummy")
    }

    @Test
    fun `stepping stops at either end`() {
        val session = opened()
        assertContains(session.run(":step +1"), "already at the tip of branch/out")
        assertContains(session.run(":step -4"), "at ${registry.m1.short}")
        assertContains(session.run(":delta"), "against nothing: the first commit — Δ3: 3 added")
        assertContains(session.run(":step -1"), "is the first commit")
        assertContains(session.run(":step +2"), "at ${registry.out2.short}")
        assertContains(session.run(":step +5"), "only 2 commit(s) after")
        assertContains(session.run(":step 2"), "usage: :step")
    }

    @Test
    fun `at a branch makes it the line`() {
        val session = opened()
        assertContains(session.run(":at master"), "at ${registry.m2.short} on master")
        assertContains(session.run(":delta"), "Δ2: 2 added")
        val log = session.run(":log")
        assertContains(log, "master in ")
        assertFalse(log.contains(registry.out1.short), log)
        // An id moves without changing the line.
        assertContains(session.run(":at ${registry.out1.name}"), "on master")
        assertContains(session.run(":step +1"), "not on master's first-parent history")
        assertContains(session.run(":at nope"), "no commit nope")
    }

    @Test
    fun `the focus is found again after a step`() {
        val session = opened()
        session.run(":focus Specimen/s2")
        val stepped = session.run(":step -2")
        assertContains(stepped, "focus Specimen/s2")
        assertContains(session.run("note.text"), "{} empty")
    }

    @Test
    fun `nothing is loaded into a git pool, and a plain pool has no revision`() {
        val session = opened()
        assertContains(session.run(":load samples/basic"), "is ${registry.import.toRealPath()}@branch/out")
        session.run(":open plain")
        assertContains(session.run(":step"), "not a git pool")
    }

    @Test
    fun `a directory outside any repository is refused and leaves no pool`() {
        val session = Run()
        val outside = Files.createTempDirectory("not-a-repo")
        assertContains(session.run(":open x $outside@master"), "not inside a git repository")
        assertContains(session.run(":pools"), "* pool")
        assertFalse(session.run(":pools").contains("x "))
    }

    @Test
    fun `a git pool comes back at the revision it was left at, with its scope`() {
        opened().run(":step -2")
        val state = Files.readAllLines(home.resolve("workspaces/w1/state"))
        assertTrue("source\t${registry.import.toRealPath()}@branch/out" in state, state.toString())
        assertTrue("at\t${registry.out2.name}" in state, state.toString())
        assertTrue("scope\tdelta" in state, state.toString())
        val next = Run()
        assertContains(next.out, "pool spec at ${registry.out2.short} on branch/out")
        assertContains(next.run(":scope"), "the delta")
    }

    @Test
    fun `a source that has gone is reported and stays saved`() {
        Files.createDirectories(home.resolve("workspaces/w1"))
        val lines = listOf("fhir\tr4", "pool\tpool", "pool\tspec", "source\t/no/such/dir@branch/out", "at\tabc")
        Files.write(home.resolve("workspaces/w1/state"), lines)
        val session = Run()
        assertContains(session.out, "not a directory: /no/such/dir")
        assertContains(session.out, "kept in the workspace")
        session.run(":limit 3")
        val state = Files.readAllLines(home.resolve("workspaces/w1/state"))
        assertTrue("source\t/no/such/dir@branch/out" in state, state.toString())
        assertContains(session.run(":pools"), "unavailable: /no/such/dir@branch/out")
    }

    @Test
    fun `switching version reads a git pool again at the same revision`() {
        val session = opened()
        session.run(":step")
        assertContains(session.run(":fhir r5"), "pool spec at ${registry.out3.short} on branch/out")
        assertContains(session.run(":scope"), "the delta")
    }

    @Test
    fun `a step parses only blobs the last revision did not hold`() {
        val session = Session(FhirVersion.R4)
        val git = GitPool(GitSource.open(registry.import), "branch/out", session.importer, session.pool)
        git.load(git.source.resolve("branch/out")!!)
        // The four files at the tip, and the deleted patient, to label it.
        assertEquals(5, git.parsedBlobs)
        git.step(-1)
        assertEquals(5, git.parsedBlobs)
        // out2 has the stool sample before the rule changed.
        git.step(-1)
        assertEquals(6, git.parsedBlobs)
        git.step(+1)
        assertEquals(6, git.parsedBlobs)
        git.source.close()
    }
}
