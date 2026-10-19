package dev.fhirwalk.repl

import dev.fhirwalk.pool.GitSource
import dev.fhirwalk.pool.Importer
import dev.fhirwalk.pool.RevisionLoader
import dev.fhirwalk.pool.ResourceHistory
import dev.fhirwalk.pool.ResourcePool
import org.eclipse.jgit.lib.ObjectId
import java.time.DateTimeException
import java.time.Instant

/**
 * What makes a [Pool] a git revision rather than a set of loaded paths: where it is
 * in the history, the branch it steps along, and whether pool-wide evaluation runs
 * over the whole snapshot or only over what the commit changed.
 *
 * @param line the branch `:step +n` walks towards the tip of. `:at` a branch makes
 *   that branch the line; `:at` anything else leaves it.
 */
class GitPool(
    val source: GitSource,
    var line: String,
    private val importer: Importer,
    private val pool: ResourcePool,
    private val times: CommitTimes = CommitTimes(),
) {
    private val loader = RevisionLoader(source, importer, pool)

    /** How many files each commit changed, by id: a commit never changes, and listings ask often. */
    private val counted = HashMap<ObjectId, Int>()

    /**
     * What recent commits changed, file by file, for walking a resource's history
     * again. Bounded by files rather than commits: a sync that rewrites the whole
     * registry is one commit of thousands.
     */
    private val changed = object : LinkedHashMap<ObjectId, List<GitSource.Change>>(16, 0.75f, true) {
        var files = 0

        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ObjectId, List<GitSource.Change>>): Boolean {
            if (files <= MAX_KEPT_CHANGES) return false
            files -= eldest.value.size
            return true
        }
    }

    val commit: GitSource.Commit get() = checkNotNull(loader.commit) { "not loaded yet" }

    val delta: RevisionLoader.Delta get() = checkNotNull(loader.delta) { "not loaded yet" }

    /** For tests: how many blobs were parsed rather than reused. */
    internal val parsedBlobs: Int get() = loader.parsedBlobs

    /** True while pool-wide evaluation runs over the delta only. */
    var deltaScope: Boolean = false
        set(value) {
            field = value
            applyScope()
        }

    /** [line] for showing: a branch as it is, a pinned commit by its short id. */
    val lineLabel: String get() = GitSource.label(line)

    /** `~/fp/registry/in/import@branch/out`, as the workspace saves it. */
    val spec: String get() = "${source.path}@$line"

    sealed interface Move {
        /** @param note which commit a date named, since it need not be one made then. */
        class Moved(val report: Importer.Report, val note: String? = null) : Move
        class Refused(val message: String) : Move

        /** The repository could not be read; the pool is where it was. */
        class Failed(val message: String) : Move
    }

    /** Throws when the revision cannot be read, leaving the pool as it was. */
    fun load(commit: GitSource.Commit): Importer.Report = loader.load(commit).also { applyScope() }

    /**
     * A branch or tag goes first, so one named like a date stays reachable; then a
     * date, the line as it stood then; then anything else git reads as a revision.
     */
    fun at(revision: String): Move = reading {
        if (revision !in source.refNames()) {
            val end = try {
                times.end(revision)
            } catch (e: DateTimeException) {
                return@reading Move.Refused("not a date: $revision")
            }
            if (end != null) return@reading asOf(revision, end)
        }
        val target = source.resolve(revision) ?: return@reading Move.Refused("no commit $revision in ${source.path}")
        val moved = Move.Moved(load(target))
        if (source.isBranch(revision)) line = revision
        moved
    }

    /**
     * Back follows first parents, which always works. Forward has to be found from
     * the line's tip, since a commit does not know its children; a commit that is
     * not on the line's first-parent history has no "next" there.
     */
    fun step(by: Int): Move = reading {
        if (by < 0) {
            var target = commit
            repeat(-by) { taken ->
                val parent = target.parent ?: return@reading Move.Refused(
                    if (taken == 0) "${commit.short} is the first commit" else "only $taken commit(s) before ${commit.short}",
                )
                target = source.commit(parent)
            }
            return@reading Move.Moved(load(target))
        }
        val tip = source.resolve(line) ?: return@reading Move.Refused("$lineLabel names no commit any more")
        val history = source.history(tip)
        val here = history.indexOfFirst { it.id == commit.id }
        when {
            here < 0 -> Move.Refused("${commit.short} is not on $lineLabel's first-parent history — :at $lineLabel goes back to it")
            here == 0 -> Move.Refused("already at the tip of $lineLabel")
            here < by -> Move.Refused("only $here commit(s) after ${commit.short} on $lineLabel")
            else -> Move.Moved(load(history[here - by]))
        }
    }

    private fun asOf(date: String, end: Instant): Move {
        val tip = source.resolve(line) ?: return Move.Refused("$lineLabel names no commit any more")
        val history = source.history(tip)
        val target = times.asOf(history, end)
            ?: return Move.Refused("$lineLabel starts at ${times.shown(history.last())}, after $date")
        return Move.Moved(load(target), note = "$date on $lineLabel is ${target.short}, ${times.shown(target)} ${target.subject}")
    }

    /** The line's commits, newest first. */
    fun history(limit: Int): List<GitSource.Commit> {
        val tip = source.resolve(line) ?: return emptyList()
        return source.history(tip, limit)
    }

    /**
     * How many files [commit] changed, for listing many commits without loading them.
     * Throws when they cannot be read; nothing is kept then, so it is asked again.
     */
    fun changedFiles(commit: GitSource.Commit): Int = counted.getOrPut(commit.id) { changes(commit).size }

    private fun changes(commit: GitSource.Commit): List<GitSource.Change> =
        changed[commit.id] ?: source.changes(commit, importer::reads).also {
            changed.files += it.size
            changed[commit.id] = it
            counted[commit.id] = it.size
        }

    /**
     * The commits of the line that added, changed or deleted [label], newest first,
     * read as the sequence is: see [ResourceHistory]. Throws as any read of the line
     * does; a file that cannot be read or parsed goes to [problem] instead.
     */
    fun history(label: String, problem: (String) -> Unit = {}): Sequence<ResourceHistory.Change> {
        val tip = source.resolve(line) ?: return emptySequence()
        return ResourceHistory(source, importer, pool.ctx, ::changes, problem).of(label, source.history(tip))
    }

    private companion object {
        /** About a few MB of paths and ids. */
        const val MAX_KEPT_CHANGES = 50_000
    }

    /**
     * A damaged or vanished repository surfaces as whatever JGit throws, from any
     * read. Caught here, at the edge of a move, so it is reported like any refusal
     * rather than escaping the command.
     */
    private inline fun reading(move: () -> Move): Move = try {
        move()
    } catch (e: Exception) {
        Move.Failed("cannot read ${source.path}: ${e.message ?: e.javaClass.simpleName}")
    }

    private fun applyScope() {
        pool.scope = if (deltaScope) loader.delta?.inPool else null
    }
}
