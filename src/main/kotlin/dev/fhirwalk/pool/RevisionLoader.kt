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
     *
     * @param base what it is compared with: the first parent, or null for the first
     *   commit, unless [ranged].
     * @param ranged whether [base] was asked for (`:delta A..B`) rather than the parent.
     */
    class Delta(
        val base: GitSource.Commit?,
        val ranged: Boolean,
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

    /** What [commit] changed against [Delta.base], file by file, for reading the base side. */
    private var fileChanges: List<GitSource.Change> = emptyList()

    /** The base side, once asked for; dropped on every load. */
    private var baseSide: Base? = null

    /**
     * The snapshot the delta compares with, as a pool of its own: what `resolve()`
     * reads when an expression is answered as it stood before the delta.
     *
     * @param changed its resources from the files the delta changed or deleted: the
     *   resources the delta touched, as they were.
     * @param problems the base's files that could not be read or parsed.
     */
    class Base(val pool: ResourcePool, val changed: List<PooledResource>, val problems: List<String>)

    /**
     * Reads [Delta.base] on first asking and keeps it until the next load. A file the
     * delta did not change has the same blob on both sides, so its resources are the
     * very ones the pool holds; only what the delta changed or deleted is parsed
     * again, and it is not kept with the revision's blobs. No census is walked: the
     * base is evaluated, never completed against. Empty for a first commit, which is
     * compared with nothing.
     *
     * Throws when the base cannot be read, leaving nothing kept.
     */
    fun base(): Base {
        baseSide?.let { return it }
        checkNotNull(commit) { "not loaded yet" }
        val against = delta?.base
        val snapshot = ResourcePool(pool.ctx, censused = false)
        val changed = mutableListOf<PooledResource>()
        val problems = mutableListOf<String>()
        if (against != null) {
            val touched = fileChanges.filter { it.kind != GitSource.Kind.ADDED }.mapTo(HashSet()) { it.path }
            // Everything read before anything is added, as a load does.
            val files = source.files(against, importer::reads).map { it to (current[it.blob] ?: previous[it.blob] ?: parse(it.path, it.blob)) }
            for ((file, blob) in files) {
                problems += blob.problems
                val added = blob.parsed.map { snapshot.add(it.resource, it.fullUrl, it.origin) }
                if (file.path in touched) changed += added
            }
        }
        return Base(snapshot, changed, problems).also { baseSide = it }
    }

    /**
     * Everything that reads the repository happens before the pool is touched, so a
     * revision that cannot be read throws with the pool, [commit] and [delta] still
     * describing the last one.
     *
     * @param base the commit the delta compares with; null for the first parent.
     */
    fun load(commit: GitSource.Commit, base: GitSource.Commit? = null): Importer.Report {
        val next = HashMap<ObjectId, Blob>()
        fun blobOf(path: String, id: ObjectId): Blob =
            next.getOrPut(id) { current[id] ?: previous[id] ?: parse(path, id) }
        val files = source.files(commit, importer::reads).map { it to blobOf(it.path, it.blob) }
        val changes = if (base == null) source.changes(commit, importer::reads) else source.changes(commit, importer::reads, base.id)
        // Kept with this revision's: the base holds them, which after a step is the last revision.
        val deletedBlobs = changes.filter { it.kind == GitSource.Kind.DELETED }
            .map { change -> change to change.oldBlob?.let { blobOf(change.path, it) } }
        val against = base ?: commit.parent?.let(source::commit)

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
            against,
            ranged = base != null,
            added = changes.filter { it.kind == GitSource.Kind.ADDED }.flatMap { byFile[it.path].orEmpty() },
            modified = changes.filter { it.kind == GitSource.Kind.MODIFIED }.flatMap { byFile[it.path].orEmpty() },
            deleted = deletedBlobs.flatMap { (change, blob) ->
                blob?.parsed?.map { it.label(pool.ctx) }?.ifEmpty { null } ?: listOf(change.path)
            },
        )
        previous = current
        current = next
        fileChanges = changes
        baseSide = null
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
