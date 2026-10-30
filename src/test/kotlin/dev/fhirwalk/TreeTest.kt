package dev.fhirwalk

import dev.fhirwalk.eval.PromptLine
import dev.fhirwalk.model.FhirVersion
import dev.fhirwalk.repl.Repl
import dev.fhirwalk.repl.Workspaces
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `:tree` over specimens linked by `parent`: s1 above s1a (above s1a1) and s1b; s8
 * below both s1 and s7; c1 and c2 each other's parent; d1's parent not in the
 * pool; s9 linked to nothing.
 */
class TreeTest {

    @TempDir
    lateinit var root: Path

    private val repl = Repl(FhirVersion.R4, emptyList(), Workspaces(null))

    private fun run(line: String): String {
        val text = StringWriter()
        PrintWriter(text).use { repl.dispatch(line, it) }
        return text.toString()
    }

    private fun assertContains(output: String, expected: String) =
        assertTrue(output.contains(expected), "expected \"$expected\" in:\n$output")

    private fun specimen(id: String, vararg parents: String): String =
        """{"resource":{"resourceType":"Specimen","id":"$id"""" +
            (if (parents.isEmpty()) "" else ""","parent":[${parents.joinToString(",") { """{"reference":"Specimen/$it"}""" }}]""") +
            "}}"

    private fun bundle(vararg entries: String): String =
        """{"resourceType":"Bundle","type":"collection","entry":[${entries.joinToString(",")}]}"""

    private fun specimens(): Path = root.resolve("specimens.json").also {
        it.writeText(
            bundle(
                specimen("s1"), specimen("s1a", "s1"), specimen("s1a1", "s1a"), specimen("s1b", "s1"),
                specimen("s7"), specimen("s8", "s1", "s7"), specimen("c1", "c2"), specimen("c2", "c1"),
                specimen("d1", "x"), specimen("s9"), """{"resource":{"resourceType":"Patient","id":"pat1"}}""",
            ),
        )
    }

    /** The rows of a listing, less the header. */
    private fun rows(output: String): List<String> = output.lines().drop(1).filter { it.isNotBlank() }

    @Test
    fun `a tree hangs each child below its parents, roots first`() {
        run(":load ${specimens()}")
        val tree = run(":tree Specimen.parent")
        assertEquals("9 resource(s) in 4 tree(s); 1 Specimen not linked", tree.lines().first())
        assertEquals(
            listOf(
                "  Specimen/s1",
                "    Specimen/s1a",
                "      Specimen/s1a1",
                "    Specimen/s1b",
                "    Specimen/s8",
                "  Specimen/s7",
                "    Specimen/s8  (shown under Specimen/s1)",
                "  Specimen/d1  ! refers to Specimen/x, not in the pool",
                "  Specimen/c1",
                "    Specimen/c2",
                "      Specimen/c1  ↻ cycle",
            ),
            rows(tree),
        )
    }

    @Test
    fun `a cycle without a root starts on the cycle, not at what hangs from it`() {
        root.resolve("cycle.json").writeText(bundle(specimen("c3", "c1"), specimen("c1", "c2"), specimen("c2", "c1")))
        run(":load ${root.resolve("cycle.json")}")
        val tree = run(":tree Specimen.parent")
        assertEquals("3 resource(s) in 1 tree(s)", tree.lines().first())
        assertEquals(
            listOf("  Specimen/c1", "    Specimen/c3", "    Specimen/c2", "      Specimen/c1  ↻ cycle"),
            rows(tree),
        )
    }

    @Test
    fun `a reference made twice is said once, and one to a contained resource as contained`() {
        val contained = """{"resource":{"resourceType":"Specimen","id":"p","contained":[{"resourceType":"Specimen","id":"k"}],""" +
            """"parent":[{"reference":"#k"}]}}"""
        root.resolve("odd.json").writeText(bundle(specimen("d1", "x", "x"), contained))
        run(":load ${root.resolve("odd.json")}")
        assertEquals(
            listOf("  Specimen/d1  ! refers to Specimen/x, not in the pool", "  Specimen/p  refers to contained #k"),
            rows(run(":tree Specimen.parent")),
        )
        val resources = run(":tree Specimen.contained")
        assertContains(resources, "1 resource(s) not in the pool, such as contained ones")
        assertFalse(resources.contains(":why"), resources)
    }

    @Test
    fun `tree takes FHIRPath only, and points a name or a number to tree-view`() {
        run(":load ${specimens()}")
        run(":tree Specimen.parent")
        assertContains(run(":history"), "Specimen.parent")
        run(":name-expr parents")
        val named = run(":tree parents")
        assertContains(named, "parents is a named expression — :tree-view parents")
        assertFalse(named.contains(":why"), named)
        assertContains(run(":tree #1"), "#1 is a :history number — :tree-view #1 runs it")
        // What links nothing is not recorded, and so not nameable.
        run(":tree Specimen.collection.collector")
        assertFalse(run(":history").contains("collector"))
    }

    /** `parents` names the link, `up` a column: the reference each specimen makes. */
    private fun named() {
        run(":load ${specimens()}")
        run(":tree Specimen.parent")
        run(":name-expr parents")
        run(":all Specimen.parent.reference")
        run(":name-expr up")
    }

    @Test
    fun `tree-view alone is the plain tree of a named or recorded link`() {
        named()
        val plain = run(":tree Specimen.parent")
        val viewed = run(":tree-view parents")
        assertEquals("parents = Specimen.parent: " + plain.lines().first(), viewed.lines().first())
        assertEquals(rows(plain), rows(viewed))
        assertEquals("#1 = Specimen.parent: " + plain.lines().first(), run(":tree-view #1").lines().first())
    }

