package dev.fhirwalk

import ca.uhn.fhir.context.FhirVersionEnum
import dev.fhirwalk.complete.FhirPathCompleter
import dev.fhirwalk.model.FhirVersion
import dev.fhirwalk.model.FhirVersionUnavailable
import dev.fhirwalk.repl.FhirPathParser
import dev.fhirwalk.repl.Repl
import dev.fhirwalk.repl.Workspaces
import org.jline.reader.Candidate
import org.jline.reader.LineReaderBuilder
import org.jline.reader.Parser
import org.jline.terminal.TerminalBuilder
import org.junit.jupiter.api.Assumptions.assumeFalse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Which FHIR versions load, and what one that does not says. R4 and R5 ship; R4B and
 * DSTU3 need a line in build.gradle.kts, so the tests about a missing one skip where
 * that line has been added.
 */
class FhirVersionTest {

    private fun absent(version: FhirVersion) = assumeFalse(FhirVersionEnum.valueOf(version.name).isPresentOnClasspath, "${version.id} is on the classpath here")

    @Test
    fun `a version is available exactly when its structures are on the classpath`() {
        assertTrue(FhirVersion.R4.isAvailable())
        assertTrue(FhirVersion.R5.isAvailable())
        for (version in FhirVersion.entries) {
            assertEquals(FhirVersionEnum.valueOf(version.name).isPresentOnClasspath, version.isAvailable(), version.id)
        }
    }

    @Test
    fun `a missing structures JAR says which dependency to add`() {
        absent(FhirVersion.DSTU3)
        val e = assertFailsWith<FhirVersionUnavailable> { FhirVersion.DSTU3.create() }
        assertTrue("hapi-fhir-structures-dstu3" in e.message!!, e.message)
        assertTrue("Maven" !in e.message!!, e.message)
        assertEquals("hapi-fhir-structures-dstu3", FhirVersion.DSTU3.missing)
    }

    @Test
    fun `fhir lists the versions that load and what the others need`() {
        absent(FhirVersion.DSTU3)
        val repl = Repl(FhirVersion.R4, emptyList(), Workspaces(null), "w1")
        val listed = repl.outputOf(":fhir").lines()
        assertTrue(listed.first().startsWith("FHIR r4 — available: r4, r5"), listed.toString())
        assertTrue("dstu3" !in listed.first(), listed.toString())
        assertTrue(listed.any { "dstu3 needs hapi-fhir-structures-dstu3 in build.gradle.kts" in it }, listed.toString())

        // Switching to one says so, and stays where it was.
        val switched = repl.outputOf(":fhir dstu3").lines()
        assertTrue(switched.any { "hapi-fhir-structures-dstu3" in it }, switched.toString())
        assertTrue(repl.outputOf(":fhir").lines().first().startsWith("FHIR r4 "))
    }

    @Test
    fun `tab after fhir offers only the versions that load`() {
        absent(FhirVersion.DSTU3)
        val ctx = FhirVersion.R4.create()
        val pool = dev.fhirwalk.pool.ResourcePool(ctx)
        val completer = FhirPathCompleter({ dev.fhirwalk.model.ModelNavigator(ctx, pool.census) }, { pool }, { emptyList() })
        val reader = LineReaderBuilder.builder().terminal(TerminalBuilder.builder().dumb(true).build()).build()
        val offered = mutableListOf<Candidate>().also {
            completer.complete(reader, FhirPathParser().parse(":fhir ", 6, Parser.ParseContext.COMPLETE), it)
        }.map { it.value() }
        assertEquals(FhirVersion.entries.filter { it.isAvailable() }.map { it.id }, offered)
        assertTrue("dstu3" !in offered)
    }
}
