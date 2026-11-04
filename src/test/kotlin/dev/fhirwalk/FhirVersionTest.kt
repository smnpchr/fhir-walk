package dev.fhirwalk

import ca.uhn.fhir.context.FhirVersionEnum
import dev.fhirwalk.complete.FhirPathCompleter
import dev.fhirwalk.eval.Evaluator
import dev.fhirwalk.eval.Outcome
import dev.fhirwalk.model.FhirVersion
import dev.fhirwalk.model.FhirVersionUnavailable
import dev.fhirwalk.model.ModelNavigator
import dev.fhirwalk.pool.Importer
import dev.fhirwalk.pool.ResourcePool
import dev.fhirwalk.repl.FhirPathParser
import dev.fhirwalk.repl.Repl
import dev.fhirwalk.repl.Workspaces
import org.jline.reader.Candidate
import org.jline.reader.LineReaderBuilder
import org.jline.reader.Parser
import org.jline.terminal.TerminalBuilder
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Which FHIR versions load, and what one that does not says. R4 and R5 ship; R4B and
 * DSTU3 need two lines each in build.gradle.kts, so the tests about a missing one
 * skip where both have been added.
 */
class FhirVersionTest {

    /** Skips unless [version] misses both its JARs here, as it does as shipped. */
    private fun absent(version: FhirVersion) =
        assumeTrue(version.missing.size == 2, "${version.id} is (partly) on the classpath here: ${version.missing}")

    private val observation = """{"resourceType":"Observation","status":"final","code":{"text":"n"},"valueQuantity":{"value":5}}"""

    @Test
    fun `every version said to be available evaluates a type operation`() {
        // What a missing definitions JAR breaks: ofType() fails on every resource.
        assertTrue(FhirVersion.R4.isAvailable() && FhirVersion.R5.isAvailable())
        for (version in FhirVersion.entries.filter { it.isAvailable() }) {
            val ctx = version.create()
            val resource = ctx.newJsonParser().parseResource(observation)
            val outcome = Evaluator(ctx, ResourcePool(ctx)).evaluate(resource, "Observation.value.ofType(Quantity).value")
            assertTrue(outcome is Outcome.Values && outcome.values.isNotEmpty(), "${version.id}: $outcome")
        }
    }

    @Test
    fun `a version missing its JARs names both dependencies`() {
        absent(FhirVersion.DSTU3)
        assertEquals(listOf("hapi-fhir-structures-dstu3", "hapi-fhir-validation-resources-dstu3"), FhirVersion.DSTU3.missing)
        val e = assertFailsWith<FhirVersionUnavailable> { FhirVersion.DSTU3.create() }
        assertTrue("ca.uhn.hapi.fhir:hapi-fhir-structures-dstu3 and ca.uhn.hapi.fhir:hapi-fhir-validation-resources-dstu3" in e.message!!, e.message)
        assertTrue("Maven" !in e.message!!, e.message)
        // Structures alone are not enough: HAPI's own check says yes to fewer.
        assertEquals(FhirVersionEnum.DSTU3.isPresentOnClasspath, "hapi-fhir-structures-dstu3" !in FhirVersion.DSTU3.missing)
    }

    @Test
    fun `fhir lists the versions that load and what the others need`() {
        absent(FhirVersion.DSTU3)
        absent(FhirVersion.R4B)
        val repl = Repl(FhirVersion.R4, emptyList(), Workspaces(null), "w1")
        val listed = repl.outputOf(":fhir").lines()
        assertEquals("FHIR r4 — available: r4, r5", listed.first())
        assertTrue(listed.any { "r4b needs hapi-fhir-structures-r4b and hapi-fhir-validation-resources-r4b in build.gradle.kts" in it }, listed.toString())
        assertTrue(listed.any { "dstu3 needs hapi-fhir-structures-dstu3 and hapi-fhir-validation-resources-dstu3" in it }, listed.toString())

        // Switching to one says so, and stays where it was.
        val switched = repl.outputOf(":fhir dstu3").lines()
        assertTrue(switched.any { "hapi-fhir-structures-dstu3" in it }, switched.toString())
        assertTrue(repl.outputOf(":fhir").lines().first().startsWith("FHIR r4 "))
    }

    @Test
    fun `tab after fhir offers only the versions that load`() {
        absent(FhirVersion.DSTU3)
        val ctx = FhirVersion.R4.create()
        val pool = ResourcePool(ctx)
        val completer = FhirPathCompleter({ ModelNavigator(ctx, pool.census) }, { pool }, { emptyList() })
        val reader = LineReaderBuilder.builder().terminal(TerminalBuilder.builder().dumb(true).build()).build()
        val offered = mutableListOf<Candidate>().also {
            completer.complete(reader, FhirPathParser().parse(":fhir ", 6, Parser.ParseContext.COMPLETE), it)
        }.map { it.value() }
        assertEquals(listOf("r4", "r5"), offered)
    }

    @Test
    fun `a workspace left on a version that cannot load starts on the default, and says why`() {
        absent(FhirVersion.DSTU3)
        val home = Files.createTempDirectory("fhir-walk-home")
        val state = home.resolve("workspaces").resolve("w1").resolve("state")
        Files.createDirectories(state.parent)
        Files.writeString(state, "fhir\tdstu3\n")
        val repl = Repl(null, emptyList(), Workspaces(home), "w1")
        val opened = StringWriter().also { text -> PrintWriter(text).use { repl.open(it) } }.toString()
        assertTrue("hapi-fhir-structures-dstu3" in opened && "starting on r4 instead" in opened, opened)
        assertTrue(repl.outputOf(":fhir").lines().first().startsWith("FHIR r4 "))
    }

    @Test
    fun `a parse failure suggests only a version that loads`() {
        absent(FhirVersion.DSTU3)
        // Transport is an R5 resource: R4 cannot parse it, R5 can.
        val file = Files.createTempFile("transport", ".json")
        Files.writeString(file, """{"resourceType":"Transport","id":"t1","status":"completed","intent":"order"}""")
        val ctx = FhirVersion.R4.create()
        val report = Importer(ctx, ResourcePool(ctx)).load(file)
        val problem = report.problems.single()
        assertTrue("try :fhir r5" in problem, problem)
        assertTrue("dstu3" !in problem && "r4b" !in problem, problem)
    }
}
