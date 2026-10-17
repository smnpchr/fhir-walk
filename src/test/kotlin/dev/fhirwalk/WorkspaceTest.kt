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
 * One session leaves state in a workspace, the next picks it up: each test runs two
 * or more REPLs against the same temporary home.
 */
class WorkspaceTest {

    private val home = Files.createTempDirectory("fhir-walk-home")
    private val workspaces = Workspaces(home)
    private val basic = Paths.get("samples/basic").toAbsolutePath().normalize().toString()

    /** A REPL, opened; [Session.out] holds what opening it printed. */
    private inner class Session(
        workspace: String = "w1",
        version: FhirVersion? = null,
        paths: List<String> = emptyList(),
        fresh: Boolean = false,
    ) {
        val repl = Repl(version, paths, workspaces, workspace, fresh)
        val out: String = capture { repl.open(it) }

        fun run(line: String): String = capture { repl.dispatch(line, it) }
    }

    private fun capture(block: (PrintWriter) -> Unit): String {
        val text = StringWriter()
        PrintWriter(text).use(block)
        return text.toString()
    }

    private fun stateFile(workspace: String = "w1") = home.resolve("workspaces").resolve(workspace).resolve("state")

    private fun assertContains(output: String, expected: String) =
        assertTrue(output.contains(expected), "expected \"$expected\" in:\n$output")

    @Test
    fun `the pool, the limit and the focus come back`() {
        Session().apply {
            run(":load samples/basic")
            run(":limit 5")
            run(":focus Patient/pat1")
            run("name")
            run(":focus [1]")
        }
        val next = Session()
        assertContains(next.out, "loaded 6 resource(s)")
        assertContains(next.out, "focus Patient/pat1.name[1]")
        assertContains(next.run(":limit"), "rows per listing: 5")
        // The chain is restored too, not just the last step.
        assertContains(next.run(":focus .."), "focus Patient/pat1 ")
    }

    @Test
    fun `a pool-wide element focus comes back by way of its resource`() {
        Session().apply {
            run(":load samples/basic")
            run(":focus Observation.code.coding")
            run(":focus [1]")
        }
        assertContains(Session().out, "focus Observation/obs2.code.coding")
    }

    @Test
    fun `a missing path is reported and stays saved`() {
        Files.createDirectories(stateFile().parent)
        Files.writeString(stateFile(), "fhir\tr4\nload\t/no/such/dir\nload\t$basic\n")
        val session = Session()
        assertContains(session.out, "/no/such/dir is missing")
        assertContains(session.out, "loaded 6 resource(s)")
        session.run(":limit 7")
        assertEquals(listOf("fhir\tr4", "load\t/no/such/dir", "load\t$basic", "limit\t7"), Files.readAllLines(stateFile()))
    }

    @Test
    fun `a focus step that no longer resolves ends the focus where it got to`() {
        Files.createDirectories(stateFile().parent)
        Files.writeString(stateFile(), "load\t$basic\nfocus\tPatient/pat1\nfocus\tnope.gone\n")
        val session = Session()
        assertContains(session.out, "focus restored only as far as Patient/pat1 — nope.gone no longer reaches one value")
        assertContains(session.out, "focus Patient/pat1")
    }

    @Test
    fun `fresh starts empty and leaves the saved state until something changes`() {
        Session().run(":load samples/basic")
        val saved = Files.readAllLines(stateFile())
        val fresh = Session(fresh = true)
        assertFalse(fresh.out.contains("loaded"), fresh.out)
        fresh.run(":pool")
        assertEquals(saved, Files.readAllLines(stateFile()))
        fresh.run(":limit 3")
        assertEquals(listOf("fhir\tr4", "limit\t3"), Files.readAllLines(stateFile()))
    }

    @Test
    fun `paths on the command line load on top and are saved`() {
        Session().run(":load samples/basic/bundle.json")
        val next = Session(paths = listOf("samples/basic/tree"))
        assertEquals(2, next.out.lines().count { it.startsWith("loaded") }, next.out)
        assertEquals(
            listOf("load\t$basic/bundle.json", "load\t$basic/tree"),
            Files.readAllLines(stateFile()).filter { it.startsWith("load") },
        )
    }

    @Test
    fun `switching version forgets the paths`() {
        Session().apply {
            run(":load samples/basic")
            run(":fhir r5")
        }
        assertEquals(listOf("fhir\tr5"), Files.readAllLines(stateFile()))
        val next = Session()
        assertContains(next.out, "FHIR R5")
        // The command line's version wins over the workspace's.
        assertContains(Session(version = FhirVersion.R4).out, "FHIR R4")
    }

    @Test
    fun `workspaces keep their expressions, names and pools apart`() {
        val session = Session()
        session.run(":load samples/basic")
        session.run("Patient.name.family")
        session.run(":name family")
        assertContains(session.run(":workspace w2"), "workspace w2, FHIR R4 — new")
        assertContains(session.run(":history"), "nothing recorded yet")
        assertContains(session.run(":name"), "no names yet")
        assertContains(session.run(":pool"), "pool is empty")
        val back = session.run(":workspace w1")
        assertContains(back, "loaded 6 resource(s)")
        assertContains(session.run(":view family"), "Mustermann")
        assertContains(session.run(":workspace"), "workspace w1")
        assertEquals(listOf("default", "w1"), workspaces.available())
    }

    @Test
    fun `the default workspace is the home directory itself`() {
        Session(workspace = Workspaces.DEFAULT).apply {
            run(":load samples/basic")
            run("Patient.birthDate")
        }
        assertEquals(listOf("Patient.birthDate"), Files.readAllLines(home.resolve("expressions")))
        assertTrue(Files.exists(home.resolve("state")))
    }

    @Test
    fun `a saved view reruns with its columns renumbered against the log`() {
        val session = Session()
        session.run(":load samples/basic")
        session.run("Patient.name.family")
        session.run("Patient.birthDate")
        session.run(":name family #1")
        assertContains(session.run(":save people"), "nothing to save")
        session.run(":view family #2")
        assertContains(session.run(":save people"), "saved @people: 2 column(s)")
        val again = Session()
        assertContains(again.run(":view @people"), "#2 birthDate")
        // Prepending an entry by hand renumbers the log; the view follows.
        val log = home.resolve("workspaces/w1/expressions")
        Files.writeString(log, "Patient.gender\n" + Files.readString(log))
        assertContains(again.run(":view @people"), "#3 birthDate")
        // An expression gone from the log keeps its column, with no number.
        Files.writeString(log, "Patient.gender\n")
        val titles = again.run(":view @people").lines()[1]
        assertTrue(titles.contains("birthDate") && !titles.contains("#"), titles)
        assertContains(again.run(":save"), "@people")
        assertContains(again.run(":unsave people"), "forgot @people")
        assertContains(again.run(":view @people"), "no saved view people")
    }
}
