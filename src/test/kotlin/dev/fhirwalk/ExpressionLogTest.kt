package dev.fhirwalk

import dev.fhirwalk.repl.ExpressionLog
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The log is what `:view #n` refers to, so its numbers must survive a restart. */
class ExpressionLogTest {

    private val dir = Files.createTempDirectory("fhir-walk-log")
    private val file = dir.resolve("nested").resolve("expressions")

    @Test
    fun `entries persist and keep their numbers across sessions`() {
        ExpressionLog(file).apply {
            assertTrue(record("Patient.name.family"))
            assertTrue(record("Patient.birthDate"))
            assertFalse(record("Patient.name.family"))
        }
        val reopened = ExpressionLog(file)
        assertEquals(listOf("Patient.name.family", "Patient.birthDate"), reopened.entries())
        assertEquals("Patient.birthDate", reopened.get(2))
        assertNull(reopened.get(3))
        reopened.record("Observation.status")
        assertEquals(listOf("Patient.name.family", "Patient.birthDate", "Observation.status"), Files.readAllLines(file))
    }

    @Test
    fun `an unwritable file keeps entries for the session and says why`() {
        // A directory where the file should be.
        Files.createDirectories(file)
        val log = ExpressionLog(file)
        log.record("Patient.name.family")
        assertEquals(listOf("Patient.name.family"), log.entries())
        assertTrue(log.writeError?.contains("cannot write") == true, "got ${log.writeError}")
    }
}
