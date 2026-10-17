package dev.fhirwalk

import dev.fhirwalk.eval.Evaluator
import dev.fhirwalk.eval.Outcome
import dev.fhirwalk.model.FhirVersion
import dev.fhirwalk.model.PackageDefinitions
import dev.fhirwalk.pool.ResourcePool
import org.hl7.fhir.instance.model.api.IBase
import org.hl7.fhir.instance.model.api.IPrimitiveType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * R5 end to end. Its base definitions come from an NPM package that
 * [PackageDefinitions] reads, not from HAPI's own reader, so this is what notices
 * when a HAPI upgrade asks for more than that reader supplies.
 */
class R5Test {

    private fun values(outcome: Outcome): List<IBase> = when (outcome) {
        is Outcome.Values -> outcome.values
        is Outcome.Failure -> error("expected values, got failure: ${outcome.message}")
    }

    private fun strings(expression: String) =
        values(evaluator.evaluate(observation, expression)).map { (it as IPrimitiveType<*>).valueAsString }

    @Test
    fun `an R5 context evaluates FHIRPath`() {
        assertEquals(listOf("8302-2"), strings("Observation.code.coding.code"))
    }

    @Test
    fun `type operations resolve against the packaged definitions`() {
        // ofType(), is and as() are the calls that need StructureDefinitions; choice
        // navigation goes through ofType().
        assertEquals(listOf("170"), strings("Observation.value.ofType(Quantity).value"))
        assertEquals(listOf("true"), strings("Observation.value is Quantity"))
        assertEquals(listOf("170"), strings("Observation.value.as(Quantity).value"))
    }

    @Test
    fun `a capitalised primitive answers as on R4, with nothing rather than an error`() {
        // R4 resolves FHIR.String to the primitive `string`; an exact-URL lookup
        // failed it with "The type FHIR.String is not valid".
        assertEquals(emptyList(), strings("Observation.code.coding.code.ofType(String)"))
        val r4 = FhirVersion.R4.create()
        val r4Pool = ResourcePool(r4)
        val r4Observation = r4Pool.add(r4.newJsonParser().parseResource(OBSERVATION), null, "test").resource
        assertEquals(
            emptyList(),
            values(Evaluator(r4, r4Pool).evaluate(r4Observation, "Observation.code.coding.code.ofType(String)")),
        )
    }

    @Test
    fun `definitions are found by every spelling HAPI accepts`() {
        val definitions = PackageDefinitions(ctx, requireNotNull(CORE))
        for (url in listOf(
            "http://hl7.org/fhir/StructureDefinition/Quantity",
            "http://hl7.org/fhir/StructureDefinition/Quantity|5.0.0",
            "StructureDefinition/Quantity",
            "Quantity",
        )) {
            assertNotNull(definitions.fetchStructureDefinition(url), url)
        }
        assertNotNull(definitions.fetchStructureDefinition("String"), "falls back to the primitive")
        assertNull(definitions.fetchStructureDefinition("NoSuchType"))
    }

    @Test
    fun `the core package is found by name, not by its exact file name`() {
        assertTrue(CORE!!.matches(Regex("/org/hl7/fhir/r5/packages/hl7\\.fhir\\.r5\\.core-.+\\.tgz")), CORE)
        assertNull(PackageDefinitions.locate("org/hl7/fhir/r5/packages", "no.such.package-"))
        assertNull(PackageDefinitions.locate("no/such/directory", "hl7.fhir.r5.core-"))
    }

    @Test
    fun `long tar names arrive whole, in either extension`() {
        val longId = "LongName" + "x".repeat(120)
        for (format in listOf("pax", "gnu")) {
            val definitions = PackageDefinitions(ctx, "/packages/long-names-$format.tgz")
            assertNotNull(definitions.fetchStructureDefinition("http://example.org/StructureDefinition/$longId"), format)
        }
    }

    @Test
    fun `a missing package is named in the error, and the failure is kept`() {
        val definitions = PackageDefinitions(ctx, "/no/such/package.tgz")
        val first = assertFailsWith<IllegalStateException> { definitions.fetchStructureDefinition("x") }
        assertTrue(first.message!!.contains("/no/such/package.tgz"), first.message)
        // The same failure, not a second attempt: a pool-wide evaluation asks per resource.
        val second = assertFailsWith<IllegalStateException> { definitions.fetchStructureDefinition("y") }
        assertSame(first, second)
    }

    private companion object {
        const val OBSERVATION = """{"resourceType":"Observation","id":"o5","status":"final",
            "code":{"coding":[{"system":"http://loinc.org","code":"8302-2"}]},
            "valueQuantity":{"value":170,"unit":"cm"}}"""

        val CORE = PackageDefinitions.locate("org/hl7/fhir/r5/packages", "hl7.fhir.r5.core-")

        // One context for the class: reading the package takes seconds.
        val ctx = FhirVersion.R5.create()
        val pool = ResourcePool(ctx)
        val evaluator = Evaluator(ctx, pool)
        val observation = pool.add(ctx.newJsonParser().parseResource(OBSERVATION), null, "test").resource
    }
}
