package dev.fhirwalk

import dev.fhirwalk.eval.Evaluator
import dev.fhirwalk.eval.Outcome
import dev.fhirwalk.model.FhirVersion
import dev.fhirwalk.model.ModelNavigator
import dev.fhirwalk.model.PackageDefinitions
import dev.fhirwalk.pool.ResourcePool
import org.hl7.fhir.instance.model.api.IBase
import org.hl7.fhir.instance.model.api.IPrimitiveType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun `completion works on R5 as on R4`() {
        val nav = ModelNavigator(ctx, pool.census)
        assertTrue(nav.childrenAt("Observation.value").any { it.name == "unit" }, "census types the choice")
    }

    @Test
    fun `a missing package is named in the error`() {
        val definitions = PackageDefinitions(ctx, "/no/such/package.tgz")
        val error = assertFailsWith<IllegalStateException> { definitions.fetchStructureDefinition("x") }
        assertTrue(error.message!!.contains("/no/such/package.tgz"), error.message)
    }

    private companion object {
        // One context for the class: reading the package takes seconds.
        val ctx = FhirVersion.R5.create()
        val pool = ResourcePool(ctx)
        val evaluator = Evaluator(ctx, pool)
        val observation = pool.add(
            ctx.newJsonParser().parseResource(
                """{"resourceType":"Observation","id":"o5","status":"final",
                   "code":{"coding":[{"system":"http://loinc.org","code":"8302-2"}]},
                   "valueQuantity":{"value":170,"unit":"cm"}}""",
            ),
            null,
            "test",
        ).resource
    }
}
