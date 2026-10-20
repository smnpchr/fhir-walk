package dev.fhirwalk

import dev.fhirwalk.model.FhirVersion
import dev.fhirwalk.pool.ResourceDiff
import dev.fhirwalk.pool.ResourceDiff.Difference
import dev.fhirwalk.pool.ResourceDiff.Kind
import org.hl7.fhir.instance.model.api.IBaseResource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What changed between two versions of a resource, path by path, a reorder told from a change. */
class ResourceDiffTest {

    private val ctx = FhirVersion.R4.create()
    private val diff = ResourceDiff(ctx)

    private fun parse(json: String): IBaseResource = ctx.newJsonParser().parseResource(json)

    private fun between(before: String, after: String) = diff.between(parse(before), parse(after))

    private fun specimen(extensions: String) =
        """{"resourceType":"Specimen","id":"s1","extension":[$extensions],"type":{"coding":[{"code":"PLASMA"}]}}"""

    private val category = """{"url":"http://e.org/category","valueCode":"DERIVED"}"""
    private val container = """{"url":"http://e.org/container","valueCode":"EDTA"}"""

    @Test
    fun `nothing differs between a resource and itself, however it is written`() {
        val one = specimen("$category,$container")
        val other = """{"type":{"coding":[{"code":"PLASMA"}]},"id":"s1","extension":[$category,$container],"resourceType":"Specimen"}"""
        assertEquals(emptyList(), between(one, other))
    }

    @Test
    fun `extensions in another order are a reorder of the element they are on, and nothing else`() {
        assertEquals(
            listOf(Difference("Specimen.extension", Kind.REORDERED)),
            between(specimen("$category,$container"), specimen("$container,$category")),
        )
    }

    @Test
    fun `an extension's value is under its url, as it is typed`() {
        val changed = container.replace("EDTA", "CITRATE")
        assertEquals(
            listOf(Difference("Specimen.extension('http://e.org/container').value", Kind.CHANGED)),
            between(specimen("$category,$container"), specimen("$category,$changed")),
        )
        assertEquals(
            listOf(Difference("Specimen.extension('http://e.org/container')", Kind.REMOVED)),
            between(specimen("$category,$container"), specimen(category)),
        )
    }

    // The counterexamples from the review of #43: each a commit whose resource reads
    // differently, which the first version reported wrongly or not at all.

    private fun patient(names: String) = """{"resourceType":"Patient","id":"p1","name":[$names]}"""

    private fun coded(codings: String) = """{"resourceType":"Specimen","id":"s1","type":{"coding":[$codings]}}"""

    @Test
    fun `codings swapped whole are a reorder, codes swapped between them a change`() {
        val one = """{"system":"A","code":"1"}"""
        val two = """{"system":"B","code":"2"}"""
        assertEquals(listOf(Difference("Specimen.type.coding", Kind.REORDERED)), between(coded("$one,$two"), coded("$two,$one")))
        assertEquals(
            listOf(Difference("Specimen.type.coding.code", Kind.CHANGED)),
            between(coded("$one,$two"), coded("""{"system":"A","code":"2"},{"system":"B","code":"1"}""")),
        )
    }

    @Test
    fun `a value that moves to another repetition leaves one and joins the other`() {
        assertEquals(
            listOf(Difference("Patient.name.given", Kind.ADDED), Difference("Patient.name.given", Kind.REMOVED)),
            between(patient("""{"given":["A"]},{"given":["B","C"]}"""), patient("""{"given":["A","B"]},{"given":["C"]}""")),
        )
        // Two names merged into one: the family's gains a given, the other is gone.
        assertEquals(
            listOf(Difference("Patient.name.given", Kind.ADDED), Difference("Patient.name", Kind.REMOVED)),
            between(patient("""{"family":"X"},{"given":["A"]}"""), patient("""{"family":"X","given":["A"]}""")),
        )
    }

    @Test
    fun `an extension that moves to another repetition is not a reorder`() {
        fun tagged(first: String, second: String) = coded(
            """{"extension":[{"url":"http://e.org/$first","valueCode":"x"}],"code":"1"},""" +
                """{"extension":[{"url":"http://e.org/$second","valueCode":"x"}],"code":"2"}""",
        )
        val found = between(tagged("a", "b"), tagged("b", "a"))
        assertTrue(found.none { it.kind == Kind.REORDERED }, "$found")
        // Each coding lost its extension and gained the other's.
        val a = "Specimen.type.coding.extension('http://e.org/a')"
        val b = "Specimen.type.coding.extension('http://e.org/b')"
        assertEquals(
            setOf(Difference(a, Kind.ADDED), Difference(a, Kind.REMOVED), Difference(b, Kind.ADDED), Difference(b, Kind.REMOVED)),
            found.toSet(),
        )
    }

