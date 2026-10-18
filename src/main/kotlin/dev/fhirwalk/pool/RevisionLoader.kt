package dev.fhirwalk.pool

import org.eclipse.jgit.lib.ObjectId

/**
 * Fills a pool with a [GitSource] at one revision at a time.
 *
 * A file whose content did not change has the same blob id in every revision, so
 * parsed resources are kept by blob id: moving to a neighbouring revision parses
 * only the files that differ. What is kept is this revision's blobs and the last
 * one's, which bounds it at about one snapshot plus a delta however long the walk.
 * Nothing mutates a parsed resource, so revisions can share them; the pool's indexes
 * and its census are rebuilt for each revision.
 */
class RevisionLoader(
    private val source: GitSource,
    private val importer: Importer,
    private val pool: ResourcePool,
) {

    /** What a file parsed to, and what went wrong parsing it, reported each time it is loaded. */
    private class Blob(val parsed: List<Importer.Parsed>, val problems: List<String>)

    /**
     * What a commit changed, as resources: the added and modified ones are in the
     * pool, the deleted ones only by label, since this revision no longer holds them.
     */
    class Delta(
        val parent: GitSource.Commit?,
        val added: List<PooledResource>,
        val modified: List<PooledResource>,
        val deleted: List<String>,
    ) {
        val size: Int get() = added.size + modified.size + deleted.size

        /** What pool-wide evaluation runs over when the scope is the delta. */
        val inPool: Set<PooledResource> get() = (added + modified).toSet()
    }

    var commit: GitSource.Commit? = null
        private set

    var delta: Delta? = null
        private set

    /** Blobs parsed so far, as opposed to taken from what was kept. */
    var parsedBlobs = 0
        private set

    private var current = HashMap<ObjectId, Blob>()
    private var previous = HashMap<ObjectId, Blob>()

    fun load(commit: GitSource.Commit): Importer.Report {
        val report = Importer.Report()
        val byFile = HashMap<String, List<PooledResource>>()
        val next = HashMap<ObjectId, Blob>()
        pool.clear()
        for (file in source.files(commit, importer::reads)) {
            report.files++
            val blob = next[file.blob] ?: current[file.blob] ?: previous[file.blob] ?: parse(file.path, file.blob)
            next[file.blob] = blob
            report.problems += blob.problems
            byFile[file.path] = blob.parsed.map { pool.add(it.resource, it.fullUrl, it.origin) }
        }
        report.resources = pool.size
        pool.settleReferences()
        val changes = source.changes(commit, importer::reads)
        delta = Delta(
            commit.parent?.let(source::commit),
            added = changes.filter { it.kind == GitSource.Kind.ADDED }.flatMap { byFile[it.path].orEmpty() },
            modified = changes.filter { it.kind == GitSource.Kind.MODIFIED }.flatMap { byFile[it.path].orEmpty() },
            deleted = changes.filter { it.kind == GitSource.Kind.DELETED }.flatMap { change ->
                val old = change.oldBlob ?: return@flatMap listOf(change.path)
                // Kept with this revision's: the parent, one step back, holds it.
                val blob = next[old] ?: current[old] ?: previous[old] ?: parse(change.path, old)
                next[old] = blob
                blob.parsed.map { labelOf(it) }.ifEmpty { listOf(change.path) }
            },
        )
        previous = current
        current = next
        this.commit = commit
        return report
    }

    private fun parse(path: String, id: ObjectId): Blob {
        parsedBlobs++
        val report = Importer.Report()
        val parsed = runCatching { importer.parse(path, source.read(id), report) }.getOrElse {
            report.problems += "$path: unreadable (${it.message})"
            emptyList()
        }
        return Blob(parsed, report.problems)
    }

    /** As [PooledResource.label] would have it, for a resource that is in no pool. */
    private fun labelOf(parsed: Importer.Parsed): String {
        val type = pool.ctx.getResourceType(parsed.resource)
        val id = parsed.resource.idElement?.idPart?.takeIf { it.isNotBlank() }
        return id?.let { "$type/$it" } ?: parsed.fullUrl ?: "$type (${parsed.origin})"
    }
}
