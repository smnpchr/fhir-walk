package dev.fhirwalk

import dev.fhirwalk.eval.Evaluator
import dev.fhirwalk.eval.Focus
import dev.fhirwalk.eval.FocusResolver
import dev.fhirwalk.eval.FocusResolver.Resolution
import dev.fhirwalk.model.FhirVersion
import dev.fhirwalk.model.ModelNavigator
import dev.fhirwalk.pool.Importer
import dev.fhirwalk.pool.ResourcePool
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The rules behind `:focus`, against `samples/basic`. */
class FocusResolverTest {

    private val ctx = FhirVersion.R4.create()
    private val pool = ResourcePool(ctx)
    private val resolver: FocusResolver

    init {
        Importer(ctx, pool).apply {
            load(Paths.get("samples/basic/bundle.json"))
            load(Paths.get("samples/basic/tree"))
        }
        resolver = FocusResolver(Evaluator(ctx, pool), pool, ModelNavigator(ctx, pool.census)) {
            ctx.getElementDefinition(it.javaClass)!!.name
        }
    }

    private fun focusOn(label: String) = Focus.of(pool.find(label)!!)

    private fun focused(argument: String, current: Focus? = null): Focus =
        assertIs<Resolution.Focused>(resolver.resolve(argument, current)).focus

    @Test
    fun `labels win over the expression reading`() {
        // Also a valid expression: a division, which would yield nothing.
        assertEquals("Observation/obs2", focused("Observation/obs2").label)
        assertEquals("Observation/obs2", focused("#${pool.find("Observation/obs2")!!.index}").label)
        assertIs<Resolution.NotInPool>(resolver.resolve("#99", null))
    }

    @Test
    fun `a bare id is a label only while nothing is in focus`() {
        assertEquals("Patient/pat1", focused("pat1").label)
        // In focus, a bare word is a relative path; Patient has no element pat1.
        val result = resolver.resolve("pat1", focusOn("Patient/pat1"))
        assertIs<Resolution.Nothing>(result)
    }

    @Test
    fun `an expression drills into an element`() {
        val code = focused("code", focusOn("Observation/obs2"))
        assertFalse(code.isResource)
        assertEquals("CodeableConcept", code.typeName)
        assertEquals("Observation.code", code.path)

        val coding = focused("coding[0]", code)
        assertEquals("Observation/obs2.code.coding[0]", coding.label)
        assertEquals(code, coding.parent)
    }

    @Test
    fun `a resolved resource in the pool becomes a resource focus`() {
        val patient = focused("subject.resolve()", focusOn("Observation/obs2"))
        assertTrue(patient.isResource)
        assertEquals("Patient/pat1", patient.label)
    }

    @Test
    fun `the same resource reached from several is one result`() {
        // obs1 and obs2 both reference Patient/pat1: one target, not an ambiguity.
        val patient = focused("Observation.where(id != 'obs3').subject.resolve()")
        assertEquals("Patient/pat1", patient.label)
    }

    @Test
    fun `several values from one resource suggest an indexer`() {
        val pat1 = focusOn("Patient/pat1")
        val names = assertIs<Resolution.Several>(resolver.resolve("name", pat1))
        assertEquals(1, names.owners)
        assertEquals("pick it with  :focus name[0]", names.hint)

        // Unspaced or not, an operator means the indexer must wrap the whole thing.
        val union = assertIs<Resolution.Several>(resolver.resolve("name|contact.name", pat1))
        assertEquals("pick it with  :focus (name|contact.name)[0]", union.hint)
    }

    @Test
    fun `several values across resources suggest picking a resource`() {
        val codes = assertIs<Resolution.Several>(resolver.resolve("Observation.code", null))
        assertEquals(3, codes.owners)
        assertTrue(codes.hint.contains("label first"), codes.hint)
    }

    @Test
    fun `an empty result says whether a label was plausible`() {
        assertTrue(assertIs<Resolution.Nothing>(resolver.resolve("Observation/nope", null)).couldBeLabel)
        val code = focused("code", focusOn("Observation/obs2"))
        assertFalse(assertIs<Resolution.Nothing>(resolver.resolve("nope", code)).couldBeLabel)
    }

    @Test
    fun `a syntax error is reported as such`() {
        assertIs<Resolution.Invalid>(resolver.resolve("name.(", null))
    }
}
