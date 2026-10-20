package dev.fhirwalk

import dev.fhirwalk.model.FhirVersion
import dev.fhirwalk.pool.GitSource
import dev.fhirwalk.repl.GitPool
import dev.fhirwalk.repl.Output
import dev.fhirwalk.repl.Repl
import dev.fhirwalk.repl.Session
import dev.fhirwalk.repl.Workspaces
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A pool that is a git revision of [RegistryFixture], stepped through line by line. */
class GitPoolTest {

    private val registry = RegistryFixture()
    private val home = Files.createTempDirectory("fhir-walk-home")

    private inner class Run(workspace: String = "w1", version: FhirVersion? = null) {
        val repl = Repl(version, emptyList(), Workspaces(home), workspace)
        val out: String = capture { repl.open(it) }

        fun run(line: String): String = capture { repl.dispatch(line, it) }
    }

    private fun capture(block: (PrintWriter) -> Unit): String {
        val text = StringWriter()
        PrintWriter(text).use(block)
        return text.toString()
    }

    private fun assertContains(output: String, expected: String) =
        assertTrue(output.contains(expected), "expected \"$expected\" in:\n$output")

    /** Opened at the tip of branch/out, as `spec`. */
    private fun opened(): Run = Run().apply { run(":open spec ${registry.spec}") }

    @Test
    fun `a git pool opens at the tip, reading only FHIR files under its directory`() {
        val output = Run().run(":open spec ${registry.spec}")
        assertContains(output, "pool spec at ${registry.out4.short} on branch/out")
        assertContains(output, "24 resource(s) from 24 file(s)")
    }

    @Test
    fun `log lists the line's first parents with what each changed`() {
        val log = opened().run(":log").lines()
        assertContains(log[0], "branch/out in ")
        assertContains(log[1], "* ${registry.out4.short}")
        assertContains(log[1], "Δ1")
        assertContains(log[2], "Δ3")
        assertContains(log[3], "Δ13")
        assertContains(log[4], "Δ11")
        assertContains(log[5], registry.m1.short)
        assertContains(log[5], "Δ12")
        assertEquals(6, log.count { it.isNotBlank() }, log.joinToString("\n"))
    }

    @Test
    fun `stepping back scopes to the delta, and resolve still sees everything`() {
        val session = opened()
        assertContains(session.run(":step"), "at ${registry.out3.short} on branch/out")
        // The fixed rule re-applied: only the citrate plasma changed.
        assertContains(session.run(":delta"), "Δ3: 3 modified")
        val types = session.run("Specimen.type.coding.code")
        assertContains(types, "3 of 3 resource(s) matched")
        assertFalse(types.contains("EDTA"), types)
        // Their masters did not change in out3, but the changed aliquots still reach them.
        val container = "Specimen.parent.resolve().parent.resolve().extension('${RegistryFixture.EXT}/primary-container').value"
        assertContains(session.run(container), "2 of 3 resource(s) matched")
        assertContains(session.run("Specimen.subject.resolve().name.family"), "Muster")
        session.run(":name-expr family")
        assertContains(session.run(":view family"), "3 Specimen row(s)")
    }

    @Test
    fun `scope all goes back to the whole revision`() {
        val session = opened()
        session.run(":step")
        assertContains(session.run(":scope"), "the delta, 3 of 25 resource(s)")
        assertContains(session.run(":scope all"), "all 25 resource(s)")
        assertContains(session.run("Specimen.type.coding.code"), "22 of 25 resource(s) matched")
        assertContains(session.run(":scope delta"), "the delta, 3 resource(s)")
    }

    @Test
    fun `a deletion is listed by label`() {
        val session = opened()
        val delta = session.run(":delta")
        assertContains(delta, "against ${registry.out3.short}")
        assertContains(delta, "- Patient/9999")
    }

