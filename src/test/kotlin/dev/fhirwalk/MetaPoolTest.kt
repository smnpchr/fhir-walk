package dev.fhirwalk

import dev.fhirwalk.model.FhirVersion
import dev.fhirwalk.repl.MetaPool
import dev.fhirwalk.repl.Repl
import dev.fhirwalk.repl.Workspaces
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Meta pools over an archive shaped like `<pipe>/<run>/bundle.json`: `specimen/r1`
 * holds all of `samples/basic/bundle.json`, `specimen/r2` only its Observation, whose
 * Patient is in r1 alone, and `rate/encounter.json` is a resource, not a Bundle.
 */
class MetaPoolTest {

    @TempDir
    lateinit var root: Path

    @TempDir
    lateinit var home: Path

    private val bundle: String = Paths.get("samples/basic/bundle.json").readText()

    private fun archive(): Path {
        root.resolve("specimen/r1").createDirectories().resolve("bundle.json").writeText(bundle)
        root.resolve("specimen/r2").createDirectories().resolve("bundle.json").writeText(
            """{"resourceType":"Bundle","type":"collection","entry":[{"fullUrl":"urn:uuid:33333333-3333-3333-3333-333333333333",
              "resource":{"resourceType":"Observation","id":"obs1","status":"final","subject":{"reference":"Patient/pat1"}}}]}""",
        )
        root.resolve("rate").createDirectories()
        Files.copy(Paths.get("samples/basic/tree/encounter.json"), root.resolve("rate/encounter.json"))
        return root
    }

    private fun repl(workspaces: Workspaces = Workspaces(null)) = Repl(FhirVersion.R4, emptyList(), workspaces)

    private val repl = repl()

    private fun Repl.run(line: String): String {
        val text = StringWriter()
        PrintWriter(text).use { dispatch(line, it) }
        return text.toString()
    }

    private fun run(line: String) = repl.run(line)

    private fun assertContains(output: String, expected: String) =
        assertTrue(output.contains(expected), "expected \"$expected\" in:\n$output")

    @Test
    fun `a meta pool holds every bundle under its directory, and skips what is not one`() {
        val root = archive()
        assertContains(run(":meta exports $root"), "meta pool exports: 2 bundle(s), 4 resource(s); 1 file(s) skipped, not a Bundle")
        assertContains(run(":pools"), "meta: $root, 2 bundle(s)")
        val members = run(":members")
        assertContains(members, "specimen-r1")
        assertContains(members, "specimen-r2")
        // The bundle's order: #0 is the first entry of the first bundle.
        assertContains(run(":focus #0"), "specimen-r1:Patient/pat1")
    }

    @Test
    fun `resolve stays inside the bundle a resource came from`() {
        run(":meta exports ${archive()}")
        val resolved = run("Observation.subject.resolve()")
        assertContains(resolved, "1 of 4 resource(s) matched, in 1 of 2 bundle(s)")
        assertContains(resolved, "specimen-r1:Observation/obs1")
        assertFalse(resolved.contains("specimen-r2:Observation/obs1"), resolved)
        run(":focus specimen-r2:Observation/obs1")
        assertContains(run("subject.resolve()"), "empty")
        run(":focus specimen-r1:Observation/obs1")
        assertContains(run("subject.resolve()"), "Patient")
    }

    @Test
    fun `scope narrows pool-wide evaluation to one bundle`() {
        run(":meta exports ${archive()}")
        assertContains(run(":scope specimen-r2"), "scope: bundle specimen-r2, 1 resource(s)")
        assertContains(run("Observation.status"), "1 of 1 resource(s) matched")
        assertContains(run(":view #1"), "1 Observation row(s)")
        assertContains(run(":scope nope"), "no bundle nope")
        assertContains(run(":scope all"), "scope: all 2 bundle(s), 4 resource(s)")
        assertContains(run("Observation.status"), "2 of 4 resource(s) matched, in 2 of 2 bundle(s)")
    }

    @Test
    fun `the cut-off is by modification time, and moving it reads the bundles again`() {
        val root = archive()
        Files.setLastModifiedTime(root.resolve("specimen/r1/bundle.json"), FileTime.from(Instant.parse("2026-01-01T00:00:00Z")))
        assertContains(run(":meta exports $root 2026-02"), "meta pool exports: 1 bundle(s), 1 resource(s); 1 older than 2026-02; 1 file(s) skipped")
        assertContains(run(":cutoff"), "cut-off: 2026-02")
        assertContains(run(":cutoff none"), "meta pool exports: 2 bundle(s), 4 resource(s)")
        assertContains(run(":cutoff soon"), "not a cut-off: soon")
        assertContains(run(":cutoff 99999999999999999999d"), "not a cut-off")
    }

    @Test
    fun `a definition is saved, and read only when asked for`() {
        val root = archive()
        val first = repl(Workspaces(home))
        first.open(PrintWriter(StringWriter()))
        first.run(":meta exports $root 30d")
        assertContains(first.run(":pools"), "exports")

        val second = repl(Workspaces(home))
        second.open(PrintWriter(StringWriter()))
        assertFalse(second.run(":pools").contains("exports"))
        assertContains(second.run(":meta"), "exports  not read")
        assertContains(second.run(":pool exports"), "meta pool exports is not read yet — :meta exports reads it")
        assertContains(second.run(":meta exports"), "meta pool exports: 2 bundle(s)")
        // Nothing of it is in the state file, if one was written at all.
        assertFalse(home.resolve("state").takeIf { Files.exists(it) }?.readText().orEmpty().contains("exports"))
        assertContains(home.resolve("metas").readText(), "exports\t$root\t30d")
    }

