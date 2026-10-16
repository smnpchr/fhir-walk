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
 * The real-world shape: a native stool sample aliquoted through *aliquot groups*
 * into direct aliquots and swabs, each swab aliquoted again into pathogen- and
 * genetics-analysis sets. Aliquots never reference their primary directly -- the
 * group sits in between, so navigating the lineage means chaining resolve().
 *
 * ```
 * spec-master-stool                       ST-2026-0001
 * ├── ag-stool-direct                     (aliquot group)
 * │   └── al-stool-001..003
 * └── ag-stool-swab                       (aliquot group)
 *     └── swab-001, swab-002
 *         ├── ag-sw00N-patho              (aliquot group)
 *         │   └── al-pa-00N-1, al-pa-00N-2
 *         └── ag-sw00N-gen                (aliquot group)
 *             └── al-ge-00N-1, al-ge-00N-2
 * ```
 */
class BiobankPoolTest {

    private val ctx = FhirVersion.R4.create()
    private val pool = ResourcePool(ctx)
    private val evaluator = Evaluator(ctx, pool)
    private val ext = "https://biobank.example.org/StructureDefinition"

    init {
        Importer(ctx, pool).load(Paths.get("samples/biobank"))
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
        // bundle (2) + 6 aliquot-group files + 13 NDJSON aliquots + 1 XML observation
        assertEquals(22, pool.size)
        assertEquals(20, pool.ofType("Specimen").size)
        assertEquals(1, pool.ofType("Observation").size)
        assertEquals(1, pool.ofType("Patient").size)
    }

    @Test
    fun `lineage is walked by chaining resolve through the aliquot groups`() {
        val leaf = "Specimen/al-pa-001-1"
        assertEquals(listOf("AL-PA-001-1"), strings(leaf, "Specimen.identifier.value"))

        // One hop reaches the group, not the swab -- the group is the intermediate.
        assertEquals(
            listOf("AG-2026-0110"),
            strings(leaf, "Specimen.parent.resolve().identifier.value"),
        )
        assertEquals(
            listOf("AL-SW-001"),
            strings(leaf, "Specimen.parent.resolve().parent.resolve().identifier.value"),
        )
        // Four hops from a terminal aliquot back to the master sample.
        assertEquals(
            listOf("ST-2026-0001"),
            strings(
                leaf,
                "Specimen.parent.resolve().parent.resolve()" +
                    ".parent.resolve().parent.resolve().identifier.value",
            ),
        )
    }

    @Test
    fun `aliquot groups are identifiable by role and carry their purpose`() {
        val roles = evaluator.evaluateAll(
            "Specimen.type.coding.where(system = " +
                "'https://biobank.example.org/CodeSystem/specimen-role').code",
        )
        assertEquals(20, roles.hits.size)

        val purposes = evaluator.evaluateAll(
            "Specimen.extension('$ext/aliquot-group-purpose').value.coding.code",
        )
        // Exactly the six aliquot groups carry a purpose.
        assertEquals(6, purposes.hits.size)
        assertEquals(
            setOf("storage", "swabbing", "pathogen", "genetics"),
            purposes.hits.flatMap { hit -> hit.values.map { (it as IPrimitiveType<*>).valueAsString } }.toSet(),
        )
    }

    @Test
    fun `nested storage extension is navigable and censused by sub-url`() {
        assertEquals(
            listOf("-80"),
            strings(
                "Specimen/al-pa-001-1",
                "Specimen.extension('$ext/specimen-storage').extension('temperature').value.value",
            ),
        )
        val nested = pool.census.extensionsAt("Specimen.extension('$ext/specimen-storage')").map { it.value }
        assertEquals(setOf("temperature", "freezer", "position"), nested.toSet())
    }

    @Test
    fun `extensions on sub-elements are censused at their own path`() {
        assertEquals(
            listOf("$ext/container-lot-number"),
            pool.census.extensionsAt("Specimen.container").map { it.value },
        )
        assertEquals(
            listOf("$ext/collection-ambient-temperature"),
            pool.census.extensionsAt("Specimen.collection").map { it.value },
        )
        // ... and not confused with the ones on the Specimen root.
        val onRoot = pool.census.extensionsAt("Specimen").map { it.value }
        assertTrue(onRoot.none { it.endsWith("container-lot-number") }, "got $onRoot")
        assertTrue(onRoot.any { it.endsWith("specimen-freezing-time") }, "got $onRoot")
    }

    @Test
    fun `partial coverage is reported rather than hidden`() {
        val result = evaluator.evaluateAll("Specimen.extension('$ext/specimen-freezing-time').value")
        assertEquals(12, result.hits.size)
        assertEquals(10, result.misses) // 8 specimens without one, plus Patient and Observation
        assertEquals(0, result.errors)
    }

    @Test
    fun `why distinguishes a data gap from a wrong expression`() {
        // swab-002 has no freezing time, but twelve other specimens do: the
        // expression is right and the data is incomplete.
        val swab = assertNotNull(pool.find("Specimen/swab-002"))
        val missingExtension = PrefixWalker(evaluator, pool)
            .diagnose(swab.resource, "Specimen.extension('$ext/specimen-freezing-time').value")
        assertEquals(1, missingExtension.firstEmpty)
        val extCoverage = assertNotNull(missingExtension.poolCoverage)
        assertEquals(12, extCoverage.matching)
        assertEquals(20, extCoverage.considered)

        // Specimen.collection, by contrast, is only ever populated on the master
        // sample -- one hit out of twenty says "wrong level", not "missing data".
        val collection = PrefixWalker(evaluator, pool)
            .diagnose(swab.resource, "Specimen.collection.collectedDateTime")
        assertEquals(1, collection.firstEmpty)
        assertEquals(1, assertNotNull(collection.poolCoverage).matching)
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
    fun `completion offers the biobank extension urls scoped to their element`() {
        val terminal = TerminalBuilder.builder().dumb(true).build()
        val reader = LineReaderBuilder.builder().terminal(terminal).build()
        val completer = FhirPathCompleter({ ModelNavigator(ctx, pool.census) }, { pool }, { emptyList() })
        val parser = FhirPathParser()

        fun values(expression: String): List<String> {
            val line = parser.parse(expression, expression.length, Parser.ParseContext.COMPLETE)
            val out = mutableListOf<Candidate>()
            completer.complete(reader, line, out)
            return out.map { it.value() }
        }

        val onContainer = values("Specimen.container.extension('")
        assertEquals(listOf("$ext/container-lot-number"), onContainer)

        val onRoot = values("Specimen.extension('")
        assertTrue(onRoot.any { it.endsWith("specimen-freezing-time") }, "got $onRoot")
        assertTrue(onRoot.none { it.endsWith("container-lot-number") }, "got $onRoot")

        // Inside the nested storage extension, only its three sub-urls.
        val nested = values("Specimen.extension('$ext/specimen-storage').extension('")
        assertEquals(setOf("temperature", "freezer", "position"), nested.toSet())
    }
}
