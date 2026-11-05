package dev.fhirwalk

import dev.fhirwalk.eval.FhirPathTokenizer
import dev.fhirwalk.eval.FhirPathTokenizer.CursorKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
    fun `after a closed call the base is the whole path, spaces in its arguments or not`() {
        for ((expr, base) in listOf(
            "Specimen.identifier.where(type.coding.code = 'IDTYPE')." to "Specimen.identifier.where(type.coding.code = 'IDTYPE')",
            "Specimen.identifier.where(type.coding.code = 'IDTYPE').val" to "Specimen.identifier.where(type.coding.code = 'IDTYPE')",
            "Specimen.identifier.where(system = 'a b').first()." to "Specimen.identifier.where(system = 'a b').first()",
        )) {
            val context = FhirPathTokenizer.cursorContext(expr, expr.length)
            assertEquals(CursorKind.MEMBER, context.kind, expr)
            assertEquals(base, context.basePath, expr)
        }
        // An operator outside any call still starts a new operand, even after a stray
        // closer, as when `)` is typed once more after a completion that inserted `')`.
        for (expr in listOf("Patient.active and Patient.name.", "Patient.name.where(given = 'x')) or Patient.name.")) {
            assertEquals("Patient.name", FhirPathTokenizer.cursorContext(expr, expr.length).basePath, expr)
        }
    }

    @Test
    fun `a closed call with spaces inside an open one is part of the base`() {
        val expr = "Patient.name.where(given.where(\$this = 'a b')."
        val context = FhirPathTokenizer.cursorContext(expr, expr.length)
        assertEquals(CursorKind.MEMBER, context.kind)
        assertEquals("Patient.name.given.where(\$this = 'a b')", context.basePath)
    }

    @Test
    fun `ofType argument is a type position`() {
        val expr = "Observation.subject.resolve().ofType("
        val context = FhirPathTokenizer.cursorContext(expr, expr.length)
        assertEquals(CursorKind.TYPE_ARG, context.kind)
        assertEquals("Observation.subject.resolve()", context.basePath)
    }

    @Test
    fun `a literal on the right of a comparison knows its left operand`() {
        fun compared(expr: String) = FhirPathTokenizer.cursorContext(expr, expr.length).comparedPath

        assertEquals("Patient.gender", compared("Patient.gender = '"))
        assertEquals("Patient.gender", compared("Patient.gender != 'f"))
        // Inside a call the operand is relative to the call's input; earlier terms do not count.
        assertEquals("Observation.code.coding.code", compared("Observation.code.coding.where(code = '"))
        assertEquals(
            "Observation.code.coding.code",
            compared("Observation.code.coding.where(system = 'http://loinc.org' and code = '"),
        )
        assertEquals("Patient.name.use", compared("Patient.name.where(\$this.use = 'x').exists() and Patient.name.use = '"))
        assertEquals("Patient.gender", compared("Patient.where(\$this.gender = '"))
        assertEquals("Patient.name.given", compared("Patient.name.given.where(\$this = '"))
        // Not a comparison: no operand.
        assertEquals(null, compared("Patient.name.extension('"))
        assertEquals(null, compared("Patient.birthDate >= '"))
    }

    @Test
    fun `a literal in an in list knows the left of in, and what the list holds`() {
        fun context(expr: String) = FhirPathTokenizer.cursorContext(expr, expr.length)

        assertEquals("Patient.gender", context("Patient.gender in ('").comparedPath)
        assertEquals("Patient.gender", context("Patient.gender in('").comparedPath)
        // Inside where(), relative to its input, one paren further out.
        assertEquals("Specimen.type.coding.code", context("Specimen.where(type.coding.code in ( '").comparedPath)
        assertEquals("Patient.gender", context("Patient.where(\$this.gender in ('").comparedPath)
        val later = context("Specimen.where( type.coding.code in ( 'NUM' |\n 'EDTA' | 'C")
        assertEquals("Specimen.type.coding.code", later.comparedPath)
        assertEquals(setOf("NUM", "EDTA"), later.listed)
        assertEquals("C", later.partial)
        assertEquals(null, later.enclosingCall)
        // Not a list after in: no operand.
        assertEquals(null, context("Patient.where(gender in ('a' 'b").comparedPath)
        assertEquals(null, context("Patient.where(name.join('").comparedPath)
        assertEquals(null, context("Patient.where(gender in ('a' | x | '").comparedPath)
        assertEquals(null, context("Patient.name.where(main('").comparedPath)
        assertEquals(null, context("Patient.name[gender in ('").comparedPath)
    }

    @Test
    fun `a bare paren groups, so the operand is relative to the call around it`() {
        fun compared(expr: String) = FhirPathTokenizer.cursorContext(expr, expr.length).comparedPath
        // How "not in" is written: there is no such operator.
        assertEquals("Patient.gender", compared("Patient.where((gender in ('"))
        assertEquals("Patient.gender", compared("Patient.where((gender = '"))
        assertEquals("Patient.gender", compared("(Patient.gender in ('"))
        assertEquals("Patient.gender", compared("Patient.where(((gender in ('"))
        // And so are members.
        val member = FhirPathTokenizer.cursorContext("Patient.where((gen", 18)
        assertEquals("Patient", member.basePath)
    }

    @Test
    fun `a line break before the operand separates terms as a space does`() {
        fun compared(expr: String) = FhirPathTokenizer.cursorContext(expr, expr.length).comparedPath
        assertEquals("Patient.gender", compared("Patient.where(gender = 'x' and\n  gender in ('"))
        assertEquals("Patient.gender", compared("Patient.where(name.exists() and\n\tgender = '"))
    }

    @Test
    fun `listed values are read as the census holds them, escapes undone`() {
        val expr = "Patient.name.where(family in ('O\\'Brien' | 'a\\\\b' | '"
        val context = FhirPathTokenizer.cursorContext(expr, expr.length)
        assertEquals(setOf("O'Brien", "a\\b"), context.listed)
    }

    @Test
    fun `inside a quoted identifier nothing is a value`() {
        for (expr in listOf("Patient.gender in (\"", "Patient.gender = \"", "Patient.`gen")) {
            assertEquals(CursorKind.QUOTED_NAME, FhirPathTokenizer.cursorContext(expr, expr.length).kind, expr)
        }
    }

    @Test
    fun `a path is identifiers, calls and indexers joined by dots`() {
        listOf("name", "Patient.name[0].given", "extension('http://x.org/a b').value", "where(a = 'b').code", "\$this.x")
            .forEach { assertTrue(FhirPathTokenizer.isPath(it), it) }
        // Operators make it not a path, wherever they hide.
        listOf("name|contact.name", "name[0] | x", "a+b", "(code)", "x != y", "code.coding = 'x'")
            .forEach { assertFalse(FhirPathTokenizer.isPath(it), it) }
    }
}
