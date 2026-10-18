package dev.fhirwalk.repl

import dev.fhirwalk.pool.GitSource
import dev.fhirwalk.pool.Importer
import dev.fhirwalk.pool.RevisionLoader
import dev.fhirwalk.pool.ResourcePool

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
    importer: Importer,
    private val pool: ResourcePool,
) {
    private val loader = RevisionLoader(source, importer, pool)

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

    /** `~/fp/registry/in/import@branch/out`, as the workspace saves it. */
    val spec: String get() = "${source.path}@$line"

    sealed interface Move {
        class Moved(val report: Importer.Report) : Move
        class Refused(val message: String) : Move
    }

    fun load(commit: GitSource.Commit): Importer.Report = loader.load(commit).also { applyScope() }

    fun at(revision: String): Move {
        val target = source.resolve(revision) ?: return Move.Refused("no commit $revision in ${source.path}")
        if (source.isBranch(revision)) line = revision
        return Move.Moved(load(target))
    }

    /**
     * Back follows first parents, which always works. Forward has to be found from
     * the line's tip, since a commit does not know its children; a commit that is
     * not on the line's first-parent history has no "next" there.
     */
    fun step(by: Int): Move {
        if (by < 0) {
            var target = commit
            repeat(-by) { taken ->
                val parent = target.parent ?: return Move.Refused(
                    if (taken == 0) "${commit.short} is the first commit" else "only $taken commit(s) before ${commit.short}",
                )
                target = source.commit(parent)
            }
            return Move.Moved(load(target))
        }
        val tip = source.resolve(line) ?: return Move.Refused("$line names no commit any more")
        val history = source.history(tip)
        val here = history.indexOfFirst { it.id == commit.id }
        return when {
            here < 0 -> Move.Refused("${commit.short} is not on $line's first-parent history — :at $line goes back to it")
            here == 0 -> Move.Refused("already at the tip of $line")
            here < by -> Move.Refused("only $here commit(s) after ${commit.short} on $line")
            else -> Move.Moved(load(history[here - by]))
        }
    }

    /** The line's commits, newest first. */
    fun history(limit: Int): List<GitSource.Commit> {
        val tip = source.resolve(line) ?: return emptyList()
        return source.history(tip, limit)
    }

    /** How many files [commit] changed, for listing many commits without loading them. */
    fun changedFiles(commit: GitSource.Commit, reads: (String) -> Boolean): Int = source.changes(commit, reads).size

    private fun applyScope() {
        pool.scope = if (deltaScope) loader.delta?.inPool else null
    }
}
