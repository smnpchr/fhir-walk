package dev.fhirwalk

import dev.fhirwalk.model.FhirVersion
import dev.fhirwalk.pool.GitSource
import dev.fhirwalk.pool.Importer
import dev.fhirwalk.pool.ResourceHistory
import dev.fhirwalk.pool.ResourcePool
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.revwalk.RevCommit
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A resource's history where it shares a file with others, which the one-file-per-
 * resource registry fixture cannot show.
 */
class ResourceHistoryTest {

    private val root = Files.createTempDirectory("history")
    private val git = Git.init().setDirectory(root.toFile()).setInitialBranch("main").call()

    private fun patient(id: String, family: String) = """{"resourceType":"Patient","id":"$id","name":[{"family":"$family"}]}"""

    private fun commit(message: String, files: Map<String, String?>): RevCommit {
        files.forEach { (path, text) ->
            val file = root.resolve(path)
            if (text == null) Files.delete(file) else Files.writeString(file, text)
        }
        git.add().addFilepattern(".").call()
        git.add().addFilepattern(".").setUpdate(true).call()
        return git.commit().setMessage(message).setAuthor("t", "t@example.org").setCommitter("t", "t@example.org").call()
    }

    private val added = commit("both", mapOf("all.ndjson" to patient("a", "Alpha") + "\n" + patient("b", "Beta") + "\n"))
    private val neighbour = commit("b only", mapOf("all.ndjson" to patient("a", "Alpha") + "\n" + patient("b", "Bravo") + "\n"))
    private val changed = commit("a", mapOf("all.ndjson" to patient("a", "Able") + "\n" + patient("b", "Bravo") + "\n"))
    private val moved = commit("a moves", mapOf("all.ndjson" to patient("b", "Bravo") + "\n", "a.json" to patient("a", "Able")))
    private val gone = commit("a goes", mapOf("a.json" to null))

    private val ctx = FhirVersion.R4.create()
    private val source = GitSource.open(root)
    private val importer = Importer(ctx, ResourcePool(ctx))
    private val history = ResourceHistory(source, importer, ctx) { source.changes(it, importer::reads) }
    private val line = source.history(source.resolve("main")!!)

    @Test
    fun `a change to the file it shares is not a change to the resource`() {
        val changes = history.of("Patient/a", line).toList()
        assertEquals(listOf(gone, changed, added).map { it.name }, changes.map { it.commit.id.name })
        assertEquals(listOf(GitSource.Kind.DELETED, GitSource.Kind.MODIFIED, GitSource.Kind.ADDED), changes.map { it.kind })
        // Moving to a file of its own, unchanged, is no change either.
        assertFalse(changes.any { it.commit.id == moved.id })
        assertTrue(changes.any { it.commit.id == neighbour.id }.not())
    }

    @Test
    fun `the neighbour has its own history`() {
        assertEquals(listOf(neighbour, added).map { it.name }, history.of("Patient/b", line).map { it.commit.id.name }.toList())
    }

    @Test
    fun `a page stops reading once it is full`() {
        var asked = 0
        val counting = ResourceHistory(source, importer, ctx) { asked++; source.changes(it, importer::reads) }
        assertEquals(gone.name, counting.of("Patient/a", line).first().commit.id.name)
        assertEquals(1, asked)
    }

    @Test
    fun `only a label that holds across revisions can be followed`() {
        assertTrue(ResourceHistory.isLabel("Specimen/30012"))
        assertTrue(ResourceHistory.isLabel("urn:uuid:7f1c"))
        assertFalse(ResourceHistory.isLabel("type.coding.code"))
        assertFalse(ResourceHistory.isLabel("Patient[#3]"))
        assertFalse(ResourceHistory.isLabel("value/2"))
    }
}