    @Test
    fun `nothing is loaded into a meta pool, and forgetting one closes it`() {
        run(":meta exports ${archive()}")
        assertContains(run(":load samples/basic"), "is a meta pool of")
        assertContains(run(":unmeta exports"), "forgot meta pool exports")
        assertContains(run(":pools"), "* pool")
        assertContains(run(":meta"), "no meta pools")
    }

    @Test
    fun `an open pool is not taken for a meta pool`() {
        assertContains(run(":meta pool ${archive()}"), "pool pool is open and is no meta pool")
        assertContains(run(":meta exports /no/such/dir"), "not a directory")
        assertContains(run(":meta nope"), "no meta pool nope")
    }

    @Test
    fun `a bundle that will not parse is a problem, not a file skipped`() {
        root.resolve("specimen/r1").createDirectories().resolve("bundle.json").writeText(bundle)
        root.resolve("specimen/r2").createDirectories().resolve("bundle.json").writeText("""{"resourceType":"Bundle","entry":[""")
        val read = run(":meta exports $root")
        assertContains(read, "meta pool exports: 1 bundle(s)")
        assertFalse(read.contains("not a Bundle"), read)
        assertContains(read, "! specimen/r2/bundle.json: ")
    }

    @Test
    fun `a scope follows its file when the names change, and goes with it`() {
        root.resolve("a").createDirectories().resolve("x.json").writeText(bundle)
        root.resolve("b").createDirectories().resolve("x.json").writeText(bundle)
        run(":meta exports $root")
        run(":scope a")
        // A file arriving at the top renames them all: a.json is now `a`.
        root.resolve("a.json").writeText(bundle)
        run(":meta exports")
        assertContains(run(":scope"), "scope: bundle a-x")
        Files.delete(root.resolve("a/x.json"))
        assertContains(run(":meta exports"), "scope on a-x dropped")
        assertContains(run(":scope"), "scope: all 2 bundle(s)")
    }

    @Test
    fun `a directory that has gone is kept, and does not stop a version switch`() {
        val root = archive()
        run(":meta exports $root")
        run(":scope specimen-r2")
        assertContains(run(":fhir r5"), "meta pool exports: 2 bundle(s)")
        assertContains(run(":scope"), "scope: bundle specimen-r2")
        root.toFile().deleteRecursively()
        val switched = run(":fhir r4")
        assertContains(switched, "switched to FHIR R4")
        assertContains(switched, "is not a directory — kept, to read once it is back")
        assertContains(run(":pools"), "meta: $root")
        assertContains(run(":meta exports"), "is not a directory")
    }

    @Test
    fun `a directory below that cannot be read is a problem, and the rest is read`() {
        val root = archive()
        val locked = root.resolve("locked").createDirectories()
        locked.resolve("bundle.json").writeText(bundle)
        locked.toFile().setReadable(false)
        try {
            val read = run(":meta exports $root")
            assertContains(read, "meta pool exports: 2 bundle(s)")
            assertContains(read, "! locked: unreadable")
        } finally {
            locked.toFile().setReadable(true)
        }
    }

    @Test
    fun `a broken bundle is reported on every read, though parsed once`() {
        root.resolve("r1").createDirectories().resolve("bundle.json").writeText(bundle)
        root.resolve("r2").createDirectories().resolve("bundle.json").writeText("""{"resourceType":"Bundle","entry":[""")
        assertContains(run(":meta exports $root"), "! r2/bundle.json: ")
        assertContains(run(":meta exports"), "! r2/bundle.json: ")
    }

    @Test
    fun `forgetting the only pool, a meta pool called pool, empties it`() {
        val alone = repl()
        alone.run(":meta x ${archive()}")
        alone.run(":close pool")
        alone.run(":meta pool $root")
        alone.run(":close x")
        assertContains(alone.run(":unmeta pool"), "emptied pool pool, the only one")
        val pools = alone.run(":pools")
        assertContains(pools, "nothing loaded")
        assertFalse(pools.contains("meta:"), pools)
    }

    @Test
    fun `a label in a meta pool names its bundle, unless the scope is on one`() {
        run(":meta exports ${archive()}")
        assertContains(run(":focus Patient/pat1"), "<bundle>:Patient/pat1")
        run(":scope specimen-r1")
        assertContains(run(":focus Patient/pat1"), "specimen-r1:Patient/pat1")
    }

    @Test
    fun `a mistyped cut-off is not taken for part of the directory`() {
        assertContains(run(":meta exports ${archive()} 30days"), "not a cut-off: 30days")
    }

    @Test
    fun `the commands a meta pool replaced say what does their job now`() {
        assertContains(run(":open-bundles $root"), ":open-bundles is gone — a tree of bundles is one meta pool")
        assertContains(run(":prune 30d"), ":prune is gone")
    }

    @Test
    fun `a bundle is named from what tells the paths apart`() {
        val root = Paths.get("/a")
        fun names(vararg files: String) = MetaPool.names(root, files.map { root.resolve(it) })
        assertEquals(listOf("in-r1", "in-r2"), names("in/r1/bundle.json", "in/r2/bundle.json"))
        assertEquals(listOf("bundle"), names("bundle.json"))
        assertEquals(listOf("b2026-10-20", "b2026-10-21"), names("2026-10-20/bundle.json", "2026-10-21/bundle.json"))
        assertEquals(listOf("a-b", "a-b-2"), names("a.b.json", "a b.json"))
    }
}
