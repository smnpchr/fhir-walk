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

    /**
     * Everything that reads the repository happens before the pool is touched, so a
     * revision that cannot be read throws with the pool, [commit] and [delta] still
     * describing the last one.
     */
    fun load(commit: GitSource.Commit): Importer.Report {
        val next = HashMap<ObjectId, Blob>()
        fun blobOf(path: String, id: ObjectId): Blob =
            next.getOrPut(id) { current[id] ?: previous[id] ?: parse(path, id) }
        val files = source.files(commit, importer::reads).map { it to blobOf(it.path, it.blob) }
        val changes = source.changes(commit, importer::reads)
        // Kept with this revision's: the parent, one step back, holds them.
        val deletedBlobs = changes.filter { it.kind == GitSource.Kind.DELETED }
            .map { change -> change to change.oldBlob?.let { blobOf(change.path, it) } }
        val parent = commit.parent?.let(source::commit)

        val report = Importer.Report()
        val byFile = HashMap<String, List<PooledResource>>()
        pool.clear()
        for ((file, blob) in files) {
            report.files++
            report.problems += blob.problems
            byFile[file.path] = blob.parsed.map { pool.add(it.resource, it.fullUrl, it.origin) }
        }
        report.resources = pool.size
        pool.settleReferences()
        delta = Delta(
            parent,
            added = changes.filter { it.kind == GitSource.Kind.ADDED }.flatMap { byFile[it.path].orEmpty() },
            modified = changes.filter { it.kind == GitSource.Kind.MODIFIED }.flatMap { byFile[it.path].orEmpty() },
            deleted = deletedBlobs.flatMap { (change, blob) ->
                blob?.parsed?.map { it.label(pool.ctx) }?.ifEmpty { null } ?: listOf(change.path)
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

}
