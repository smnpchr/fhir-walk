package dev.fhirwalk

import dev.fhirwalk.repl.NameStore
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NameStoreTest {

    private val dir = Files.createTempDirectory("fhir-walk-names")
    private val file = dir.resolve("nested").resolve("names")

    @Test
    fun `names persist as the expression text`() {
        NameStore(file).apply {
            assertNull(set("family", "Patient.name.family"))
            set("born", "Patient.birthDate")
            assertEquals("Patient.name.family", set("family", "Patient.name"))
        }
        val reopened = NameStore(file)
        assertEquals(mapOf("family" to "Patient.name", "born" to "Patient.birthDate"), reopened.all())
        assertEquals("Patient.birthDate", reopened.remove("born"))
        assertNull(reopened.remove("born"))
        assertEquals(listOf("family\tPatient.name"), Files.readAllLines(file))
    }

    @Test
    fun `malformed lines from a hand edit are skipped`() {
        Files.createDirectories(file.parent)
        Files.writeString(file, "family\tPatient.name.family\nno tab here\n2x\tPatient.id\nempty\t\n")
        assertEquals(mapOf("family" to "Patient.name.family"), NameStore(file).all())
    }

    @Test
    fun `two sessions sharing the file see each other's names`() {
        val a = NameStore(file)
        val b = NameStore(file)
        a.set("family", "Patient.name.family")
        b.set("born", "Patient.birthDate")
        assertEquals(setOf("family", "born"), a.all().keys)
        assertEquals(listOf("family", "born"), b.namesOf("Patient.name.family") + b.namesOf("Patient.birthDate"))
    }

    @Test
    fun `an unreadable file is reported and never rewritten`() {
        Files.createDirectories(file.parent)
        val bytes = byteArrayOf('a'.code.toByte(), '\t'.code.toByte(), 0xC3.toByte(), '\n'.code.toByte())
        Files.write(file, bytes)
        val store = NameStore(file)
        assertTrue(store.all().isEmpty())
        assertTrue(store.problem?.contains("cannot read") == true, "got ${store.problem}")
        store.set("family", "Patient.name.family")
        assertEquals("Patient.name.family", store.get("family"))
        assertTrue(Files.readAllBytes(file).contentEquals(bytes))
    }
}
