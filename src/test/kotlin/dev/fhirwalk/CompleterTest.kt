package dev.fhirwalk

import dev.fhirwalk.complete.FhirPathCompleter
import dev.fhirwalk.model.FhirVersion
import dev.fhirwalk.model.ModelNavigator
import dev.fhirwalk.pool.Importer
import dev.fhirwalk.pool.ResourcePool
import dev.fhirwalk.repl.FhirPathParser
import org.jline.reader.Candidate
import org.jline.reader.LineReader
import org.jline.reader.LineReaderBuilder
import org.jline.reader.Parser
import org.jline.terminal.TerminalBuilder
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers the behaviour the tool exists for: suggestions that come from the imported
 * data, scoped to the element the cursor is on.
 */
class CompleterTest {

    private val ctx = FhirVersion.R4.create()
    private val pool = ResourcePool(ctx)
    private val parser = FhirPathParser()
    private val reader: LineReader
    private val completer: FhirPathCompleter

    init {
        Importer(ctx, pool).apply {
            load(Paths.get("samples/basic/bundle.json"))
            load(Paths.get("samples/basic/tree"))
        }
        val terminal = TerminalBuilder.builder().dumb(true).build()
        reader = LineReaderBuilder.builder().terminal(terminal).build()
        completer = FhirPathCompleter({ ModelNavigator(ctx, pool.census) }, { pool }, { emptyList() })
    }

    private fun candidates(expression: String): List<Candidate> {
        val line = parser.parse(expression, expression.length, Parser.ParseContext.COMPLETE)
        val out = mutableListOf<Candidate>()
        completer.complete(reader, line, out)
        return out
    }

    private fun values(expression: String) = candidates(expression).map { it.value() }

    @Test
    fun `extension urls are scoped to the element they were observed on`() {
        val onName = values("Patient.name.extension('")
        assertTrue(onName.any { it.endsWith("humanname-namenszusatz") }, "got $onName")
        // The birthPlace extension sits on Patient, not on Patient.name -- offering it
        // here is exactly the noise a schema-only completer cannot avoid.
        assertTrue(onName.none { it.endsWith("patient-birthPlace") }, "got $onName")

        val onPatient = values("Patient.extension('")
        assertTrue(onPatient.any { it.endsWith("patient-birthPlace") }, "got $onPatient")
        assertTrue(onPatient.none { it.endsWith("humanname-namenszusatz") }, "got $onPatient")
    }

    @Test
    fun `nested extension urls complete under the outer extension`() {
        val nested = values("Patient.extension('http://hl7.org/fhir/StructureDefinition/patient-nationality').extension('")
        assertEquals(listOf("code"), nested)
    }

    @Test
    fun `extension candidates carry occurrence counts`() {
        val candidate = candidates("Patient.extension('")
            .first { it.value().endsWith("patient-birthPlace") }
        assertTrue(candidate.descr().contains("1 ×"), "got ${candidate.descr()}")
        assertTrue(candidate.descr().contains("Address"), "got ${candidate.descr()}")
    }

    @Test
    fun `populated elements are grouped apart from merely legal ones`() {
        val byName = candidates("Patient.").associateBy { it.value() }
        assertEquals("elements (in pool)", byName.getValue("name").group())
        assertEquals("elements (in pool)", byName.getValue("gender").group())
        // Legal on Patient, absent from this pool.
        assertEquals("elements", byName.getValue("photo").group())
    }

    @Test
    fun `member completion offers functions alongside elements`() {
        val all = values("Patient.name.")
        assertTrue(all.contains("given"), "elements missing")
        assertTrue(all.contains("where("), "functions missing")
    }

    @Test
    fun `resource types in the pool are separated from the full schema`() {
        val byName = candidates("Pat").associateBy { it.value() }
        assertEquals("in pool", byName.getValue("Patient").group())
        assertEquals("all resource types", byName.getValue("Practitioner").group())
    }

    @Test
    fun `ofType after resolve offers the types actually referenced`() {
        val candidates = candidates("Observation.subject.resolve().ofType(")
        val observed = candidates.filter { it.group() == "observed" }.map { it.value() }
        assertEquals(listOf("Patient"), observed)
    }

    @Test
    fun `choice elements complete by base name only`() {
        val values = values("Observation.")
        assertTrue(values.contains("value"), "got $values")
        assertTrue(values.none { it == "valueQuantity" }, "wire names must not be offered")
    }
}
