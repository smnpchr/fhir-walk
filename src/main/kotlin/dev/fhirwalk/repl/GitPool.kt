package dev.fhirwalk.repl

import dev.fhirwalk.eval.Evaluator
import dev.fhirwalk.pool.GitSource
import dev.fhirwalk.pool.Importer
import dev.fhirwalk.pool.RevisionLoader
import dev.fhirwalk.pool.ResourceHistory
import dev.fhirwalk.pool.ResourcePool
import org.eclipse.jgit.lib.ObjectId
import java.nio.file.Files
import java.time.DateTimeException

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

    /** The commit `:delta A..B` compares with, while one was asked for; null for the first parent. */
    val base: GitSource.Commit? get() = loader.delta?.takeIf { it.ranged }?.base

    /** The delta's base side, with an evaluator whose `resolve()` reads it. */
    class Before(val base: RevisionLoader.Base, val evaluator: Evaluator)

    private var before: Before? = null

    /**
     * The snapshot the delta compares with, read when first asked for and dropped
     * when the pool moves; see [RevisionLoader.base]. Throws when it cannot be read.
     */
    fun before(): Before = before ?: loader.base().let { Before(it, Evaluator(pool.ctx, it.pool)) }.also { before = it }

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

    /**
     * Whether the directory and the commit the pool is at are still there: a test
     * run may delete the repository, or rebuild it with another history, between
     * two lines. Asked of the repository opened afresh: the one held open remembers
     * objects it has seen, and answers for a commit that a rebuild took away. A
     * directory whose own `.git` has gone opens as an enclosing repository, which
     * does not count.
     */
    val readable: Boolean get() = Files.isDirectory(source.path) && runCatching {
        GitSource.open(source.path).use { it.gitDir == source.gitDir && it.has(commit.id) }
    }.getOrDefault(false)

    /** `~/fp/registry/in/import@branch/out`, as the workspace saves it. */
    val spec: String get() = "${source.path}@$line"

    sealed interface Move {
        /** @param note which commit a date named, since it need not be one made then. */
        class Moved(val report: Importer.Report, val note: String? = null) : Move
        class Refused(val message: String) : Move

        /** The repository could not be read; the pool is where it was. */
        class Failed(val message: String) : Move
    }

    /**
     * Throws when the revision cannot be read, leaving the pool as it was.
     *
     * @param base what the delta compares with; null for the first parent. A move
     *   is about one commit, so `:at` and `:step` go back to that.
     */
    fun load(commit: GitSource.Commit, base: GitSource.Commit? = null): Importer.Report =
        loader.load(commit, base).also {
            // The last base is a whole snapshot: not kept past the move that ends its use.
            before = null
            applyScope()
        }

    /** A revision looked up: the commit, with which one a date named, since it need not be one made then; or why not. */
    private sealed interface Lookup {
        class Found(val commit: GitSource.Commit, val note: String?) : Lookup
        class Missing(val refusal: Move.Refused) : Lookup
    }

    /**
     * A branch or tag goes first, so one named like a date stays reachable; then a
     * date, [on] as it stood then; then anything else git reads as a revision.
     */
    private fun find(revision: String, on: String = line): Lookup {
        fun missing(message: String) = Lookup.Missing(Move.Refused(message))
        if (revision !in source.refNames()) {
            val end = try {
                times.end(revision)
            } catch (e: DateTimeException) {
                return missing("not a date: $revision")
            }
            if (end != null) {
                val label = GitSource.label(on)
                val tip = source.resolve(on) ?: return missing("$label names no commit any more")
                val history = source.history(tip)
                val target = times.asOf(history, end)
                    ?: return missing("$label starts at ${times.shown(history.last())}, after $revision")
                return Lookup.Found(target, "$revision on $label is ${target.short}, ${times.shown(target)} ${target.subject}")
            }
        }
        val target = source.resolve(revision) ?: return missing("no commit $revision in ${source.path}")
        return Lookup.Found(target, null)
    }

    fun at(revision: String): Move = reading {
        val found = when (val lookup = find(revision)) {
            is Lookup.Found -> lookup
            is Lookup.Missing -> return@reading lookup.refusal
        }
        val moved = Move.Moved(load(found.commit), found.note)
        if (source.isBranch(revision)) line = revision
        moved
    }

    /**
     * `:delta from..to`: the pool at [to], its delta against [from] rather than the
     * first parent. Either end takes what `:at` takes; an empty one is the commit the
     * pool is at. Both empty go back to the first parent. A base that is not an
     * ancestor is still a tree diff, said in the note. A date as [from] is read on
     * the line the pool ends up on: `:delta 2026-11-02..feat` is feat since then.
     */
    fun range(from: String, to: String): Move = reading {
        if (from.isEmpty() && to.isEmpty()) return@reading Move.Moved(load(commit))
        fun end(revision: String) = if (revision.isEmpty()) Lookup.Found(commit, null) else find(revision)
        val target = when (val lookup = end(to)) {
            is Lookup.Found -> lookup
            is Lookup.Missing -> return@reading lookup.refusal
        }
        val toLine = to.takeIf { it.isNotEmpty() && source.isBranch(it) } ?: line
        val base = when (val lookup = if (from.isEmpty()) end(from) else find(from, toLine)) {
            is Lookup.Found -> lookup
            is Lookup.Missing -> return@reading lookup.refusal
        }
        val notes = listOfNotNull(
            base.note,
            target.note,
            "${base.commit.short} is not an ancestor of ${target.commit.short}: compared as two trees"
                .takeUnless { source.isAncestor(base.commit.id, target.commit.id) },
        )
        val moved = Move.Moved(load(target.commit, base.commit), notes.joinToString("\n").ifEmpty { null })
        if (to.isNotEmpty() && source.isBranch(to)) line = to
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
