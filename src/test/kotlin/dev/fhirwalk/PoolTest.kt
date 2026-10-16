package dev.fhirwalk

import dev.fhirwalk.eval.Evaluator
import dev.fhirwalk.eval.Outcome
import dev.fhirwalk.eval.PrefixWalker
import dev.fhirwalk.model.FhirVersion
import dev.fhirwalk.model.ModelNavigator
import dev.fhirwalk.pool.Importer
import dev.fhirwalk.pool.ResourcePool
import org.hl7.fhir.instance.model.api.IPrimitiveType
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PoolTest {

    private val ctx = FhirVersion.R4.create()
    private val pool = ResourcePool(ctx)
    private val evaluator = Evaluator(ctx, pool)

    init {
        val importer = Importer(ctx, pool)
        importer.load(Paths.get("samples/basic/bundle.json"))
        importer.load(Paths.get("samples/basic/tree"))
    }

    @Test
    fun `bundles are flattened and directory trees walked`() {
        // 3 from the bundle, 2 from NDJSON, 1 loose Encounter.
        assertEquals(6, pool.size)
        assertEquals(2, pool.ofType("Patient").size)
        assertEquals(3, pool.ofType("Observation").size)
        assertEquals(1, pool.ofType("Encounter").size)
    }

    @Test
    fun `resolve crosses file and bundle boundaries`() {
        // obs2 lives in the NDJSON file; Patient/pat1 came from the bundle.
        val obs2 = pool.find("Observation/obs2")
        assertNotNull(obs2)
        val outcome = evaluator.evaluate(obs2.resource, "Observation.subject.resolve().name.family")
        val values = assertIsValues(outcome)
        assertEquals(listOf("Mustermann"), values.map { (it as IPrimitiveType<*>).valueAsString })
    }

    @Test
    fun `fullUrl resolution works for urn references`() {
        assertNotNull(pool.find("urn:uuid:11111111-1111-1111-1111-111111111111"))
    }

    @Test
    fun `census records extension urls scoped to their element path`() {
        val onPatient = pool.census.extensionsAt("Patient").map { it.value }
        assertTrue(onPatient.any { it.endsWith("patient-birthPlace") }, "got $onPatient")

        // The namenszusatz extension hangs off Patient.name, not Patient.
        val onName = pool.census.extensionsAt("Patient.name").map { it.value }
        assertTrue(onName.any { it.endsWith("humanname-namenszusatz") }, "got $onName")
        assertTrue(onPatient.none { it.endsWith("humanname-namenszusatz") })
    }

    @Test
    fun `census records nested extensions under the path the user would type`() {
        val nested = pool.census
            .extensionsAt("Patient.extension('http://hl7.org/fhir/StructureDefinition/patient-nationality')")
            .map { it.value }
        assertEquals(listOf("code"), nested)
    }

    @Test
    fun `census records reference targets and code systems`() {
        assertEquals(
            listOf("Patient"),
            pool.census.referenceTargetsAt("Observation.subject").map { it.value },
        )
        assertTrue(
            pool.census.codeSystemsAt("Observation.code.coding").any { it.value == "http://loinc.org" },
        )
    }

    @Test
    fun `pool-wide fan-out reports coverage`() {
        // Choice elements navigate by base name per the FHIRPath spec; HAPI rejects
        // the wire spelling (Observation.valueQuantity), which is why the census and
        // the completer both use the base name.
        val result = evaluator.evaluateAll("Observation.value.ofType(Quantity).value")
        assertEquals(3, result.hits.size)
        assertEquals(3, result.misses) // the two Patients and the Encounter
        assertEquals(0, result.errors)
    }

    @Test
    fun `census records choice element paths by their navigable base name`() {
        assertTrue(pool.census.pathCounts.containsKey("Observation.value"))
        assertTrue(!pool.census.pathCounts.containsKey("Observation.valueQuantity"))
        assertEquals(listOf("Quantity"), pool.census.choiceTypesAt("Observation.value").map { it.value })
    }

    @Test
    fun `navigator narrows a choice element using observed types`() {
        val navigator = ModelNavigator(ctx, pool.census)
        // Statically ambiguous (11 possible types); the pool only ever holds Quantity.
        assertEquals("Quantity", navigator.resolveType("Observation.value")?.name)
        assertTrue(navigator.childrenAt("Observation.value").any { it.name == "unit" })
    }

    @Test
    fun `navigator offers choice elements by base name only`() {
        val navigator = ModelNavigator(ctx, pool.census)
        val names = navigator.childrenAt("Observation").map { it.name }
        assertTrue(names.contains("value"), "got $names")
        assertTrue(!names.contains("valueQuantity"))
    }

    @Test
    fun `prefix walk finds the step that goes empty`() {
        val pat1 = requireNonNull(pool.find("Patient/pat1"))
        val diagnosis = PrefixWalker(evaluator, pool).diagnose(pat1.resource, "Patient.contact.name.given")
        assertEquals(4, diagnosis.steps.size)
        assertEquals(1, diagnosis.firstEmpty) // Patient.contact
        assertEquals("Patient.contact", diagnosis.steps[1].expression)

        // pat2 does populate contact, so the pool tells us the element is fine.
        val coverage = assertNotNull(diagnosis.poolCoverage)
        assertEquals(1, coverage.matching)
        assertEquals(2, coverage.considered)
    }

    @Test
    fun `navigator resolves types through calls using the schema axis`() {
        val navigator = ModelNavigator(ctx, pool.census)
        assertEquals("HumanName", navigator.resolveType("Patient.name.where(use = 'official')")?.name)
        assertEquals("Extension", navigator.resolveType("Patient.extension('http://x')")?.name)
        assertTrue(navigator.childrenAt("Patient.name").any { it.name == "given" })
    }

    @Test
    fun `navigator falls back to the corpus for resolve`() {
        val navigator = ModelNavigator(ctx, pool.census)
        // The static model cannot type resolve(); the census observed only Patients.
        assertEquals("Patient", navigator.resolveType("Observation.subject.resolve()")?.name)
    }

    @Test
    fun `references the string cannot type are typed by what they resolve to`() {
        val pool = ResourcePool(ctx)
        val importer = Importer(ctx, pool)
        importer.load(Paths.get("samples/references/transaction.json"))

        // urn:uuid links name no type; resolving them against the pool does.
        assertEquals<Map<String, Int>?>(mapOf("Patient" to 2, "Group" to 1), pool.census.referenceTargets["Observation.subject"])
        assertEquals<Map<String, Int>?>(mapOf("Patient" to 1), pool.census.referenceTargets["Encounter.subject"])
        assertEquals<Map<String, Int>?>(mapOf("Patient" to 1), pool.census.referenceTargets["Group.member.entity"])
        // Identifier-only: Reference.type is all there is.
        assertEquals<Map<String, Int>?>(mapOf("Practitioner" to 1), pool.census.referenceTargets["Observation.performer"])

        // A bare id whose target is not loaded yet stays queued, then settles on a later load.
        assertEquals<Map<String, Int>?>(null, pool.census.referenceTargets["Encounter.participant.individual"])
        importer.load(Paths.get("samples/references/late"))
        assertEquals<Map<String, Int>?>(mapOf("Practitioner" to 1), pool.census.referenceTargets["Encounter.participant.individual"])
        assertTrue(pool.census.pendingReferences.isEmpty(), "left pending: ${pool.census.pendingReferences}")
    }

    @Test
    fun `navigator keeps the census path through calls and indexers`() {
        val navigator = ModelNavigator(ctx, pool.census)
        assertEquals("Patient", navigator.resolveType("Observation.subject.where(x).resolve()")?.name)
        assertEquals("Patient", navigator.resolveType("Observation.subject.first().resolve()")?.name)
        assertEquals("Patient", navigator.resolveType("Observation.subject[0].resolve()")?.name)
        assertEquals("Patient.name", navigator.censusPath("Observation.subject.resolve().name.where(use = 'official')"))
        assertEquals(
            "Patient.extension('http://x').value",
            navigator.censusPath("Patient.extension('http://x').first().value"),
        )
        // A projection has no census spelling; the raw path goes through untouched.
        assertEquals("Patient.select(name).given", navigator.censusPath("Patient.select(name).given"))
    }

    @Test
    fun `census records coded values and nothing identifying`() {
        val census = pool.census
        assertEquals(setOf("female", "male"), census.valuesAt("Patient.gender").map { it.value }.toSet())
        assertEquals("final", census.valuesAt("Observation.status").first().value)
        assertTrue(census.valuesAt("Observation.code.coding.code").any { it.value == "8302-2" })
        // Strings, dates and ids are never recorded, so they can never reach the menu.
        for (path in listOf("Patient.name.family", "Patient.birthDate", "Patient.id", "Patient.identifier.value")) {
            assertTrue(census.valuesAt(path).isEmpty(), "recorded values at $path")
        }
    }

    @Test
    fun `a path with too many distinct values stops being recorded`() {
        val census = dev.fhirwalk.pool.Census()
        repeat(dev.fhirwalk.pool.Census.MAX_VALUES_PER_PATH) { census.recordValue("X.code", "c$it") }
        assertEquals(dev.fhirwalk.pool.Census.MAX_VALUES_PER_PATH, census.valuesAt("X.code").size)

        census.recordValue("X.code", "one-too-many")
        census.recordValue("X.code", "c0")
        assertTrue(census.valuesAt("X.code").isEmpty())
        assertTrue("X.code" in census.highCardinality)
    }

    private fun assertIsValues(outcome: Outcome): List<org.hl7.fhir.instance.model.api.IBase> =
        when (outcome) {
            is Outcome.Values -> outcome.values
            is Outcome.Failure -> error("expected values, got failure: ${outcome.message}")
        }

    private fun <T> requireNonNull(value: T?): T = assertNotNull(value)
}