    @Test
    fun `stepping stops at either end`() {
        val session = opened()
        assertContains(session.run(":step +1"), "already at the tip of branch/out")
        assertContains(session.run(":step -4"), "at ${registry.m1.short}")
        assertContains(session.run(":delta"), "against nothing: the first commit — Δ12: 12 added")
        assertContains(session.run(":step -1"), "is the first commit")
        assertContains(session.run(":step +2"), "at ${registry.out2.short}")
        assertContains(session.run(":step +5"), "only 2 commit(s) after")
        assertContains(session.run(":step 2"), "usage: :step")
    }

    @Test
    fun `at a branch makes it the line`() {
        val session = opened()
        assertContains(session.run(":at master"), "at ${registry.m2.short} on master")
        assertContains(session.run(":delta"), "Δ13: 13 added")
        val log = session.run(":log")
        assertContains(log, "master in ")
        assertFalse(log.contains(registry.out1.short), log)
        // An id moves without changing the line.
        assertContains(session.run(":at ${registry.out1.name}"), "on master")
        assertContains(session.run(":step +1"), "not on master's first-parent history")
        assertContains(session.run(":at nope"), "no commit nope")
    }

    @Test
    fun `the focus is found again after a step`() {
        val session = opened()
        session.run(":focus Specimen/30012")
        assertContains(session.run("type.coding.code"), "CIT")
        // Back before the fixed rule, the same aliquot was mapped to EDTA plasma.
        val stepped = session.run(":step -2")
        assertContains(stepped, "focus Specimen/30012")
        assertContains(session.run("type.coding.code"), "EDTA")
    }

    @Test
    fun `nothing is loaded into a git pool, and a plain pool has no revision`() {
        val session = opened()
        assertContains(session.run(":load samples/basic"), "is ${registry.import.toRealPath()}@branch/out")
        session.run(":open plain")
        assertContains(session.run(":step"), "not a git pool")
    }

    @Test
    fun `a directory outside any repository is refused and leaves no pool`() {
        val session = Run()
        val outside = Files.createTempDirectory("not-a-repo")
        assertContains(session.run(":open x $outside@master"), "not inside a git repository")
        assertContains(session.run(":pools"), "* pool")
        assertFalse(session.run(":pools").contains("x "))
    }

    @Test
    fun `a git pool comes back at the revision it was left at, with its scope`() {
        opened().run(":step -2")
        val state = Files.readAllLines(home.resolve("workspaces/w1/state"))
        assertTrue("source\t${registry.import.toRealPath()}@branch/out" in state, state.toString())
        assertTrue("at\t${registry.out2.name}" in state, state.toString())
        assertTrue("scope\tdelta" in state, state.toString())
        val next = Run()
        assertContains(next.out, "pool spec at ${registry.out2.short} on branch/out")
        assertContains(next.run(":scope"), "the delta")
    }

    @Test
    fun `a source that has gone is reported and stays saved`() {
        Files.createDirectories(home.resolve("workspaces/w1"))
        val lines = listOf("fhir\tr4", "pool\tpool", "pool\tspec", "source\t/no/such/dir@branch/out", "at\tabc")
        Files.write(home.resolve("workspaces/w1/state"), lines)
        val session = Run()
        assertContains(session.out, "not a directory: /no/such/dir")
        assertContains(session.out, "kept in the workspace")
        session.run(":limit 3")
        val state = Files.readAllLines(home.resolve("workspaces/w1/state"))
        assertTrue("source\t/no/such/dir@branch/out" in state, state.toString())
        assertContains(session.run(":pools"), "unavailable: /no/such/dir@branch/out")
    }

    @Test
    fun `switching version reads a git pool again at the same revision`() {
        val session = opened()
        session.run(":step")
        assertContains(session.run(":fhir r5"), "pool spec at ${registry.out3.short} on branch/out")
        assertContains(session.run(":scope"), "the delta")
    }

