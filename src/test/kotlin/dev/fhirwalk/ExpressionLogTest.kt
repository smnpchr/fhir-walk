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
    fun `a hand edit without a final newline is not joined to the next entry`() {
        Files.createDirectories(file.parent)
        Files.writeString(file, "Patient.name.family")
        ExpressionLog(file).record("Patient.birthDate")
        assertEquals(listOf("Patient.name.family", "Patient.birthDate"), Files.readAllLines(file))
    }

    @Test
    fun `two sessions sharing the file agree on the numbers`() {
        val a = ExpressionLog(file)
        val b = ExpressionLog(file)
        a.record("Patient.name.family")
        b.record("Patient.birthDate")
        // b sees a's entry, so its own is #2 in both.
        assertEquals(listOf("Patient.name.family", "Patient.birthDate"), a.entries())
        assertEquals(a.entries(), b.entries())
        // And neither records what the other already has.
        assertFalse(a.record("Patient.birthDate"))
    }

    @Test
    fun `an unreadable file is reported and never appended to`() {
        Files.createDirectories(file.parent)
        val bytes = byteArrayOf('P'.code.toByte(), 0xC3.toByte(), '\n'.code.toByte())
        Files.write(file, bytes)
        val log = ExpressionLog(file)
        assertTrue(log.entries().isEmpty())
        assertTrue(log.problem?.contains("cannot read") == true, "got ${log.problem}")
        log.record("Patient.name.family")
        // Kept for the session, but the file is left as it was.
        assertEquals(listOf("Patient.name.family"), log.entries())
        assertTrue(Files.readAllBytes(file).contentEquals(bytes))
    }

    @Test
    fun `an unwritable file keeps entries for the session and says why`() {
        val log = ExpressionLog(file)
        // A file where the directory should be.
        Files.createDirectories(file.parent.parent)
        Files.writeString(file.parent, "")
        log.record("Patient.name.family")
        assertEquals(listOf("Patient.name.family"), log.entries())
        assertTrue(log.problem?.contains("cannot write") == true, "got ${log.problem}")
    }
}
