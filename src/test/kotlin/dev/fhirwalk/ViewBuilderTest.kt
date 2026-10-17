package dev.fhirwalk

import dev.fhirwalk.eval.Evaluator
import dev.fhirwalk.eval.ViewBuilder
import dev.fhirwalk.eval.ViewBuilder.Column
import dev.fhirwalk.model.FhirVersion
import dev.fhirwalk.model.ModelNavigator
import dev.fhirwalk.pool.ResourcePool
import dev.fhirwalk.repl.Render
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Cell contents, against resources built for the case rather than a fixture. */
class ViewBuilderTest {

    private val ctx = FhirVersion.R4.create()
    private val pool = ResourcePool(ctx)
    private val builder = ViewBuilder(Evaluator(ctx, pool), pool, ModelNavigator(ctx, pool.census), Render(ctx)::typeOf)

    init {
        val json = ctx.newJsonParser()
        pool.add(
            json.parseResource(
                """{"resourceType":"Patient","id":"p1","name":[{"family":"Muster\nmann","given":["A","B"]}]}""",
            ),
            null,
            "test",
        )
    }

    @Test
    fun `a cell stays on one line whatever the value holds`() {
        val table = assertIs<ViewBuilder.Result.Table>(
            builder.build(listOf(Column(1, "Patient.name.family"), Column(2, "Patient.name.given"))),
        )
        assertEquals(listOf("Muster mann", "A, B"), table.rows.single().cells)
    }

    @Test
    fun `a structured column is refused`() {
        val refused = assertIs<ViewBuilder.Result.Refused>(
            builder.build(listOf(Column(1, "Patient.name.family"), Column(2, "Patient.name"))),
        )
        assertEquals(true, refused.message.startsWith("#2 Patient.name yields HumanName"), refused.message)
    }
}
