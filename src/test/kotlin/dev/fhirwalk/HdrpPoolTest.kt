package dev.fhirwalk

import dev.fhirwalk.complete.FhirPathCompleter
import dev.fhirwalk.eval.Evaluator
import dev.fhirwalk.eval.Outcome
import dev.fhirwalk.eval.PrefixWalker
import dev.fhirwalk.model.FhirVersion
import dev.fhirwalk.model.ModelNavigator
import dev.fhirwalk.pool.Importer
import dev.fhirwalk.pool.ResourcePool
import dev.fhirwalk.repl.FhirPathParser
import org.hl7.fhir.instance.model.api.IPrimitiveType
import org.jline.reader.Candidate
import org.jline.reader.LineReaderBuilder
import org.jline.reader.Parser
import org.jline.terminal.TerminalBuilder
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The real-world shape, from `samples/hdrp`: a local HDRP (CentraXX) export. Masters
 * are aliquoted through *aliquot groups*, and PBMC isolated from a master is
 * aliquoted again, so a pellet is four `resolve()` hops from its master:
 *
 * ```
 * 30040  MASTER        BLD in a PED (EDTA) tube
 * └── 30041  ALIQUOTGROUP  ZZZ(pbm)
 *     └── 30042  DERIVED   ZZZ(pbm), PBMC isolate
 *         └── 30043  ALIQUOTGROUP  ZZZ(ppm)
 *             └── 30044, 30045  DERIVED  ZZZ(ppm), pellets
 * ```
 *
 * Only a master records its primary container and organisation unit, as the
 * local-to-central mappings expect: an aliquot has to reach its master for them.
 */
class HdrpPoolTest {

    private val ctx = FhirVersion.R4.create()
    private val pool = ResourcePool(ctx)
    private val evaluator = Evaluator(ctx, pool)

    init {
        Importer(ctx, pool).load(FIXTURE)
    }

    private companion object {
        val FIXTURE = Paths.get("samples/hdrp")
        const val EXT = "https://fhir.centraxx.de/extension"
        const val DAR = "http://hl7.org/fhir/StructureDefinition/data-absent-reason"
        const val SAMPLE_ID = "identifier.where(type.coding.code = 'SAMPLEID').value"
        val LOCATION_PARTS = setOf(
            "$EXT/sample/sampleLocationPath",
            "$EXT/sample/xPosition",
            "$EXT/sample/yPosition",
        )
    }

    private fun strings(id: String, expression: String): List<String> {
        val item = assertNotNull(pool.find(id), "not in pool: $id")
        return when (val outcome = evaluator.evaluate(item.resource, expression)) {
            is Outcome.Values -> outcome.values.map { (it as IPrimitiveType<*>).valueAsString }
            is Outcome.Failure -> error("$expression -> ${outcome.message}")
        }
    }

    @Test
    fun `all three import paths contribute to one pool`() {
        // bundle (2 patients, 5 masters) + 6 aliquot-group files + 11 NDJSON
        // aliquots + an XML and a JSON observation
        assertEquals(26, pool.size)
        assertEquals(22, pool.ofType("Specimen").size)
        assertEquals(2, pool.ofType("Observation").size)
        assertEquals(2, pool.ofType("Patient").size)
    }

    @Test
    fun `lineage is walked by chaining resolve through the aliquot groups`() {
        val leaf = "Specimen/30044"
        assertEquals(listOf("LIMS-30044"), strings(leaf, "Specimen.$SAMPLE_ID"))

        // One hop reaches the group, not the isolate -- the group is the intermediate.
        assertEquals(listOf("LIMS-30043"), strings(leaf, "Specimen.parent.resolve().$SAMPLE_ID"))
        assertEquals(listOf("LIMS-30042"), strings(leaf, "Specimen.parent.resolve().parent.resolve().$SAMPLE_ID"))
        // Four hops from a pellet back to the master sample.
        assertEquals(
            listOf("LIMS-30040"),
            strings(leaf, "Specimen.parent.resolve().parent.resolve().parent.resolve().parent.resolve().$SAMPLE_ID"),
        )
    }