    @Test
    fun `a value of another type is a change, though it reads the same`() {
        fun observation(value: String) = """{"resourceType":"Observation","id":"o1","status":"final","code":{"text":"x"},$value}"""
        assertEquals(listOf(Difference("Observation.value", Kind.CHANGED)), between(observation(""""valueString":"5""""), observation(""""valueInteger":5""")))
        val asString = """{"url":"http://e.org/category","valueString":"DERIVED"}"""
        assertEquals(
            listOf(Difference("Specimen.extension('http://e.org/category').value", Kind.CHANGED)),
            between(specimen(asString), specimen(category)),
        )
    }

    @Test
    fun `a new version of the resource is not a new id`() {
        fun versioned(version: String) = """{"resourceType":"Specimen","id":"s1","meta":{"versionId":"$version"}}"""
        assertEquals(listOf(Difference("Specimen.meta.versionId", Kind.CHANGED)), between(versioned("1"), versioned("2")))
    }

    @Test
    fun `a modifier extension is not an extension with the same url`() {
        val ext = """{"url":"http://e.org/flag","valueBoolean":true}"""
        fun flagged(where: String) = """{"resourceType":"Patient","id":"p1","$where":[$ext]}"""
        assertEquals(
            listOf(
                Difference("Patient.modifierExtension.where(url='http://e.org/flag')", Kind.ADDED),
                Difference("Patient.extension('http://e.org/flag')", Kind.REMOVED),
            ),
            between(flagged("extension"), flagged("modifierExtension")),
        )
    }

    @Test
    fun `an extension with no url is still compared`() {
        assertEquals(
            listOf(Difference("Specimen.extension.value", Kind.CHANGED)),
            between(specimen("""{"valueCode":"A"}"""), specimen("""{"valueCode":"B"}""")),
        )
    }

    @Test
    fun `a contained resource of another type is a change`() {
        fun containing(type: String) = """{"resourceType":"Specimen","id":"s1","contained":[{"resourceType":"$type","id":"c1","active":true}]}"""
        assertEquals(listOf(Difference("Specimen.contained", Kind.CHANGED)), between(containing("Patient"), containing("Practitioner")))
    }

    @Test
    fun `a change past the census's depth is found`() {
        fun nested(depth: Int, text: String): String =
            if (depth == 0) """{"linkId":"leaf","text":"$text"}""" else """{"linkId":"l$depth","item":[${nested(depth - 1, text)}]}"""
        fun questionnaire(text: String) = """{"resourceType":"Questionnaire","id":"q1","status":"draft","item":[${nested(20, text)}]}"""
        val found = between(questionnaire("old"), questionnaire("new"))
        assertEquals(listOf(Kind.CHANGED), found.map { it.kind })
        assertTrue(found.single().path.endsWith(".item.text"), found.single().path)
    }

    @Test
    fun `values in another order are a reorder, other values a change, new ones an addition`() {
        fun patient(name: String) = """{"resourceType":"Patient","id":"p1","name":[$name]}"""
        assertEquals(
            listOf(Difference("Patient.name.given", Kind.REORDERED)),
            between(patient("""{"given":["Anna","Maria"]}"""), patient("""{"given":["Maria","Anna"]}""")),
        )
        assertEquals(
            listOf(Difference("Patient.name.family", Kind.ADDED), Difference("Patient.name.given", Kind.CHANGED)),
            between(patient("""{"given":["Anna"]}"""), patient("""{"family":"Muster","given":["Anne"]}""")),
        )
    }

    @Test
    fun `a choice is under its base name, as FHIRPath navigates it`() {
        fun observation(value: String) = """{"resourceType":"Observation","id":"o1","status":"final","code":{"text":"x"},$value}"""
        assertEquals(
            listOf(Difference("Observation.value.value", Kind.CHANGED)),
            between(observation(""""valueQuantity":{"value":1.0,"unit":"g"}"""), observation(""""valueQuantity":{"value":2.0,"unit":"g"}""")),
        )
    }
}
