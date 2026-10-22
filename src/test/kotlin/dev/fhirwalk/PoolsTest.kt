package dev.fhirwalk

import dev.fhirwalk.model.FhirVersion
import dev.fhirwalk.repl.Repl
import dev.fhirwalk.repl.Workspaces
import dev.fhirwalk.repl.command.PoolCommands
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import org.junit.jupiter.api.io.TempDir
import kotlin.io.path.writeText
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

    /** `publish/archive/<pipe>/<date>-<publishId>/bundle.json`, as a pipe archives what it sent, and a stray resource. */
    @TempDir
    lateinit var root: Path

    private fun archive(): Path {
        for (run in listOf("specimen/2026-10-20T12:00:00Z-p1", "specimen/2026-10-21T12:00:00Z-p2", "rate/2026-10-21T13:00:00Z-p3")) {
            Files.copy(Paths.get("samples/basic/bundle.json"), root.resolve(run).createDirectories().resolve("bundle.json"))
        }
        Files.copy(Paths.get("samples/basic/tree/encounter.json"), root.resolve("rate/encounter.json"))
        return root
    }

    private fun bundle(run: String) {
        Files.copy(Paths.get("samples/basic/bundle.json"), root.resolve(run).createDirectories().resolve("bundle.json"))
    }

    @Test
    fun `open-bundles opens a pool per bundle, named by its path`() {
        val root = archive()
        assertContains(
            run(":open-bundles $root"),
            "opened 3 pools, rate-2026-10-21T13-00-00Z-p3 … specimen-2026-10-21T12-00-00Z-p2; 1 file(s) skipped, not a Bundle",
        )
        assertContains(run(":pool"), "pool pool: 6 resource(s)")
        assertContains(run(":pool specimen-2026-10-20T12-00-00Z-p1"), "3 resource(s)")
        // The bundle's order: #0 is its first entry.
        val first = run(":focus #0")
        assertContains(first, "bundle.json#entry[0]")
        assertContains(run(":open-bundles $root"), "no new pools; 3 already open; 1 file(s) skipped")
    }

    @Test
    fun `a pool name is made from what tells the paths apart`() {
        val root = Paths.get("/a")
        fun names(vararg files: String) = PoolCommands.poolNames(root, files.map { root.resolve(it) })
        assertEquals(listOf("in-r1", "in-r2"), names("in/r1/bundle.json", "in/r2/bundle.json"))
        assertEquals(listOf("bundle"), names("bundle.json"))
        assertEquals(listOf("b2026-10-20", "b2026-10-21"), names("2026-10-20/bundle.json", "2026-10-21/bundle.json"))
        assertEquals(listOf("a-b", "a-b-2"), names("a.b.json", "a b.json"))
        assertEquals(listOf("pool-2"), PoolCommands.poolNames(root, listOf(root.resolve("pool.json")), setOf("pool")))
    }

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    @Test
    fun `prune closes the pools whose files are gone, or older than a cut-off`() {
        val root = archive()
        run(":open-bundles $root")
        run(":pool rate-2026-10-21T13-00-00Z-p3")
        assertContains(run(":prune"), "nothing to prune — 4 loaded pool(s), all present")
        root.resolve("rate").deleteRecursively()
        assertContains(run(":prune"), "pruned 1 pool(s); now in pool pool")
        Files.setLastModifiedTime(
            root.resolve("specimen/2026-10-20T12:00:00Z-p1/bundle.json"),
            FileTime.from(Instant.parse("2026-01-01T00:00:00Z")),
        )
        val pruned = run(":prune 2026-02")
        assertContains(pruned, "pruned 1 pool(s)")
        assertContains(pruned, "specimen-2026-10-20T12-00-00Z-p1")
        assertContains(run(":pools"), "specimen-2026-10-21T12-00-00Z-p2")
        assertContains(run(":prune soon"), "not a cut-off: soon")
    }

    @Test
    fun `a bundle that arrives later is opened alone, though it changes what the names share`() {
        bundle("specimen/r1")
        assertContains(run(":open-bundles $root"), "opened pool specimen-r1-bundle")
        bundle("specimen/r2")
        assertContains(run(":open-bundles $root"), "opened pool specimen-r2; 1 already open")
        root.resolve("specimen/r3").createDirectories().resolve("bundle-v2.json")
            .writeText(Files.readString(Paths.get("samples/basic/bundle.json")))
        assertContains(run(":open-bundles $root"), "opened pool specimen-r3-bundle-v2; 2 already open")
    }

    @Test
    fun `a bundle that will not parse is a problem, not a file skipped`() {
        bundle("specimen/r1")
        root.resolve("specimen/r2").createDirectories().resolve("bundle.json").writeText("""{"resourceType":"Bundle","entry":[""")
        val opened = run(":open-bundles $root")
        assertContains(opened, "opened pool specimen-r1")
        assertFalse(opened.contains("not a Bundle"), opened)
        assertContains(opened, "! specimen/r2/bundle.json: ")
    }

    @Test
    fun `an ndjson file holding a Bundle is not one`() {
        val line = Files.readString(Paths.get("samples/basic/bundle.json")).replace(Regex("\\s*\n\\s*"), "")
        root.resolve("export.ndjson").writeText(line + "\n")
        assertContains(run(":open-bundles $root"), "no new pools; 1 file(s) skipped, not a Bundle")
    }

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    @Test
    fun `prune keeps the only pool left`() {
        val alone = Repl(FhirVersion.R4, emptyList(), Workspaces(null))
        fun run(line: String) = StringWriter().also { text -> PrintWriter(text).use { alone.dispatch(line, it) } }.toString()
        bundle("specimen/r1")
        run(":open-bundles $root")
        run(":close pool")
        root.resolve("specimen").deleteRecursively()
        assertContains(run(":prune"), "kept specimen-r1-bundle, the only pool left")
        assertContains(run(":prune 99999999999999999999d"), "not a cut-off")
        assertContains(run(":prune 9999999999999999d"), "not a cut-off")
    }
}