    @Test
    fun `a step parses only blobs the last revision did not hold`() {
        val session = Session(FhirVersion.R4)
        val git = GitPool(GitSource.open(registry.import), "branch/out", session.importer, session.pool)
        git.load(git.source.resolve("branch/out")!!)
        // The 24 files at the tip, and the deleted patient, to label it.
        assertEquals(25, git.parsedBlobs)
        git.step(-1)
        assertEquals(25, git.parsedBlobs)
        // out2 has the three citrate samples before the rule was fixed.
        git.step(-1)
        assertEquals(28, git.parsedBlobs)
        git.step(+1)
        assertEquals(28, git.parsedBlobs)
        git.source.close()
    }

    /** Deletes the loose tree object for [dir] at [commit], as a damaged repository would lack it. */
    private fun breakTree(commit: org.eclipse.jgit.revwalk.RevCommit, dir: String) {
        org.eclipse.jgit.storage.file.FileRepositoryBuilder().setGitDir(registry.root.resolve(".git").toFile()).build().use { repo ->
            val id = org.eclipse.jgit.treewalk.TreeWalk.forPath(repo, dir, commit.tree).use { it.getObjectId(0) }
            val name = id.name
            Files.delete(registry.root.resolve(".git/objects/${name.take(2)}/${name.drop(2)}"))
        }
    }

    @Test
    fun `a revision that cannot be read leaves no half-open pool`() {
        breakTree(registry.out4, "import/Specimen")
        val session = Run()
        assertContains(session.run(":open spec ${registry.spec}"), "!")
        // Nothing half-open remains to break the next command.
        val pools = session.run(":pools")
        assertContains(pools, "* pool")
        assertFalse(pools.contains("spec"), pools)
    }

    @Test
    fun `a step into a revision that cannot be read stays where it was`() {
        breakTree(registry.out2, "import/Specimen")
        val session = opened()
        assertContains(session.run(":step -2"), "!")
        assertContains(session.run(":pool"), "at ${registry.out4.short} on branch/out: 24 resource(s)")
        assertContains(session.run(":delta"), "- Patient/9999")
    }

    @Test
    fun `a git pool that cannot be read again in another version stays saved`() {
        val session = opened()
        registry.root.toFile().deleteRecursively()
        session.run(":fhir r5")
        assertContains(session.run(":pools"), "unavailable: ")
        val state = Files.readAllLines(home.resolve("workspaces/w1/state"))
        assertTrue(state.any { it.startsWith("source\t") }, state.toString())
    }

    @Test
    fun `a workspace left at a revision that cannot be read still starts`() {
        opened().run(":step -2")
        breakTree(registry.out2, "import/Specimen")
        val next = Run()
        assertContains(next.out, "cannot read")
        assertContains(next.out, "kept in the workspace")
        assertContains(next.run(":pools"), "unavailable: ")
        // A change elsewhere rewrites the state, and the pool's place in it survives.
        next.run(":limit 3")
        val state = Files.readAllLines(home.resolve("workspaces/w1/state"))
        assertTrue("at\t${registry.out2.name}" in state, state.toString())
        assertTrue("scope\tdelta" in state, state.toString())
    }

    @Test
    fun `at a revision that cannot be read keeps the line and the focus`() {
        val session = opened()
        session.run(":focus Specimen/30012")
        breakTree(registry.m2, "import/Specimen")
        assertContains(session.run(":at master"), "! cannot read")
        assertContains(session.run(":pool"), "at ${registry.out4.short} on branch/out")
        assertContains(session.run("type.coding.code"), "CITRATE_PLASMA")
        assertContains(session.run(":log 1"), "branch/out in ")
    }

    @Test
    fun `log marks commits whose changes cannot be read`() {
        val session = opened()
        breakTree(registry.out2, "import/Specimen")
        val log = session.run(":log")
        // out2's own diff and out3's, which compares against out2, both need the tree.
        assertEquals(2, log.lines().count { "Δ?" in it && "cannot be read" !in it }, log)
        assertContains(log, "what 2 commit(s) changed cannot be read")
        assertContains(log, "* ${registry.out4.short}")
    }

