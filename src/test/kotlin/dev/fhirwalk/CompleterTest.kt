package dev.fhirwalk

import dev.fhirwalk.complete.FhirPathCompleter
import dev.fhirwalk.eval.Focus
import dev.fhirwalk.model.FhirVersion
import dev.fhirwalk.model.ModelNavigator
import dev.fhirwalk.pool.Importer
import dev.fhirwalk.pool.ResourcePool
import dev.fhirwalk.repl.FhirPathParser
import dev.fhirwalk.repl.FhirWalkLineReader
import org.jline.reader.Candidate
import org.jline.reader.LineReader
import org.jline.reader.LineReaderBuilder
import org.jline.reader.Parser
import org.jline.terminal.TerminalBuilder
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.createTempDirectory
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
    fun `trace completes its expression against the resource it names`() {
        val members = values(":trace Patient/pat1 na")
        assertTrue("name" in members, "got $members")
        // Before the label is finished, labels are offered alongside.
        assertTrue("Patient/pat1" in displays(":trace Patient/pa"), "got ${displays(":trace Patient/pa")}")
    }

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
        assertEquals("resource types (in pool)", byName.getValue("Patient").group())
        assertEquals("all resource types", byName.getValue("Practitioner").group())
    }

    @Test
    fun `ofType after resolve offers the types actually referenced`() {
        val candidates = candidates("Observation.subject.resolve().ofType(")
        val observed = candidates.filter { it.group() == "types referenced here" }.map { it.value() }
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
    fun `load narrows a large directory by the name typed before capping it`() {
        val dir = createTempDirectory("fhir-walk-paths")
        try {
            (1..70).forEach { Files.createDirectory(dir.resolve("other$it")) }
            listOf("test-fhir-pipes", "test-test-fhir-pipes").forEach { Files.createDirectory(dir.resolve(it)) }
            assertEquals(listOf("$dir/test-fhir-pipes/", "$dir/test-test-fhir-pipes/"), values(":load $dir/test-"))
            assertEquals(listOf("$dir/test-test-fhir-pipes/"), values(":load $dir/test-t"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `load offers a relative path as typed, without a leading dot`() {
        // JLine matches against the word, so `./samples/` would never match `sam`.
        assertEquals(listOf("samples/"), values(":load sam"))
        assertTrue("samples/basic/" in values(":load samples/b"))
    }

    @Test
    fun `load completes paths as a shell would`() {
        val dir = createTempDirectory("fhir-walk-paths")
        val home = System.getProperty("user.home")
        try {
            listOf(".hidden", "visible", "Mixed").forEach { Files.createDirectory(dir.resolve(it)) }
            // Hidden entries only once a dot is typed, so they cannot crowd out the rest.
            assertEquals(listOf("$dir/Mixed/", "$dir/visible/"), values(":load $dir/"))
            assertEquals(listOf("$dir/.hidden/"), values(":load $dir/.h"))
            // The reader matches ignoring case, so the listing must too.
            assertEquals(listOf("$dir/Mixed/"), values(":load $dir/mi"))
            // No listing returns . or .., so typed without a slash they would go nowhere.
            assertEquals("$dir/../", values(":load $dir/..").first())
            assertTrue("$dir/./" in values(":load $dir/."))
            // ~ is looked up as home but inserted as typed.
            System.setProperty("user.home", dir.toString())
            assertEquals(listOf("~/visible/"), values(":load ~/v"))
            assertEquals(listOf("~/"), values(":load ~"))
        } finally {
            System.setProperty("user.home", home)
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `an empty prompt offers the resource types the pool actually holds`() {
        val byName = candidates("").associateBy { it.value() }
        assertEquals("resource types (in pool)", byName.getValue("Patient").group())
        assertEquals("resource types (in pool)", byName.getValue("Observation").group())
        // The full 146-type schema stays out of the way until a prefix is typed.
        assertTrue(byName.keys.none { it == "Practitioner" }, "got ${byName.keys}")
    }

    @Test
    fun `choice elements complete by base name only`() {
        val values = values("Observation.")
        assertTrue(values.contains("value"), "got $values")
        assertTrue(values.none { it == "valueQuantity" }, "wire names must not be offered")
    }

    @Test
    fun `resolve keeps its type through filters, subsetting and indexers`() {
        // Census keys hold no call syntax, so these used to miss and offer functions only.
        for (path in listOf(
            "Observation.subject.where(reference.startsWith('Patient')).resolve().",
            "Observation.subject.first().resolve().",
            "Observation.subject[0].resolve().",
        )) {
            assertTrue("birthDate" in values(path), "no Patient elements after $path")
        }
    }

    @Test
    fun `members follow a filter whose argument has spaces`() {
        assertTrue("value" in values("Patient.identifier.where(system = 'http://example.org/mrn')."))
    }

    @Test
    fun `scope offers the bundles in the order given, not alphabetically`() {
        val newestFirst = listOf("run-c", "run-a", "run-b")
        val scoped = FhirPathCompleter({ ModelNavigator(ctx, pool.census) }, { pool }, { COMMANDS }, members = { newestFirst })
        val line = parser.parse(":scope ", 7, Parser.ParseContext.COMPLETE)
        val out = mutableListOf<Candidate>()
        scoped.complete(reader, line, out)
        // JLine sorts the candidates before it shows them.
        assertEquals(newestFirst, out.sorted().filter { it.group() == "bundles" }.map { it.value() })
    }

    @Test
    fun `command arguments match whatever the case, as the reader does`() {
        val named = FhirPathCompleter(
            { ModelNavigator(ctx, pool.census) }, { pool }, { COMMANDS },
            savedViews = { setOf("Specimens") },
            workspaces = { listOf("Biobank") },
            pools = { listOf("Exports") },
            metas = { listOf("Exports", "Nightly") },
            members = { listOf("Specimen-r1") },
        )
        fun offered(line: String) = mutableListOf<Candidate>().also {
            named.complete(reader, parser.parse(line, line.length, Parser.ParseContext.COMPLETE), it)
        }.map { it.value() }
        assertTrue("Specimen-r1" in offered(":scope spec"))
        assertTrue("Exports" in offered(":pool exp"))
        assertTrue("Nightly" in offered(":pool nig"))
        assertTrue("Nightly" in offered(":meta NIG"))
        assertTrue("Biobank" in offered(":workspace bio"))
        assertTrue("@Specimens" in offered(":view @spec"))
        assertTrue("Patient/pat1" in values(":log patient/"))
    }

    @Test
    fun `elements after resolve are counted against the target type`() {
        val name = candidates("Observation.subject.resolve().").single { it.value() == "name" }
        assertEquals("elements (in pool)", name.group())
    }

    @Test
    fun `extension urls stay scoped through a filter`() {
        val scoped = candidates("Patient.name.where(use = 'official').extension('")
        assertTrue(scoped.isNotEmpty() && scoped.all { it.group().startsWith("observed on") }, "got ${scoped.map { it.group() }}")
        assertTrue(scoped.any { it.displ().endsWith("humanname-namenszusatz") }, "got ${scoped.map { it.displ() }}")
        assertTrue(scoped.none { it.displ().endsWith("patient-birthPlace") }, "got ${scoped.map { it.displ() }}")
    }

    @Test
    fun `resolve over urn references completes the target's elements`() {
        val pool = ResourcePool(ctx)
        Importer(ctx, pool).load(Paths.get("samples/references/transaction.json"))
        val completer = FhirPathCompleter({ ModelNavigator(ctx, pool.census) }, { pool }, { COMMANDS })
        val line = parser.parse("Encounter.subject.resolve().", 28, Parser.ParseContext.COMPLETE)
        val out = mutableListOf<Candidate>()
        completer.complete(reader, line, out)
        assertTrue(out.any { it.value() == "birthDate" }, "got ${out.map { it.value() }}")
    }

    @Test
    fun `resolve over several target types offers whole ofType steps`() {
        val pool = ResourcePool(ctx)
        Importer(ctx, pool).load(Paths.get("samples/references/transaction.json"))
        val completer = FhirPathCompleter({ ModelNavigator(ctx, pool.census) }, { pool }, { COMMANDS })
        fun complete(expression: String) = mutableListOf<Candidate>().also {
            completer.complete(reader, parser.parse(expression, expression.length, Parser.ParseContext.COMPLETE), it)
        }

        // Patient twice, Group once: ranked by count, closed so the next TAB has elements.
        val steps = complete("Observation.subject.resolve().").filter { it.group().startsWith("ofType()") }
        assertEquals(listOf("ofType(Patient)", "ofType(Group)"), steps.map { it.value() })
        assertTrue(complete("Observation.subject.resolve().ofType(Group).").any { it.value() == "member" })

        // A single target needs no narrowing.
        assertTrue(complete("Encounter.subject.resolve().").none { it.group().startsWith("ofType()") })
    }

    @Test
    fun `a comparison literal offers the values observed on its left operand`() {
        val gender = candidates("Patient.gender = '")
        assertEquals(setOf("female", "male"), gender.map { it.displ() }.toSet())
        assertTrue(gender.all { it.group() == "observed values" })
        // Like every string candidate, accepting one closes the literal.
        assertTrue(gender.all { it.value().endsWith("'") && !it.value().endsWith("')") }, "got ${gender.map { it.value() }}")

        // Inside where(), the closer also ends the call.
        val codes = candidates("Observation.code.coding.where(code = '")
        assertTrue(codes.any { it.value() == "8302-2')" }, "got ${codes.map { it.value() }}")
        // Codes, not the code systems the old fallback offered for every literal.
        assertTrue(codes.none { it.displ() == "http://loinc.org" }, "got ${codes.map { it.displ() }}")
    }

    @Test
    fun `values reach through extension steps and filters`() {
        val ext = "Patient.extension('http://hl7.org/fhir/StructureDefinition/patient-nationality')" +
            ".extension('code').value.coding.code = '"
        assertEquals(listOf("DE"), displays(ext))
        assertTrue("final" in displays("Observation.where(subject.exists()).status = '"))
    }

    @Test
    fun `free text is never suggested as a value`() {
        assertTrue(displays("Patient.name.family = '").none { it == "Mustermann" })
    }

    @Test
    fun `focus candidates stay under the menu threshold and narrow by what is typed`() {
        val pool = ResourcePool(ctx)
        val json = ctx.newJsonParser()
        repeat(150) { pool.add(json.parseResource("""{"resourceType":"Patient","id":"p$it"}"""), null, "test") }
        val completer = FhirPathCompleter({ ModelNavigator(ctx, pool.census) }, { pool }, { COMMANDS })
        fun complete(line: String) = mutableListOf<Candidate>().also {
            completer.complete(reader, parser.parse(line, line.length, Parser.ParseContext.COMPLETE), it)
        }

        // Past 100 JLine asks "see all N possibilities?" instead of showing a menu.
        assertTrue(complete(":focus ").size < 100)
        // A resource past the cap is still reachable once its prefix is typed. The
        // argument splits as FHIRPath, so only the word after the '/' is replaced.
        val narrowed = complete(":focus Patient/p149").filter { it.group() == "pool" }
        assertEquals(listOf("Patient/p149"), narrowed.map { it.displ() })
        assertEquals(listOf("p149"), narrowed.map { it.value() })
    }

    @Test
    fun `view offers recorded expressions by number, newest first`() {
        val recorded = (1..150).map { "Patient.id.where(\$this != 'x$it')" }
        val completer = FhirPathCompleter({ ModelNavigator(ctx, pool.census) }, { pool }, { COMMANDS }, { null }, { recorded })
        fun complete(line: String) = mutableListOf<Candidate>().also {
            completer.complete(reader, parser.parse(line, line.length, Parser.ParseContext.COMPLETE), it)
        }

        val all = complete(":view ")
        assertTrue(all.size < 100, "got ${all.size}")
        assertEquals("#150", all.first().value())
        assertEquals("#150  Patient.id.where(\$this != 'x150')", all.first().displ())
        // The oldest entries are past the cap, but reachable once their number is typed.
        assertEquals((listOf(3) + (30..39)).map { "#$it" }.sorted(), complete(":view #3").map { it.value() }.sorted())
        // A bare number completes as one, and one already on the line is not offered.
        val bare = complete(":view 12 1").map { it.value() }
        assertTrue("1" in bare && "13" in bare && "12" !in bare, "got $bare")
    }

    @Test
    fun `names complete after view, unname and the first word of name`() {
        val recorded = listOf("Patient.name.family", "Patient.birthDate")
        val named = mapOf("family" to "Patient.name.family", "born" to "Patient.birthDate")
        val completer = FhirPathCompleter(
            { ModelNavigator(ctx, pool.census) }, { pool }, { COMMANDS }, { null }, { recorded }, { named },
        )
        fun values(line: String) = mutableListOf<Candidate>().also {
            completer.complete(reader, parser.parse(line, line.length, Parser.ParseContext.COMPLETE), it)
        }.map { it.value() }

        assertEquals(setOf("family", "born", "#2", "#1"), values(":view ").toSet())
        // Neither a name nor a number already on the line comes back.
        assertEquals(setOf("born", "#2"), values(":view family #1 ").toSet())
        assertEquals(listOf("family"), values(":view fa"))
        // Names are case-sensitive; completion is not, and inserts the name as it is spelled.
        assertEquals(listOf("family"), values(":view Fa"))
        // A name and the number it holds are one column: either rules out the other.
        assertEquals(setOf("born", "#2"), values(":view family ").toSet())
        assertEquals(setOf("family", "#1"), values(":view #2 ").toSet())
        assertEquals(setOf("family", "born"), values(":unname-expr ").toSet())
        assertEquals(setOf("family", "born"), values(":name-expr ").toSet())
        assertEquals(listOf("#2", "#1"), values(":name-expr born "))
    }

    @Test
    fun `meta pools complete by name, and their bundles after scope`() {
        val completer = FhirPathCompleter(
            { ModelNavigator(ctx, pool.census) }, { pool }, { COMMANDS },
            pools = { listOf("pool") },
            metas = { listOf("exports", "older") },
            members = { listOf("specimen-r1", "specimen-r2") },
        )
        fun values(line: String) = mutableListOf<Candidate>().also {
            completer.complete(reader, parser.parse(line, line.length, Parser.ParseContext.COMPLETE), it)
        }.map { it.value() }

        assertEquals(listOf("exports"), values(":meta ex"))
        assertEquals(listOf("older"), values(":unmeta o"))
        assertEquals(setOf("30d", "12h"), values(":meta exports archive ").toSet())
        assertEquals(setOf("30d", "12h", "none"), values(":cutoff ").toSet())
        assertEquals(setOf("specimen-r1", "specimen-r2", "all"), values(":scope ").toSet())
        assertTrue("delta" !in values(":scope "))
        // Not read yet: :pool offers it, and says how; :close has nothing to close.
        assertEquals(setOf("pool", "exports", "older"), values(":pool ").toSet())
        assertEquals(listOf("pool"), values(":close "))
    }

    @Test
    fun `view completion stays under the menu threshold with many views and names`() {
        val recorded = (1..30).map { "Patient.id.where(\$this != 'x$it')" }
        val named = (1..50).associate { "n$it" to "Patient.id" }
        val views = (1..60).map { "v$it" }.toSet()
        val completer = FhirPathCompleter(
            { ModelNavigator(ctx, pool.census) }, { pool }, { COMMANDS }, { null }, { recorded }, { named }, { views },
        )
        val line = ":view "
        val found = mutableListOf<Candidate>().also {
            completer.complete(reader, parser.parse(line, line.length, Parser.ParseContext.COMPLETE), it)
        }
        assertTrue(found.size < 100, "got ${found.size}")
        // Views come first, but take a third of the room at most: every name still fits.
        assertEquals(50, found.count { it.group() == "named expressions" })
        assertEquals(33, found.count { it.group() == "saved views" })
    }

    @Test
    fun `what the pool holds is listed before what the schema allows`() {
        val order = FhirWalkLineReader(TerminalBuilder.builder().dumb(true).build()).groupComparator
        fun menu(line: String) = candidates(line).map { it.group() }.distinct().sortedWith(order)
        // Resource types, once a letter is typed: the pool's before the rest, so
        // Patient before PaymentNotice.
        assertEquals(listOf("resource types (in pool)", "all resource types"), menu("P"))
        // After a dot: populated elements and observed extensions, then the schema's
        // remaining elements, then functions.
        assertEquals(
            listOf("elements (in pool)", "extension() — observed here", "elements", "functions"),
            menu("Patient."),
        )
        // In ofType(, the types referenced right here before every type in the pool.
        assertEquals("types referenced here", menu("Observation.subject.resolve().ofType(").first())
    }

    @Test
    fun `with a resource in focus, its elements come before the resource types`() {
        val order = FhirWalkLineReader(TerminalBuilder.builder().dumb(true).build()).groupComparator
        val completer = focusedOn("Patient/pat1")
        val groups = mutableListOf<Candidate>().also {
            completer.complete(reader, parser.parse("", 0, Parser.ParseContext.COMPLETE), it)
        }.map { it.group() }.distinct().sortedWith(order)
        assertTrue("resource types (in pool)" in groups, "got $groups")
        assertEquals("elements (in pool)", groups.first(), "got $groups")
        assertTrue(groups.indexOf("elements") < groups.indexOf("resource types (in pool)"), "got $groups")
    }

    @Test
    fun `view lists saved views first, then names, then recorded expressions`() {
        val recorded = (1..60).map { "Patient.id.where(\$this != 'x$it')" }
        val named = mapOf("family" to "Patient.name.family", "born" to "Patient.birthDate")
        val views = setOf("people", "births")
        val completer = FhirPathCompleter(
            { ModelNavigator(ctx, pool.census) }, { pool }, { COMMANDS }, { null }, { recorded }, { named }, { views },
        )
        val line = ":view "
        val offered = mutableListOf<Candidate>().also {
            completer.complete(reader, parser.parse(line, line.length, Parser.ParseContext.COMPLETE), it)
        }
        // Ordered as the menu orders them: with the comparator the REPL's reader hands JLine.
        val order = FhirWalkLineReader(TerminalBuilder.builder().dumb(true).build()).groupComparator
        val groups = offered.map { it.group() }.distinct().sortedWith(order)
        assertEquals(listOf("saved views", "named expressions", "recorded expressions"), groups)
        assertEquals(setOf("@births", "@people"), offered.filter { it.group() == "saved views" }.map { it.value() }.toSet())
        // Groups not in MenuGroups.FIRST keep JLine's order by name, case-insensitively.
        assertEquals(listOf("commands", "Files", "pools"), listOf("pools", "Files", "commands").sortedWith(order))
    }

    /** A completer whose focus is the result of [expression] on [label]. */
    private fun focusedOn(label: String, expression: String? = null): FhirPathCompleter {
        val nav = ModelNavigator(ctx, pool.census)
        val owner = pool.find(label)!!
        var focus = Focus.of(owner)
        if (expression != null) {
            val evaluator = dev.fhirwalk.eval.Evaluator(ctx, pool)
            val node = (evaluator.evaluate(owner.resource, expression) as dev.fhirwalk.eval.Outcome.Values).values.single()
            focus = Focus.reached(owner, node, ctx.getElementDefinition(node.javaClass)!!.name, expression, focus, nav)
        }
        return FhirPathCompleter({ nav }, { pool }, { COMMANDS }, { focus })
    }

    private fun FhirPathCompleter.values(line: String) = mutableListOf<Candidate>().also {
        complete(reader, parser.parse(line, line.length, Parser.ParseContext.COMPLETE), it)
    }.map { it.value() }

    @Test
    fun `an element focus completes relative to the element`() {
        val completer = focusedOn("Observation/obs2", "code")
        // A bare TAB offers the element's members, not resource types.
        val root = completer.values("")
        assertTrue(root.containsAll(listOf("coding", "text")), "got $root")
        assertTrue("Patient" !in root, "got $root")

        assertTrue(completer.values("coding.").containsAll(listOf("code", "system")))
        assertTrue(completer.values("CodeableConcept.coding.").contains("code"))
        assertTrue(completer.values(":focus coding.").contains("system"))
        // The census still answers, under the resource-rooted spelling.
        assertTrue(completer.values("coding.code = '").contains("8302-2'"), "got ${completer.values("coding.code = '")}")
    }

    @Test
    fun `a resource focus completes relative paths too`() {
        val completer = focusedOn("Patient/pat1")
        val onName = completer.values("name.extension('")
        assertTrue(onName.any { it.contains("humanname-namenszusatz") }, "got $onName")
        // Typed in full, the path is left alone.
        assertTrue(completer.values("Patient.name.").contains("given"))
        // At the root, the resource's own elements sit beside the resource types.
        val root = completer.values(":focus ")
        assertTrue(root.containsAll(listOf("name", "Patient")), "got $root")
        assertTrue(root.size < 100, "got ${root.size}")
    }

    @Test
    fun `a focus label is offered only when the typed prefix matches its case`() {
        val completer = focusedOn("Observation/obs2")
        // Only the word after '/' is replaced, so `patient/` would stay on the line.
        assertTrue(completer.values(":focus patient/p").none { it.startsWith("pat") })
        assertTrue(completer.values(":focus Patient/p").contains("pat1"))
    }

    @Test
    fun `focus labels make room for the members of a resource in focus`() {
        val big = ResourcePool(ctx)
        val json = ctx.newJsonParser()
        repeat(150) { big.add(json.parseResource("""{"resourceType":"Observation","id":"o$it"}"""), null, "test") }
        // Many types in the pool: each is a candidate on a bare TAB, as are the labels.
        ctx.resourceTypes.sorted().filter { it != "Observation" }.take(40).forEach {
            big.add(json.parseResource("""{"resourceType":"$it","id":"x"}"""), null, "test")
        }
        val focus = Focus.of(big.find("Observation/o0")!!)
        val completer = FhirPathCompleter({ ModelNavigator(ctx, big.census) }, { big }, { COMMANDS }, { focus })
        val all = completer.values(":focus ")
        assertTrue(all.size < 100, "got ${all.size}")
        assertTrue(all.containsAll(listOf("code", "component", "Observation/o0")), "got $all")
    }

    @Test
    fun `a resource focus offers functions once something is typed`() {
        val completer = focusedOn("Patient/pat1")
        assertTrue(completer.values("whe").contains("where("))
    }

    @Test
    fun `all completes across the pool whatever is in focus`() {
        val completer = focusedOn("Observation/obs2", "code")
        assertTrue(completer.values(":all Pat").contains("Patient"))
        assertTrue(completer.values(":all Patient.").contains("name"))
    }

    @Test
    fun `help completes a command in the spelling typed, and manual its topics`() {
        val completer = FhirPathCompleter(
            { ModelNavigator(ctx, pool.census) }, { pool }, { COMMANDS },
            topics = { listOf(FhirPathCompleter.CommandInfo("git", "", "Pools from git revisions")) },
        )
        fun complete(line: String) = mutableListOf<Candidate>().also {
            completer.complete(reader, parser.parse(line, line.length, Parser.ParseContext.COMPLETE), it)
        }
        // JLine narrows by the word typed, so `lo` must be offered `load`, not `:load`.
        val bare = complete(":help lo")
        assertTrue("load" in bare.map { it.value() }, "${bare.map { it.value() }}")
        assertTrue(":load" in bare.map { it.displ() })
        assertTrue(":load" in complete(":help :lo").map { it.value() })
        assertEquals(listOf("git"), complete(":manual g").map { it.value() })
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
