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

    private fun several(argument: String, current: Focus? = null) =
        assertIs<Resolution.Several>(resolver.resolve(argument, current))

    @Test
    fun `several values across resources pick by row`() {
        val codes = several("Observation.code")
        assertEquals(3, codes.owners)
        // Each resource gave one code, so no indexer: the label is the resource's own.
        val picked = assertIs<Resolution.Focused>(resolver.resolve("[1]", null, codes.picks)).focus
        assertEquals("Observation/obs2.code", picked.label)
        assertEquals("Observation.code", picked.path)
        assertEquals(null, picked.parent)
    }

    @Test
    fun `a pick within one resource is spelled as the indexer that selects it`() {
        val pat1 = focusOn("Patient/pat1")
        val names = several("name", pat1)
        val second = assertIs<Resolution.Focused>(resolver.resolve("[1]", pat1, names.picks)).focus
        assertEquals("Patient/pat1.name[1]", second.label)
        assertEquals("Patient.name[1]", second.path)
        assertEquals(pat1, second.parent)

        // An indexer on a union has to wrap all of it.
        val union = several("name|contact.name", pat1)
        val first = assertIs<Resolution.Focused>(resolver.resolve("[0]", pat1, union.picks)).focus
        assertEquals("Patient/pat1.(name|contact.name)[0]", first.label)
    }

    @Test
    fun `picking a resolved resource gives a resource focus`() {
        val patients = several("Observation.subject.resolve()")
        val pat2 = assertIs<Resolution.Focused>(resolver.resolve("[1]", null, patients.picks)).focus
        assertTrue(pat2.isResource)
        assertEquals("Patient/pat2", pat2.label)
    }

    @Test
    fun `a pick needs a listing from the current focus and an index in it`() {
        assertIs<Resolution.CannotPick>(resolver.resolve("[0]", null, null))
        val pat1 = focusOn("Patient/pat1")
        val names = several("name", pat1)
        // A different focus, even of the same resource: those rows were not its rows.
        assertIs<Resolution.CannotPick>(resolver.resolve("[0]", focusOn("Patient/pat1"), names.picks))
        val outOfRange = assertIs<Resolution.CannotPick>(resolver.resolve("[5]", pat1, names.picks))
        assertTrue(outOfRange.reason.contains("[0] to [1]"), outOfRange.reason)
        assertIs<Resolution.CannotPick>(resolver.resolve("[99999999999]", pat1, names.picks))
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
