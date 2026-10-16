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
        completer = FhirPathCompleter({ ModelNavigator(ctx, pool.census) }, { pool }, { COMMANDS })
    }

    private fun candidates(expression: String, cursor: Int = expression.length): List<Candidate> {
        val line = parser.parse(expression, cursor, Parser.ParseContext.COMPLETE)
        val out = mutableListOf<Candidate>()
        completer.complete(reader, line, out)
        return out
    }

    /** What gets inserted, which for a string argument includes the closer. */
    private fun values(expression: String) = candidates(expression).map { it.value() }

    /** What the menu shows, which stays the bare value. */
    private fun displays(expression: String) = candidates(expression).map { it.displ() }

    @Test
    fun `extension urls are scoped to the element they were observed on`() {
        val onName = displays("Patient.name.extension('")
        assertTrue(onName.any { it.endsWith("humanname-namenszusatz") }, "got $onName")
        // The birthPlace extension sits on Patient, not on Patient.name -- offering it
        // here is exactly the noise a schema-only completer cannot avoid.
        assertTrue(onName.none { it.endsWith("patient-birthPlace") }, "got $onName")

        val onPatient = displays("Patient.extension('")
        assertTrue(onPatient.any { it.endsWith("patient-birthPlace") }, "got $onPatient")
        assertTrue(onPatient.none { it.endsWith("humanname-namenszusatz") }, "got $onPatient")
    }

    @Test
    fun `nested extension urls complete under the outer extension`() {
        val nested = displays("Patient.extension('http://hl7.org/fhir/StructureDefinition/patient-nationality').extension('")
        assertEquals(listOf("code"), nested)
    }

    @Test
    fun `extension candidates carry occurrence counts`() {
        val candidate = candidates("Patient.extension('")
            .first { it.displ().endsWith("patient-birthPlace") }
        assertTrue(candidate.descr().contains("1 ×"), "got ${candidate.descr()}")
        assertTrue(candidate.descr().contains("Address"), "got ${candidate.descr()}")
    }

    @Test
    fun `accepting a string candidate closes the literal and the call`() {
        // Inserting only the URL left the line holding an unterminated string, so
        // Enter submitted something that could not parse.
        val open = candidates("Patient.extension('").first { it.displ().endsWith("patient-birthPlace") }
        assertEquals(
            "http://hl7.org/fhir/StructureDefinition/patient-birthPlace')",
            open.value(),
        )

        // When the closer is already on the line, adding another would corrupt it.
        val line = "Patient.extension('')"
        val closed = candidates(line, line.indexOf("''") + 1)
            .first { it.displ().endsWith("patient-birthPlace") }
        assertEquals("http://hl7.org/fhir/StructureDefinition/patient-birthPlace", closed.value())
    }

    @Test
    fun `a function taking a string literal opens the quote`() {
        // Otherwise the URL suggestions stay out of reach until you type the quote
        // yourself -- and typing a double quote silently produces an expression
        // FHIRPath cannot parse.
        assertTrue(values("Patient.ext").contains("extension('"), "got ${values("Patient.ext")}")
        assertTrue(values("Patient.name.given.matc").contains("matches('"))
        // Functions whose first argument is not a string stop at the paren.
        assertTrue(values("Patient.name.whe").contains("where("))
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
    fun `the argument of an expression command completes like an expression`() {
        // Completion that stops working the moment you prefix the line with :all is
        // worse than none at all, since :all is how you search the pool.
        assertTrue(values(":all Speci").contains("Specimen"), "root type not offered")
        assertTrue(values(":all Patient.").contains("name"), "members not offered")
        assertTrue(values(":why Patient.").contains("name"), "members not offered")
        assertTrue(
            displays(":all Patient.name.extension('").any { it.endsWith("humanname-namenszusatz") },
            "census urls not offered inside a command argument",
        )
    }

    @Test
    fun `commands and their non-expression arguments do not complete as expressions`() {
        assertTrue(values(":").contains(":all"), "command names not offered")
        assertTrue(values(":fhir ").contains("r5"), "versions not offered")
        // :load takes a path, so resource types must not appear.
        assertTrue(values(":load sam").none { it == "Specimen" || it == "Patient" })
    }

    @Test
    fun `an empty prompt offers the resource types the pool actually holds`() {
        val byName = candidates("").associateBy { it.value() }
        assertEquals("in pool", byName.getValue("Patient").group())
        assertEquals("in pool", byName.getValue("Observation").group())
        // The full 146-type schema stays out of the way until a prefix is typed.
        assertTrue(byName.keys.none { it == "Practitioner" }, "got ${byName.keys}")
    }

    @Test
    fun `choice elements complete by base name only`() {
        val values = values("Observation.")
        assertTrue(values.contains("value"), "got $values")
        assertTrue(values.none { it == "valueQuantity" }, "wire names must not be offered")
    }

    private companion object {
        /** Stands in for the REPL's own command list. */
        val COMMANDS = listOf(
            FhirPathCompleter.CommandInfo(":all", "<expr>", "evaluate across the pool"),
            FhirPathCompleter.CommandInfo(":why", "<expr>", "find the step that goes empty"),
            FhirPathCompleter.CommandInfo(":load", "<path>", "import a path"),
            FhirPathCompleter.CommandInfo(":fhir", "[version]", "switch FHIR version"),
        )
    }
}
