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
            builder.build(listOf(Column("Patient.name.family", 1), Column("Patient.name.given", 2))),
        )
        assertEquals(listOf("Muster mann", "A, B"), table.rows.single().cells)
        assertEquals(emptySet(), table.rows.single().failed)
    }

    @Test
    fun `a failed cell is known as one, and a value that reads like one is not`() {
        // given.single() fails on two names; the literal '!' is a value.
        val table = assertIs<ViewBuilder.Result.Table>(
            builder.build(listOf(Column("Patient.name.given.single()", 1), Column("Patient.name.family.replace('Muster\nmann', '!')", 2))),
        )
        assertEquals(listOf(ViewBuilder.ERROR_CELL, "!"), table.rows.single().cells)
        assertEquals(setOf(0), table.rows.single().failed)
    }

    @Test
    fun `a structured column is refused`() {
        val refused = assertIs<ViewBuilder.Result.Refused>(
            builder.build(listOf(Column("Patient.name.family", 1), Column("Patient.name", 2))),
        )
        assertEquals(true, refused.message.startsWith("#2 Patient.name yields HumanName"), refused.message)
    }

    @Test
    fun `a named column is titled by its name, a numbered one by its path`() {
        assertEquals("family", Column("Patient.name.family", 1, "family").title("Patient"))
        assertEquals("#2 name.family", Column("Patient.name.family", 2).title("Patient"))
        assertEquals("name.family", Column("Patient.name.family").title("Patient"))
        val refused = assertIs<ViewBuilder.Result.Refused>(builder.build(listOf(Column("Patient.name", name = "who"))))
        assertEquals(true, refused.message.startsWith("who (Patient.name) yields HumanName"), refused.message)
    }
}
