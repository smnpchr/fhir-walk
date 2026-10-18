package dev.fhirwalk

import dev.fhirwalk.complete.FhirPathCompleter
import dev.fhirwalk.model.FhirVersion
import dev.fhirwalk.model.ModelNavigator
import dev.fhirwalk.pool.Importer
import dev.fhirwalk.pool.ResourcePool
import dev.fhirwalk.repl.FhirPathParser
import org.jline.reader.Candidate
import org.jline.reader.LineReaderBuilder
import org.jline.reader.Parser
import org.jline.terminal.TerminalBuilder
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `repeat()` is typed when its projection is closed over the input's type, which is
 * how hierarchies are walked: `repeat(parent.resolve())` on a Specimen is Specimens
 * all the way up. The pool is a small aliquot chain and a nested questionnaire.
 */
class RepeatTypingTest {

    private val ctx = FhirVersion.R4.create()
    private val pool = ResourcePool(ctx)
    private val navigator: ModelNavigator

    init {
        val dir = Files.createTempDirectory("repeat")
        fun specimen(id: String, type: String, parent: String?) = """{"resourceType":"Specimen","id":"$id",""" +
            """"type":{"coding":[{"code":"$type"}]},"subject":{"reference":"Patient/p1"}""" +
            (parent?.let { ""","parent":[{"reference":"Specimen/$it"}]""" } ?: "") + "}"
        Files.writeString(dir.resolve("p1.json"), """{"resourceType":"Patient","id":"p1"}""")
        Files.writeString(dir.resolve("master.json"), specimen("master", "BLOOD", null))
        Files.writeString(dir.resolve("group.json"), specimen("group", "PLASMA", "master"))
        Files.writeString(dir.resolve("aliquot.json"), specimen("aliquot", "PLASMA", "group"))
        Files.writeString(
            dir.resolve("q.json"),
            """{"resourceType":"Questionnaire","id":"q","status":"active",""" +
                """"item":[{"linkId":"1","type":"group","item":[{"linkId":"1.1","type":"string"}]}]}""",
        )
        Importer(ctx, pool).load(dir)
        navigator = ModelNavigator(ctx, pool.census)
    }

    @Test
    fun `a closed repeat keeps its input's type`() {
        assertEquals("Specimen", navigator.resolveType("Specimen.repeat(parent.resolve())")?.name)
        // Mid-path too, after a resolve() has restarted at the resource.
        assertEquals("Specimen", navigator.resolveType("Specimen.parent.resolve().repeat(parent.resolve())")?.name)
        // A backbone element closed over itself: the classic nested item.
        val item = navigator.resolveType("Questionnaire.item")
        assertEquals(item?.implementingClass, navigator.resolveType("Questionnaire.item.repeat(item)")?.implementingClass)
    }

    @Test
    fun `a repeat that changes type stays unknown`() {
        // A Specimen's subject is a Patient, and a Patient has no subject: the rounds
        // are different types, so nothing can be said about the union.
        assertNull(navigator.resolveType("Specimen.repeat(subject.resolve())"))
        assertNull(navigator.resolveType("Specimen.repeat(type)"))
        assertNull(navigator.resolveType("Specimen.repeat()"))
    }

    @Test
    fun `navigation goes on after a closed repeat, census counts included`() {
        assertEquals("CodeableConcept", navigator.resolveType("Specimen.repeat(parent.resolve()).type")?.name)
        // The census path stays where the repeat started, so the values observed
        // there are offered.
        assertEquals("Specimen.type.coding.code", navigator.censusPath("Specimen.repeat(parent.resolve()).type.coding.code"))
    }

    @Test
    fun `completion offers members after a closed repeat`() {
        val terminal = TerminalBuilder.builder().dumb(true).build()
        val reader = LineReaderBuilder.builder().terminal(terminal).build()
        val completer = FhirPathCompleter({ navigator }, { pool }, { emptyList() })
        fun offered(expression: String): List<Candidate> = mutableListOf<Candidate>().also {
            completer.complete(reader, FhirPathParser().parse(expression, expression.length, Parser.ParseContext.COMPLETE), it)
        }
        val members = offered("Specimen.repeat(parent.resolve()).").map { it.value() }
        assertTrue("parent" in members && "type" in members, "got $members")
        val codes = offered("Specimen.repeat(parent.resolve()).type.coding.code = '").map { it.value() }
        assertTrue(codes.any { it.startsWith("BLOOD") }, "got $codes")
        // Not closed: nothing to offer, as before.
        assertTrue(offered("Specimen.repeat(subject.resolve()).").none { it.value() == "parent" })
    }
}
