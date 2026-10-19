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
        assertEquals(listOf("fhir\tr4", "limit\t7", "load\t/no/such/dir", "load\t$basic"), Files.readAllLines(stateFile()))
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
        session.run(":name-expr family")
        assertContains(session.run(":workspace w2"), "workspace w2, FHIR R4 — new")
        assertContains(session.run(":history"), "nothing recorded yet")
        assertContains(session.run(":name-expr"), "no names yet")
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
        session.run(":name-expr family #1")
        assertContains(session.run(":name-view people"), "nothing to save")
        session.run(":view family #2")
        assertContains(session.run(":name-view people"), "saved @people: 2 column(s)")
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
        assertContains(again.run(":name-view"), "@people")
        assertContains(again.run(":unname-view people"), "forgot @people")
        assertContains(again.run(":view @people"), "no saved view people")
    }

    @Test
    fun `switching to a workspace that does not exist does not create it`() {
        val session = Session()
        assertContains(session.run(":workspace typo"), "— new, created once something is saved")
        session.run(":workspace w1")
        assertFalse(Files.exists(home.resolve("workspaces/typo")))
        assertFalse("typo" in workspaces.available())
    }

    @Test
    fun `a session on another version restores neither paths nor focus, and keeps them saved`() {
        Files.createDirectories(stateFile().parent)
        Files.writeString(stateFile(), "fhir\tr5\nload\t$basic\nfocus\tPatient/pat1\n")
        val session = Session(version = FhirVersion.R4)
        assertContains(session.out, "the workspace was left on R5, so its 1 path(s) and focus are not restored in R4")
        assertFalse(session.out.contains("loaded"), session.out)
        assertEquals(listOf("fhir\tr5", "load\t$basic", "focus\tPatient/pat1"), Files.readAllLines(stateFile()))
        // A change replaces it, as :fhir would have.
        session.run(":limit 9")
        assertEquals(listOf("fhir\tr4", "limit\t9"), Files.readAllLines(stateFile()))
    }

    @Test
    fun `a focus step that changes nothing restores`() {
        Session().apply {
            run(":load samples/basic")
            run(":focus Patient/pat1")
            run(":focus name[0]")
            run(":focus \$this")
        }
        assertEquals(
            listOf("focus\tPatient/pat1", "focus\tname[0]", "focus\t\$this"),
            Files.readAllLines(stateFile()).filter { it.startsWith("focus") },
        )
        val next = Session()
        assertFalse(next.out.contains("restored only"), next.out)
        assertContains(next.out, "focus Patient/pat1.name[0]")
    }

    @Test
    fun `a saved view is saved and run with or without its at sign`() {
        val session = Session()
        session.run(":load samples/basic")
        session.run("Patient.name.family")
        session.run(":view #1")
        assertContains(session.run(":name-view @people"), "saved @people: 1 column(s)")
        assertContains(session.run(":view @people"), "Mustermann")
        assertContains(session.run(":unname-view @people"), "forgot @people")
    }

    @Test
    fun `a saved view follows a rebound name and regains a lost number`() {
        val session = Session()
        session.run(":load samples/basic")
        session.run("Patient.name.family")
        session.run("Patient.birthDate")
        session.run(":name-expr col #1")
        session.run(":view col #2")
        session.run(":name-view v")
        // The name now means something else: the column computes what its title says.
        session.run(":name-expr col #2")
        val rebound = session.run(":view @v")
        assertContains(rebound, "1979-02-28")
        assertFalse(rebound.contains("Schmidt"), rebound)
        // Saved again while #2 is gone from the log, then back: the number returns.
        val log = home.resolve("workspaces/w1/expressions")
        Files.writeString(log, "Patient.name.family\n")
        session.run(":unname-expr col")
        session.run(":view @v")
        session.run(":name-view v")
        Files.writeString(log, "Patient.name.family\nPatient.birthDate\n")
        assertContains(session.run(":view @v").lines()[1], "#2 birthDate")
    }

    @Test
    fun `pools come back with their paths and focus, and the current one`() {
        Session().apply {
            run(":load samples/basic")
            run(":focus Patient/pat1")
            run(":open tree samples/basic/tree")
            run(":focus Observation/obs2")
            run(":open empty")
            run(":pool tree")
        }
        assertEquals(
            listOf(
                "fhir\tr4",
                "load\t$basic", "focus\tPatient/pat1",
                "pool\ttree", "load\t$basic/tree", "focus\tObservation/obs2",
                "pool\tempty",
                "current\ttree",
            ),
            Files.readAllLines(stateFile()),
        )
        val next = Session()
        assertContains(next.out, "pool tree now 3")
        assertContains(next.out, "focus Observation/obs2 (pool tree)")
        assertContains(next.run(":pool"), "pool tree: 3 resource(s)")
        assertContains(next.run(":pool pool"), "focus Patient/pat1")
        assertContains(next.run(":pools"), "empty")
    }

    @Test
    fun `a closed default pool stays closed, and an empty one stays open`() {
        Session().apply {
            run(":open tree samples/basic/tree")
            run(":open other")
        }
        // Empty, with pools after it: only its line keeps it.
        assertEquals("pool\tpool", Files.readAllLines(stateFile())[1])
        assertContains(Session().run(":pools"), "  pool")
        Session().run(":close pool")
        val listed = Session().run(":pools")
        assertFalse(listed.contains("  pool "), listed)
        assertContains(listed, "  tree")
        assertContains(listed, "* other")
    }
}
