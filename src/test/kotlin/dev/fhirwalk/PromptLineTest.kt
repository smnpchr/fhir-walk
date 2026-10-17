package dev.fhirwalk

import dev.fhirwalk.eval.PromptLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PromptLineTest {

    @Test
    fun `a bare line is all expression`() {
        assertEquals(PromptLine.Slice("Patient.na", 10, 0), PromptLine.expressionSlice("Patient.na", 10))
    }

    @Test
    fun `the argument of an expression command is sliced off`() {
        assertEquals(PromptLine.Slice("Patient.na", 10, 5, ":all"), PromptLine.expressionSlice(":all Patient.na", 15))
        assertEquals(PromptLine.Slice("Patient", 7, 5, ":why"), PromptLine.expressionSlice(":why Patient", 12))
    }

    @Test
    fun `leading whitespace is tolerated`() {
        assertEquals(PromptLine.Slice("X", 1, 7, ":all"), PromptLine.expressionSlice("  :all X", 8))
    }

    @Test
    fun `an empty argument still completes as an expression`() {
        assertEquals(PromptLine.Slice("", 0, 5, ":all"), PromptLine.expressionSlice(":all ", 5))
    }

    @Test
    fun `commands whose argument is not an expression are excluded`() {
        assertNull(PromptLine.expressionSlice(":load samples/", 14))
        assertNull(PromptLine.expressionSlice(":fhir r5", 8))
    }

    @Test
    fun `focus takes an expression as well as a label`() {
        assertEquals(PromptLine.Slice("code.cod", 8, 7, ":focus"), PromptLine.expressionSlice(":focus code.cod", 15))
    }

    @Test
    fun `a cursor inside the command name is not in an expression`() {
        assertNull(PromptLine.expressionSlice(":all", 4))
        assertNull(PromptLine.expressionSlice(":all Patient", 3))
    }
}
