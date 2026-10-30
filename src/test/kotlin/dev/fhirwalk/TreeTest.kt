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
                specimen("d1", "x"), specimen("s9"),
            ),
        )
    }

    /** The rows of a listing, less the header. */
    private fun rows(output: String): List<String> = output.lines().drop(1).filter { it.isNotBlank() }

    @Test
    fun `a tree hangs each child below its parents, roots first`() {
        run(":load ${specimens()}")
        val tree = run(":tree Specimen.parent")
        assertEquals("9 resource(s) in 4 tree(s); 1 Specimen with no parent or child", tree.lines().first())
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
