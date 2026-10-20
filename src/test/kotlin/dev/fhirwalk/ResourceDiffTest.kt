package dev.fhirwalk

import dev.fhirwalk.model.FhirVersion
import dev.fhirwalk.pool.ResourceDiff
import dev.fhirwalk.pool.ResourceDiff.Difference
import dev.fhirwalk.pool.ResourceDiff.Kind
import org.hl7.fhir.instance.model.api.IBaseResource
import kotlin.test.Test
import kotlin.test.assertEquals

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
            listOf(Difference("Specimen.extension('http://e.org/container').value", Kind.REMOVED)),
            between(specimen("$category,$container"), specimen(category)),
        )
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