    @Test
    fun `a trailing at sign opens the branch HEAD is on, and saves that branch`() {
        // The fixture is left with branch/out checked out.
        val session = Run()
        assertContains(session.run(":open spec ${registry.import}@"), "pool spec at ${registry.out4.short} on branch/out")
        assertContains(session.run(":step +1"), "already at the tip of branch/out")
        val state = Files.readAllLines(home.resolve("workspaces/w1/state"))
        assertTrue("source\t${registry.import.toRealPath()}@branch/out" in state, state.toString())
    }

    @Test
    fun `a directory inside a repository loads from disk and points at its history`() {
        val inside = Run().run(":open plain ${registry.import}")
        assertContains(inside, "loaded 24 resource(s)")
        assertContains(inside, "in a git repository, on branch/out — :open <name> ${registry.import}@ opens its history")
        val outside = Files.createTempDirectory("not-a-repo")
        Files.writeString(outside.resolve("p.json"), """{"resourceType":"Patient","id":"p"}""")
        assertFalse(Run().run(":open plain $outside").contains("git repository"))
    }

    @Test
    fun `revisions complete after the at sign`() {
        val terminal = org.jline.terminal.TerminalBuilder.builder().dumb(true).build()
        val reader = org.jline.reader.LineReaderBuilder.builder().terminal(terminal).build()
        val session = Session(FhirVersion.R4)
        val completer = dev.fhirwalk.complete.FhirPathCompleter(
            { session.navigator }, { session.pool }, { emptyList() },
            revisionsAt = { GitSource.refNamesAt(java.nio.file.Paths.get(it)) },
        )
        fun values(line: String) = mutableListOf<org.jline.reader.Candidate>().also {
            completer.complete(reader, dev.fhirwalk.repl.FhirPathParser().parse(line, line.length, org.jline.reader.Parser.ParseContext.COMPLETE), it)
        }.map { it.value() }
        assertEquals(setOf("${registry.import}@branch/out", "${registry.import}@master"), values(":open spec ${registry.import}@").toSet())
        assertEquals(listOf("${registry.import}@master"), values(":open spec ${registry.import}@ma"))
    }

    private fun completer(session: Session) = dev.fhirwalk.complete.FhirPathCompleter(
        { session.navigator }, { session.pool }, { emptyList() },
        revisionsAt = { GitSource.refNamesAt(java.nio.file.Paths.get(it)) },
        gitSpec = { token -> GitSource.revisionSpec(token) { Files.exists(java.nio.file.Paths.get(it)) } },
    )

    private fun offered(completer: dev.fhirwalk.complete.FhirPathCompleter, line: String): List<String> {
        val terminal = org.jline.terminal.TerminalBuilder.builder().dumb(true).build()
        val reader = org.jline.reader.LineReaderBuilder.builder().terminal(terminal).build()
        return mutableListOf<org.jline.reader.Candidate>().also {
            completer.complete(reader, dev.fhirwalk.repl.FhirPathParser().parse(line, line.length, org.jline.reader.Parser.ParseContext.COMPLETE), it)
        }.map { it.value() }
    }