    @Test
    fun `an aliquot's central type depends on its master's primary container`() {
        // PL1 under an EDTA tube is EDTA plasma, under a citrate tube citrate plasma;
        // the aliquots themselves cannot tell, only their master two hops up can.
        val container = "Specimen.parent.resolve().parent.resolve()" +
            ".extension('$EXT/sprec').extension('$EXT/sprec/primarySampleContainer').value.code"
        assertEquals(listOf("PED"), strings("Specimen/30003", container))
        assertEquals(listOf("SCI"), strings("Specimen/30012", container))
        assertEquals(listOf("PL1"), strings("Specimen/30012", "Specimen.type.coding.code"))
        val citrate = evaluator.evaluateAll(
            "Specimen.where(type.coding.code = 'PL1' and extension('$EXT/sampleCategory').value.code = 'DERIVED')" +
                ".where($container = 'SCI')",
        )
        assertEquals(setOf("Specimen/30012", "Specimen/30013"), citrate.hits.map { it.resource.label }.toSet())
    }

    @Test
    fun `aliquot groups are identifiable by category`() {
        val categories = evaluator.evaluateAll("Specimen.extension('$EXT/sampleCategory').value.code")
        assertEquals(22, categories.hits.size)
        val byCategory = categories.hits.groupingBy { (it.values.single() as IPrimitiveType<*>).valueAsString }.eachCount()
        assertEquals(mapOf("MASTER" to 5, "ALIQUOTGROUP" to 6, "DERIVED" to 11), byCategory)
    }

    @Test
    fun `nested location extension is navigable and censused by sub-url`() {
        assertEquals(
            listOf("2"),
            strings(
                "Specimen/30044",
                "Specimen.extension('$EXT/sample/sampleLocation').extension('$EXT/sample/xPosition').value",
            ),
        )
        val nested = pool.census.extensionsAt("Specimen.extension('$EXT/sample/sampleLocation')").map { it.value }
        assertEquals(LOCATION_PARTS, nested.toSet())
    }

    @Test
    fun `extensions on sub-elements are censused at their own path`() {
        // The saliva sample's receipt time is unknown: HDRP says so on the primitive.
        assertEquals(listOf(DAR), pool.census.extensionsAt("Specimen.receivedTime").map { it.value })
        assertEquals(listOf("unknown"), strings("Specimen/30030", "Specimen.receivedTime.extension('$DAR').value"))
        // ... and not confused with the ones on the Specimen root.
        val onRoot = pool.census.extensionsAt("Specimen").map { it.value }
        assertTrue(onRoot.none { it == DAR }, "got $onRoot")
        assertTrue(onRoot.any { it.endsWith("/sample/repositionDate") }, "got $onRoot")
    }

    @Test
    fun `partial coverage is reported rather than hidden`() {
        val result = evaluator.evaluateAll("Specimen.extension('$EXT/sample/repositionDate').value")
        assertEquals(8, result.hits.size)
        // 3 aliquots not stored yet, 11 masters and groups, 2 patients, 2 observations
        assertEquals(18, result.misses)
        assertEquals(0, result.errors)
    }

    @Test
    fun `why distinguishes a data gap from a wrong expression`() {
        // 30005 is not stored yet, but eight other aliquots are: the expression is
        // right and the data is incomplete.
        val unstored = assertNotNull(pool.find("Specimen/30005"))
        val missingExtension = PrefixWalker(evaluator, pool)
            .diagnose(unstored.resource, "Specimen.extension('$EXT/sample/repositionDate').value")
        assertEquals(1, missingExtension.firstEmpty)
        val extCoverage = assertNotNull(missingExtension.poolCoverage)
        assertEquals(8, extCoverage.matching)
        assertEquals(22, extCoverage.considered)

        // Specimen.collection, by contrast, is only ever populated on a master --
        // five out of twenty-two says "wrong level", not "missing data".
        val collection = PrefixWalker(evaluator, pool)
            .diagnose(unstored.resource, "Specimen.collection.collectedDateTime")
        assertEquals(1, collection.firstEmpty)
        assertEquals(5, assertNotNull(collection.poolCoverage).matching)
    }

    @Test
    fun `parent references are censused so resolve types from the corpus`() {
        assertEquals(
            listOf("Specimen"),
            pool.census.referenceTargetsAt("Specimen.parent").map { it.value },
        )
        val navigator = ModelNavigator(ctx, pool.census)
        assertEquals("Specimen", navigator.resolveType("Specimen.parent.resolve()")?.name)
        // ... which means members complete after a resolve() hop.
        assertTrue(navigator.childrenAt("Specimen.parent.resolve()").any { it.name == "parent" })
    }