    @Test
    fun `tree-view shows columns along the tree, and none on a row shown elsewhere`() {
        named()
        val view = run(":tree-view parents up").lines()
        assertTrue(view.any { it.matches(Regex("""\s+up""")) }, view.joinToString("\n"))
        assertTrue(view.any { it.matches(Regex("""  Specimen/s1""")) }, view.joinToString("\n"))
        assertTrue(view.any { it.matches(Regex("""      Specimen/s1a1\s+Specimen/s1a""")) }, view.joinToString("\n"))
        assertTrue(view.any { it.matches(Regex("""    Specimen/s8\s+Specimen/s1, Specimen/s7""")) }, view.joinToString("\n"))
        assertTrue("    Specimen/s8  (shown under Specimen/s1)" in view, view.joinToString("\n"))
        assertTrue("      Specimen/c1  ↻ cycle" in view, view.joinToString("\n"))
        assertTrue(view.any { it.matches(Regex("""  Specimen/d1\s+Specimen/x  ! refers to Specimen/x, not in the pool""")) }, view.joinToString("\n"))
        // A saved view's columns are the same columns.
        run(":view up")
        run(":name-view ups")
        assertEquals(view, run(":tree-view parents @ups").lines())
    }

    @Test
    fun `tree-view says what it cannot find or use`() {
        named()
        assertContains(run(":tree-view"), "usage: :tree-view <#n|name>")
        assertContains(run(":tree-view nope"), "no :history number or name nope")
        assertContains(run(":tree-view parents nope"), "no :history number or name nope")
        assertContains(run(":tree-view parents @nope"), "no saved view nope")
        assertContains(run(":tree-view @ups"), "a view gives columns, not the link")
        assertContains(run(":tree-view parents parents"), "not a primitive")
        run(":all Patient.id")
        run(":name-expr pid")
        assertContains(run(":tree-view parents pid"), "no Patient among the rows, which are Specimen")
    }

    @Test
    fun `a tree-view's columns are what name-view saves next`() {
        named()
        val view = run(":tree-view parents up")
        assertContains(run(":name-view tv"), "saved @tv: 1 column(s)")
        assertEquals(view, run(":tree-view parents @tv"))
    }

    @Test
    fun `more prints a tree-view's notes whole`() {
        root.resolve("a").createDirectories().resolve("bundle.json")
            .writeText(bundle(specimen("s1"), specimen("s7"), specimen("s8", "s1", "s7")))
        run(":meta exports $root")
        run(":tree Specimen.parent")
        run(":name-expr parents")
        run(":all Specimen.parent.reference")
        run(":name-expr up")
        assertContains(run(":tree-view parents up"), "    Specimen/s8  (shown under Specimen/s1)")
        assertContains(run(":more"), "    a-bundle:Specimen/s8  (shown under a-bundle:Specimen/s1)")
    }

    @Test
    fun `resolved resources link as references do`() {
        run(":load ${specimens()}")
        assertEquals(rows(run(":tree Specimen.parent")).filterNot { "refers to" in it }, rows(run(":tree Specimen.parent.resolve()")))
    }

    @Test
    fun `with a focus, only the tree below it`() {
        run(":load ${specimens()}")
        run(":focus Specimen/s1a")
        val tree = run(":tree Specimen.parent")
        assertEquals("2 resource(s) in 1 tree(s) below Specimen/s1a", tree.lines().first())
        assertEquals(listOf("  Specimen/s1a", "    Specimen/s1a1"), rows(tree))
        run(":focus Specimen/s9")
        assertContains(run(":tree Specimen.parent"), "Specimen/s9 links to nothing by Specimen.parent, and nothing to it")
    }

    @Test
    fun `what links nothing, or is no reference, says so`() {
        run(":load ${specimens()}")
        assertContains(run(":tree Specimen.collection.collector"), "nothing links by Specimen.collection.collector — :why")
        val ids = run(":tree Specimen.id")
        assertContains(ids, "10 value(s) not a Reference")
        assertFalse(ids.contains(":why"), ids)
        assertContains(run(":tree"), "usage: :tree <expr>")
    }

    @Test
    fun `in a meta pool a tree stays inside its bundle`() {
        root.resolve("a").createDirectories().resolve("bundle.json").writeText(bundle(specimen("s1"), specimen("s1a", "s1")))
        root.resolve("b").createDirectories().resolve("bundle.json").writeText(bundle(specimen("s1"), specimen("s2", "s1"), specimen("s3", "s1a")))
        run(":meta exports $root")
        val tree = run(":tree Specimen.parent")
        assertEquals("5 resource(s) in 3 tree(s), in 2 of 2 bundle(s)", tree.lines().first())
        assertEquals(
            listOf(
                "  a:Specimen/s1",
                "    Specimen/s1a",
                "  b:Specimen/s1",
                "    Specimen/s2",
                "  b:Specimen/s3  ! refers to Specimen/s1a, not in this bundle",
            ),
            rows(tree),
        )
        // Scoped to one bundle, the root leaves its name out too; :more keeps every label whole.
        run(":scope b")
        assertEquals("  Specimen/s1", rows(run(":tree Specimen.parent")).first())
        assertContains(run(":more"), "    b:Specimen/s2")
        assertFalse(rows(run(":tree Specimen.parent")).any { it.contains("a:") })
    }

    @Test
    fun `the argument of tree completes as an expression`() {
        assertEquals(PromptLine.Slice("Specimen.pa", 11, 6, ":tree"), PromptLine.expressionSlice(":tree Specimen.pa", 17))
    }
}
