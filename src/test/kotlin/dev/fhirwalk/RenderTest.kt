package dev.fhirwalk

import dev.fhirwalk.eval.Evaluator
import dev.fhirwalk.eval.Outcome
import dev.fhirwalk.model.FhirVersion
import dev.fhirwalk.pool.Importer
import dev.fhirwalk.pool.ResourcePool
import dev.fhirwalk.repl.Render
import org.hl7.fhir.instance.model.api.IBase
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import java.nio.file.Paths
import kotlin.io.path.isDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RenderTest {

    private val ctx = FhirVersion.R4.create()
    private val pool = ResourcePool(ctx)
    private val evaluator = Evaluator(ctx, pool)
    private val render = Render(ctx)

    init {
        Importer(ctx, pool).load(FIXTURE)
    }

    /** Skips rather than fails when the local-only fixture is absent. */
    @BeforeEach
    fun requireFixture() {
        assumeTrue(
            FIXTURE.isDirectory(),
            "$FIXTURE is a local-only fixture and is not published; see CLAUDE.md",
        )
    }

    private companion object {
        val FIXTURE = Paths.get("samples/biobank")
    }

    private fun one(id: String, expression: String): IBase {
        val item = requireNotNull(pool.find(id)) { "not in pool: $id" }
        return when (val outcome = evaluator.evaluate(item.resource, expression)) {
            is Outcome.Values -> outcome.values.first()
            is Outcome.Failure -> error("$expression -> ${outcome.message}")
        }
    }

    @Test
    fun `a primitive stays a single line`() {
        val value = one("Specimen/spec-master-stool", "Specimen.identifier.value")
        assertEquals(listOf("ST-2026-0001"), render.pretty(value))
    }

    @Test
    fun `a structured value is indented over several lines`() {
        val value = one("Specimen/spec-master-stool", "Specimen.type")
        val lines = render.pretty(value)
        assertTrue(lines.size > 3, "expected indented output, got $lines")
        assertEquals("{", lines.first())
        assertEquals("}", lines.last())
        // Nesting shows up as leading whitespace.
        assertTrue(lines.any { it.startsWith("  \"coding\"") }, "got $lines")
    }

    @Test
    fun `indenting preserves the content exactly`() {
        val value = one("Specimen/spec-master-stool", "Specimen.type")
        val compact = render.value(value, 0).filterNot { it.isWhitespace() }
        val indented = render.pretty(value).joinToString("").filterNot { it.isWhitespace() }
        assertEquals(compact, indented)
    }

    @Test
    fun `punctuation inside a string does not split the line`() {
        // The master sample's note reads "Native stool, no additive. Aliquoted via
        // aliquot groups." -- a naive formatter breaks at that comma.
        val value = one("Specimen/spec-master-stool", "Specimen.note")
        val lines = render.pretty(value)
        assertTrue(
            lines.any { it.contains("Native stool, no additive.") },
            "a comma inside a string literal was treated as a separator: $lines",
        )
    }

    @Test
    fun `width zero never truncates and a small width does`() {
        val value = one("Specimen/spec-master-stool", "Specimen.type")
        val full = render.value(value, 0)
        assertTrue(!full.endsWith("…"), "unlimited width must not truncate")
        assertTrue(full.length > 40)

        val short = render.value(value, 30)
        assertEquals(30, short.length)
        assertTrue(short.endsWith("…"))
        // The shortened form is a prefix of the full one, so nothing is reordered.
        assertTrue(full.startsWith(short.dropLast(1)))
    }
}