    @Test
    fun `a path with an at sign in it still completes as a path`() {
        val dir = Files.createTempDirectory("paths").resolve("user@host")
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("p.json"), "{}")
        val completer = completer(Session(FhirVersion.R4))
        // Existing as written: a path.
        assertTrue(offered(completer, ":open x $dir/").any { it.endsWith("p.json") })
        // Half typed, and no repository's revision either: still a path.
        assertTrue(offered(completer, ":open x ${dir.parent}/user@ho").any { it.endsWith("user@host/") })
    }

    @Test
    fun `no hint for a directory no commit holds`() {
        val untracked = registry.root.resolve("untracked")
        Files.createDirectories(untracked)
        Files.writeString(untracked.resolve("p.json"), """{"resourceType":"Patient","id":"p"}""")
        val output = Run().run(":open u $untracked")
        assertContains(output, "loaded 1 resource(s)")
        assertFalse(output.contains("git repository"), output)
    }

    @Test
    fun `a repository with no commits has no history to open`() {
        val root = Files.createTempDirectory("empty-repo")
        org.eclipse.jgit.api.Git.init().setDirectory(root.toFile()).call().close()
        val dir = Files.createDirectories(root.resolve("import"))
        Files.writeString(dir.resolve("p.json"), """{"resourceType":"Patient","id":"p"}""")
        val session = Run()
        assertContains(session.run(":open p $dir@"), "is in a repository with no commits yet")
        assertFalse(session.run(":open q $dir").contains("git repository"))
    }

    @Test
    fun `a detached HEAD pins the pool and says so`() {
        org.eclipse.jgit.api.Git.open(registry.root.toFile()).use { it.checkout().setName(registry.out2.name).call() }
        val session = Run()
        val opened = session.run(":open spec ${registry.import}@")
        assertContains(opened, "HEAD is detached — pinned at ${registry.out2.short}; :at <branch> to follow one")
        assertContains(opened, "at ${registry.out2.short} on ${registry.out2.short},")
        assertFalse(opened.contains(registry.out2.name), opened)
        assertContains(session.run(":step +1"), "already at the tip of ${registry.out2.short}")
        assertContains(Run().run(":open plain ${registry.import}"), "in a git repository, HEAD detached at ${registry.out2.short}")
    }

    @Test
    fun `at offers the line's commits by date, newest first, the current one marked`() {
        val session = opened()
        val choices = session.repl.commitChoices()
        assertEquals(
            listOf(registry.out4, registry.out3, registry.out2, registry.out1, registry.m1).map { it.short },
            choices.map { it.id },
        )
        assertEquals(minute(registry.out4), choices.first().at)
        assertTrue(choices.first().shown.startsWith("* ${minute(registry.out4)}  "), choices.first().shown)
        assertTrue(choices.first().shown.endsWith(registry.out4.short), choices.first().shown)
        assertEquals("remove dummy patient", choices.first().subject)
        // The mark follows the pool.
        session.run(":step -2")
        assertEquals(listOf(registry.out2.short), session.repl.commitChoices().filter { it.shown.startsWith("*") }.map { it.id })
        // No git pool, no commits.
        session.run(":open plain")
        assertTrue(session.repl.commitChoices().isEmpty())
    }

    @Test
    fun `at lists commits ahead of branches, and an id prefix narrows them`() {
        val session = opened()
        val pool = Session(FhirVersion.R4)
        val completer = dev.fhirwalk.complete.FhirPathCompleter(
            { pool.navigator }, { pool.pool }, { emptyList() },
            revisions = { listOf("branch/out", "master") },
            commits = { session.repl.commitChoices() },
        )
        val terminal = org.jline.terminal.TerminalBuilder.builder().dumb(true).build()
        val reader = org.jline.reader.LineReaderBuilder.builder().terminal(terminal).build()
        fun offered(line: String) = mutableListOf<org.jline.reader.Candidate>().also {
            completer.complete(reader, dev.fhirwalk.repl.FhirPathParser().parse(line, line.length, org.jline.reader.Parser.ParseContext.COMPLETE), it)
        }
        val all = offered(":at ")
        val order = dev.fhirwalk.repl.FhirWalkLineReader(terminal).groupComparator
        assertEquals(listOf("commits", "branches and tags"), all.map { it.group() }.distinct().sortedWith(order))
        assertEquals(listOf(registry.out2.short), offered(":at ${registry.out2.name.take(5)}").map { it.value() })
        // With nothing typed, or a date begun, a commit goes in as its time.
        assertEquals(minute(registry.out4), all.filter { it.group() == "commits" }.sorted().first().value())
        val day = minute(registry.out2).take(10)
        assertTrue(offered(":at $day").all { it.value().startsWith(day) })
        assertEquals(listOf(minute(registry.out2)), offered(":at ${minute(registry.out2)}").map { it.value() })
        // What each changed is beside its subject, as :log shows it.
        assertEquals("Δ1  remove dummy patient", all.single { it.value() == minute(registry.out4) }.descr())
    }

    // ------------------------------------------------------------- the model

    @Test
    fun `log says what each commit is, apart from the window it is printed in`() {
        val session = opened()
        val listing = session.repl.outputOf(":log").listed()
        val first = listing.rows.first().spans
        assertEquals(registry.out4.short, first.single { it.kind == Output.Kind.INDEX }.text)
        assertEquals("    Δ1", first.single { it.kind == Output.Kind.COUNT }.text)
        // The subject is what gives way in a narrow window; the model keeps it whole.
        assertEquals("remove dummy patient", first.single { it.shrinks }.text)
        assertEquals(5, listing.rows.size)
    }

    @Test
    fun `delta rows name each resource as a label`() {
        val session = opened()
        session.run(":step -1")
        val rows = session.repl.outputOf(":delta").listed().rows
        assertTrue(rows.isNotEmpty())
        rows.forEach { row -> assertTrue(row.spans.any { it.kind == Output.Kind.LABEL && it.text.contains('/') }, row.text) }
        // :more prints the same listing whole.
        assertEquals(rows, ((session.repl.outputOf(":more").blocks.single() as Output.Whole).listing as Output.Listing).rows)
    }

    // ------------------------------------------------------- one resource's history

    /** Each row's short id, and the word or answer after its time. */
    /** The listing [this] ends with, which for these commands is never a table. */
    private fun Output.listed(): Output.Listing = listing as Output.Listing

    private fun rowsOf(output: Output) = output.listed().rows.map { row ->
        row.spans.single { it.kind == Output.Kind.INDEX }.text to row.spans.drop(3).joinToString("") { it.text }.trim()
    }

    @Test
    fun `log of a label lists only the commits that changed that resource`() {
        val session = opened()
        assertEquals(
            listOf(registry.out3.short to "modified  sync", registry.out1.short to "modified  sync", registry.m1.short to "added     import"),
            rowsOf(session.repl.outputOf(":log Specimen/30012")),
        )
        // out3 rewrote every sample, but an EDTA one came out the same: git saw no change.
        assertEquals(listOf(registry.out1.short, registry.m1.short), rowsOf(session.repl.outputOf(":log Specimen/30003")).map { it.first })
        assertEquals(
            listOf(registry.out4.short to "deleted   remove dummy patient", registry.out2.short to "added     sync"),
            rowsOf(session.repl.outputOf(":log Patient/9999")),
        )
        assertContains(session.run(":log Specimen/99999"), "no commit on branch/out added, changed or deleted Specimen/99999")
    }

    @Test
    fun `log of a label pages like the line`() {
        val session = opened()
        val page = session.run(":log Specimen/30012 1")
        assertContains(page, registry.out3.short)
        assertContains(page, "older: :log Specimen/30012 1 +1")
        assertContains(session.run(":log Specimen/30012 +3"), "only 3 commit(s) on branch/out touched Specimen/30012")
    }

    @Test
    fun `trace shows how an answer changed, and what it was before`() {
        val session = opened()
        val output = session.repl.outputOf(":trace Specimen/30012 type.coding.code")
        assertEquals(
            listOf(
                registry.out3.short to "CITRATE_PLASMA  (was EDTA_PLASMA)",
                registry.out1.short to "EDTA_PLASMA  (was PLASMA)",
                registry.m1.short to "PLASMA  (added)",
            ),
            rowsOf(output),
        )
        assertContains(output.lines().first(), "3 change(s) in 3 commit(s) that touched it")
        // Read from the history: the pool stays where it was.
        assertContains(session.run(":log 1"), "* ${registry.out4.short}")
    }

    @Test
    fun `trace leaves out commits that changed the resource but not the answer`() {
        val session = opened()
        val output = session.repl.outputOf(":trace Specimen/30012 subject.reference")
        assertEquals(listOf(registry.m1.short to "Patient/2001  (added)"), rowsOf(output))
        assertContains(output.lines().first(), "1 change(s) in 3 commit(s)")
    }

    @Test
    fun `trace says when the resource went`() {
        assertEquals(
            listOf(registry.out4.short to "deleted  (was Dummy)", registry.out2.short to "Dummy  (added)"),
            rowsOf(opened().repl.outputOf(":trace Patient/9999 name.family")),
        )
    }

    @Test
    fun `with a focus, trace follows the focused resource or element`() {
        val session = opened()
        val expected = rowsOf(session.repl.outputOf(":trace Specimen/30012 type.coding.code"))
        session.run(":focus Specimen/30012")
        assertEquals(expected, rowsOf(session.repl.outputOf(":trace type.coding.code")))
        session.run(":focus type")
        assertEquals(expected, rowsOf(session.repl.outputOf(":trace coding.code")))
    }

    @Test
    fun `an element's trace runs the whole expression on the element`() {
        val session = opened()
        session.run(":focus Specimen/30012")
        session.run(":focus type")
        // A union: both sides on the element, not the right one on the Specimen.
        val newest = rowsOf(session.repl.outputOf(":trace coding.code | coding.system")).first().second
        assertTrue("CITRATE_PLASMA" in newest && "central-sample-type" in newest, newest)
    }

    @Test
    fun `an expression with a URL in it is not taken for a label`() {
        val session = opened()
        session.run(":focus Specimen/30012")
        val rows = rowsOf(session.repl.outputOf(":trace extension('${RegistryFixture.EXT}/sample-category').value"))
        assertEquals(listOf(registry.m1.short to "DERIVED  (added)"), rows)
    }

    @Test
    fun `trace with no expression follows the whole resource`() {
        val session = opened()
        val output = session.repl.outputOf(":trace Specimen/30012")
        val rows = rowsOf(output)
        assertEquals(listOf(registry.out3, registry.out1, registry.m1).map { it.short }, rows.map { it.first })
        assertTrue(rows.first().second.startsWith("modified  {"), rows.first().second)
        assertContains(rows.first().second, "CITRATE_PLASMA")
        assertTrue(rows.last().second.startsWith("added     {"), rows.last().second)
        assertContains(output.lines().first(), "Specimen/30012 on branch/out, newest first — 3 change(s) in 3 commit(s)")
        // With a focus, :trace alone traces the focused resource.
        session.run(":focus Specimen/30012")
        assertEquals(rows, rowsOf(session.repl.outputOf(":trace")))
    }

    @Test
    fun `with an element in focus, trace alone follows the element's content`() {
        val session = opened()
        session.run(":focus Specimen/30012")
        session.run(":focus type")
        // An expression on the focus's steps: a row where the element changed, saying what it was.
        val output = session.repl.outputOf(":trace")
        assertContains(output.lines().first(), "Specimen/30012  type on branch/out")
        assertEquals(listOf(registry.out3, registry.out1, registry.m1).map { it.short }, rowsOf(output).map { it.first })
        assertTrue(rowsOf(output).first().second.contains("(was "), rowsOf(output).first().second)
    }

    @Test
    fun `more prints a trace's answers whole, a resource as pretty-printed JSON`() {
        val session = opened()
        session.repl.outputOf(":trace Specimen/30012 Specimen")
        val whole = session.repl.outputOf(":more").lines()
        // Under the header, one commit line, then the resource under it, a field to a line.
        assertContains(whole[1], registry.out3.short)
        assertContains(whole[1], "modified")
        assertTrue(whole.any { it.trim() == "\"resourceType\": \"Specimen\"," }, whole.joinToString("\n"))
        assertTrue(whole.none { it.contains("\"resourceType\":\"Specimen\"") }, "compact JSON left in:\n${whole.joinToString("\n")}")
        // A primitive stays on one line under its commit.
        session.repl.outputOf(":trace Specimen/30012 type.coding.code")
        val codes = session.repl.outputOf(":more").lines()
        assertTrue(codes.any { it.trim() == "code  CITRATE_PLASMA" }, codes.joinToString("\n"))
    }

    @Test
    fun `trace refuses what it cannot answer`() {
        val session = opened()
        assertContains(session.run(":trace type.coding.code"), "usage: :trace <label> [expr]")
        assertContains(session.run(":trace"), "usage: :trace <label> [expr]")
        assertContains(session.run(":trace Specimen/30012 subject.resolve().name"), "resolve() would read the pool at ${registry.out4.short}")
        assertContains(session.run(":trace Specimen/30012 subject.resolve ().name"), "resolve() would read the pool")
        assertContains(session.run(":trace Specimen/30012 type.where("), "! ")
        assertContains(Run("plain").run(":trace Specimen/30012 type"), "is not a git pool")
    }

    // ------------------------------------------------------------- :at a date

    /** [commit]'s time to the minute, as `:at` takes it, in the zone `:log` shows. */
    private fun minute(commit: org.eclipse.jgit.revwalk.RevCommit, plusMinutes: Long = 0): String =
        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm").withZone(java.time.ZoneId.systemDefault())
            .format(java.time.Instant.ofEpochSecond(commit.commitTime.toLong()).plusSeconds(plusMinutes * 60))

    @Test
    fun `at a date is the line as it stood then, saying which commit that is`() {
        val session = opened()
        // Half an hour after out2, before out3: the line still stood at out2.
        val output = session.run(":at ${minute(registry.out2, plusMinutes = 30)}")
        assertContains(output, "on branch/out is ${registry.out2.short}")
        assertContains(output, "spec at ${registry.out2.short}")
        // The space :log shows works as well as the T.
        assertContains(session.run(":at ${minute(registry.out3).replace('T', ' ')}"), "spec at ${registry.out3.short}")
        // A date leaves the line as it was, so :step still walks towards its tip.
        assertContains(session.run(":step +1"), "spec at ${registry.out4.short}")
    }

    @Test
    fun `at a date before the line or no date at all is refused and stays put`() {
        val session = opened()
        val before = java.time.LocalDate.parse(minute(registry.m1).take(10)).minusDays(2).toString()
        assertContains(session.run(":at $before"), "branch/out starts at ")
        assertContains(session.run(":at 2026-13"), "not a date: 2026-13")
        assertContains(session.run(":log"), "* ${registry.out4.short}")
    }

    // ---------------------------------------------------------------- :log pages

    @Test
    fun `log pages back through the line`() {
        val session = opened()
        val first = session.run(":log 2").lines().filter { it.isNotBlank() }
        assertEquals(4, first.size, first.joinToString("\n"))
        assertContains(first[1], registry.out4.short)
        assertContains(first[3], "older: :log 2 +2")
        val last = session.run(":log 2 +4")
        assertContains(last, registry.m1.short)
        assertFalse(last.contains("older"), last)
        assertContains(last, "(at ${registry.out4.short}, which is not among these)")
        assertContains(session.run(":log +9"), "only 5 commit(s) on branch/out")
        assertContains(session.run(":log x"), "x is not a label a history can follow")
        assertContains(session.run(":log 0"), "usage: :log [label] [n] [+skip]")
        assertContains(session.run(":log 2 +2 +4"), "usage: :log [label] [n] [+skip]")
        // Past what an Int holds once a page is added: the whole line, nothing older.
        val all = session.run(":log ${Int.MAX_VALUE}")
        assertContains(all, registry.m1.short)
        assertContains(session.run(":log +${Int.MAX_VALUE}"), "only 5 commit(s) on branch/out")
    }
}