    @Test
    fun `a syntax error is reported once, in readable form`() {
        // HAPI wraps lexer failures as
        //   HAPI-2409: org.hl7...FHIRLexer$FHIRLexerException: Error @1, 14: …
        // and only the tail concerns the user's expression.
        val error = assertNotNull(evaluator.validate("Specimen.subject.foo..bar"))
        assertTrue(error.startsWith("Error @"), "got: $error")
        assertTrue(!error.contains("HAPI-"), "HAPI code leaked: $error")
        assertTrue(!error.contains("Exception"), "exception class leaked: $error")

        // A valid expression validates cleanly even though it matches nothing.
        assertEquals(null, evaluator.validate("Specimen.subject.id"))
    }

    @Test
    fun `diagnosis picks a resource of the expression's own root type`() {
        // The pool's first resource is a Patient; diagnosing a Specimen expression
        // against it would report "empty at step one" and teach nothing.
        assertEquals("Patient", pool.all().first().type)

        val target = assertNotNull(PrefixWalker(evaluator, pool).diagnosisTarget("Specimen.subject.id"))
        assertEquals("Specimen", target.type)

        // ... so the diagnosis reaches the step that actually fails: Reference.id is
        // the element id, not the reference string.
        val diagnosis = PrefixWalker(evaluator, pool).diagnose(target.resource, "Specimen.subject.id")
        assertEquals(2, diagnosis.firstEmpty)
        assertEquals(0, assertNotNull(diagnosis.poolCoverage).matching)
    }

    @Test
    fun `references are not mistaken for choice elements`() {
        // HAPI gives a reference child two valid names (parent / parentResource), so
        // counting names would label it a choice and send completion down the
        // corpus-narrowing path instead of simply typing it as a Reference.
        val navigator = ModelNavigator(ctx, pool.census)
        val children = navigator.childrenAt("Specimen").associateBy { it.name }
        assertEquals("Reference", children.getValue("parent").typeName)
        assertEquals("Reference", children.getValue("subject").typeName)
        assertTrue(children.getValue("parent").isCollection)
        assertTrue(children.keys.none { it == "parentResource" }, "synthetic name leaked")

        assertEquals("Reference", navigator.resolveType("Specimen.parent")?.name)
        assertTrue(pool.census.choiceTypesAt("Specimen.parent").isEmpty())

        // A genuine choice is still labelled as one.
        val processing = navigator.childrenAt("Specimen.processing").associateBy { it.name }
        assertTrue(processing.getValue("time").typeName.startsWith("choice:"), "got $processing")
    }

    @Test
    fun `completion offers the HDRP extension urls scoped to their element`() {
        val terminal = TerminalBuilder.builder().dumb(true).build()
        val reader = LineReaderBuilder.builder().terminal(terminal).build()
        val completer = FhirPathCompleter({ ModelNavigator(ctx, pool.census) }, { pool }, { emptyList() })
        val parser = FhirPathParser()

        fun offered(expression: String): List<Candidate> {
            val line = parser.parse(expression, expression.length, Parser.ParseContext.COMPLETE)
            val out = mutableListOf<Candidate>()
            completer.complete(reader, line, out)
            return out
        }

        fun displays(expression: String) = offered(expression).map { it.displ() }

        assertEquals(listOf(DAR), displays("Specimen.receivedTime.extension('"))

        val onRoot = displays("Specimen.extension('")
        assertTrue(onRoot.any { it.endsWith("/sample/repositionDate") }, "got $onRoot")
        assertTrue(onRoot.none { it == DAR }, "got $onRoot")

        // Inside the nested location extension, only its three sub-urls.
        assertEquals(LOCATION_PARTS, displays("Specimen.extension('$EXT/sample/sampleLocation').extension('").toSet())

        // At a member position the whole extension() step is offered, parens and
        // quotes included, so picking one needs no further typing.
        val steps = offered("Specimen.").map { it.value() }
        assertTrue(steps.contains("extension('$EXT/sampleCategory')"), "got $steps")
        assertTrue(offered("Specimen.receivedTime.").map { it.value() }.contains("extension('$DAR')"))
    }
}
