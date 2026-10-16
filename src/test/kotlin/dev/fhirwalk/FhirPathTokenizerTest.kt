package dev.fhirwalk

import dev.fhirwalk.eval.FhirPathTokenizer
import dev.fhirwalk.eval.FhirPathTokenizer.CursorKind
import kotlin.test.Test
import kotlin.test.assertEquals

class FhirPathTokenizerTest {

    @Test
    fun `splits on navigation dots only`() {
        val segments = FhirPathTokenizer.segments("Patient.name.where(given.first() = 'Erika').family")
        assertEquals(
            listOf("Patient", "name", "where(given.first() = 'Erika')", "family"),
            segments.map { it.text },
        )
    }

    @Test
    fun `ignores dots inside string literals`() {
        val segments = FhirPathTokenizer.segments("Patient.extension('http://example.org/a.b').value")
        assertEquals(
            listOf("Patient", "extension('http://example.org/a.b')", "value"),
            segments.map { it.text },
        )
    }

    @Test
    fun `prefixes build up cumulatively`() {
        assertEquals(
            listOf("Patient", "Patient.contact", "Patient.contact.name", "Patient.contact.name.given"),
            FhirPathTokenizer.prefixes("Patient.contact.name.given"),
        )
    }

    @Test
    fun `cursor after a dot completes members of the base path`() {
        val expr = "Patient.name."
        val context = FhirPathTokenizer.cursorContext(expr, expr.length)
        assertEquals(CursorKind.MEMBER, context.kind)
        assertEquals("Patient.name", context.basePath)
        assertEquals("", context.partial)
    }

    @Test
    fun `cursor mid-identifier keeps the partial token`() {
        val expr = "Patient.name.giv"
        val context = FhirPathTokenizer.cursorContext(expr, expr.length)
        assertEquals(CursorKind.MEMBER, context.kind)
        assertEquals("Patient.name", context.basePath)
        assertEquals("giv", context.partial)
        assertEquals(expr.length - 3, context.replaceFrom)
    }

    @Test
    fun `cursor at the start completes resource types`() {
        val context = FhirPathTokenizer.cursorContext("Pat", 3)
        assertEquals(CursorKind.ROOT, context.kind)
        assertEquals("Pat", context.partial)
    }

    @Test
    fun `inside an extension literal the base path is the element it hangs off`() {
        val expr = "Patient.name.extension('http://fhir.de/Str"
        val context = FhirPathTokenizer.cursorContext(expr, expr.length)
        assertEquals(CursorKind.STRING_ARG, context.kind)
        assertEquals("extension", context.enclosingCall)
        assertEquals("Patient.name", context.basePath)
        assertEquals("http://fhir.de/Str", context.partial)
    }

    @Test
    fun `nested extension literal scopes to the outer extension path`() {
        val expr = "Patient.extension('http://outer').extension('"
        val context = FhirPathTokenizer.cursorContext(expr, expr.length)
        assertEquals(CursorKind.STRING_ARG, context.kind)
        assertEquals("Patient.extension('http://outer')", context.basePath)
        assertEquals("", context.partial)
    }

    @Test
    fun `inside where the base is the call input`() {
        val expr = "Patient.name.where(fam"
        val context = FhirPathTokenizer.cursorContext(expr, expr.length)
        assertEquals(CursorKind.MEMBER, context.kind)
        assertEquals("Patient.name", context.basePath)
        assertEquals("fam", context.partial)
    }

    @Test
    fun `ofType argument is a type position`() {
        val expr = "Observation.subject.resolve().ofType("
        val context = FhirPathTokenizer.cursorContext(expr, expr.length)
        assertEquals(CursorKind.TYPE_ARG, context.kind)
        assertEquals("Observation.subject.resolve()", context.basePath)
    }
}
