package dev.fhirwalk.pool

import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.treewalk.EmptyTreeIterator
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.treewalk.filter.PathFilter
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class GitSourceException(message: String) : Exception(message)

/**
 * A directory inside a local git repository, read at any revision straight from the
 * object database: nothing is checked out, and the working tree is never touched.
 *
 * Read-only by construction. Nothing here fetches, pushes or commits; keeping a
 * registry up to date is the job of whatever writes it, and of your own `git`.
 *
 * Paths handed out are relative to the directory, so `Specimen/s1.json` rather than
 * `import/Specimen/s1.json`.
 *
 * @param subpath where the directory sits in the repository; empty for its root.
 */
class GitSource private constructor(
    private val repository: Repository,
    val subpath: String,
    /** The directory, absolute: how the source is named back to the user. */
    val path: Path,
) : AutoCloseable {

    data class Commit(val id: ObjectId, val time: Instant, val subject: String, val parent: ObjectId?) {
        val short: String get() = id.name.take(SHORT)
    }

    data class File(val path: String, val blob: ObjectId)

    enum class Kind { ADDED, MODIFIED, DELETED }

    data class Change(val kind: Kind, val path: String, val oldBlob: ObjectId?, val newBlob: ObjectId?)

    /** A revision as git spells it: a branch, a tag, an id, `branch/out~2`. Null when it names no commit. */
    fun resolve(revision: String): Commit? {
        val id = runCatching { repository.resolve("$revision^{commit}") }.getOrNull() ?: return null
        return commit(id)
    }

    fun commit(id: ObjectId): Commit = RevWalk(repository).use { walk -> commitOf(walk.parseCommit(id)) }

    /**
     * Plain ids, not the walk's [RevCommit]s: those keep their message and every
     * parent they reached, which would keep the walked history alive for as long as
     * the pool sits at the commit.
     */
    private fun commitOf(commit: RevCommit) =
        Commit(commit.copy(), Instant.ofEpochSecond(commit.commitTime.toLong()), commit.shortMessage, commit.parents.firstOrNull()?.copy())

    /** Whether [revision] is a branch, which `:step` can walk towards the tip of. */
    fun isBranch(revision: String): Boolean {
        val ref = runCatching { repository.findRef(revision) }.getOrNull() ?: return false
        return ref.name.startsWith(Constants.R_HEADS) || ref.name.startsWith(Constants.R_REMOTES)
    }

    /** Local branches, then tags, as they would be typed after `:at`. */
    fun refNames(): List<String> = runCatching {
        val refs = repository.refDatabase
        refs.getRefsByPrefix(Constants.R_HEADS).map { it.name.removePrefix(Constants.R_HEADS) } +
            refs.getRefsByPrefix(Constants.R_TAGS).map { it.name.removePrefix(Constants.R_TAGS) }
    }.getOrDefault(emptyList())

    /** The files under the directory at [commit] whose name passes [wanted], sorted by path. */
    fun files(commit: Commit, wanted: (String) -> Boolean): List<File> = RevWalk(repository).use { walk ->
        TreeWalk(repository).use { tree ->
            tree.addTree(walk.parseCommit(commit.id).tree)
            tree.isRecursive = true
            if (subpath.isNotEmpty()) tree.filter = PathFilter.create(subpath)
            buildList {
                while (tree.next()) {
                    val relative = relative(tree.pathString) ?: continue
                    if (wanted(relative)) add(File(relative, tree.getObjectId(0)))
                }
            }.sortedBy { it.path }
        }
    }

    fun read(blob: ObjectId): String = String(repository.open(blob, Constants.OBJ_BLOB).bytes, Charsets.UTF_8)

    /**
     * What [commit] changed under the directory against its first parent, or against
     * nothing for the first commit. Renames are not detected: a moved file is a
     * deletion and an addition, which is what it is to a pool keyed by content.
     */
    fun changes(commit: Commit, wanted: (String) -> Boolean): List<Change> = RevWalk(repository).use { walk ->
        TreeWalk(repository).use { tree ->
            val parent = commit.parent
            if (parent == null) tree.addTree(EmptyTreeIterator()) else tree.addTree(walk.parseCommit(parent).tree)
            tree.addTree(walk.parseCommit(commit.id).tree)
            tree.isRecursive = true
            if (subpath.isNotEmpty()) tree.filter = PathFilter.create(subpath)
            DiffEntry.scan(tree).mapNotNull { entry ->
                val kind = when (entry.changeType) {
                    DiffEntry.ChangeType.ADD -> Kind.ADDED
                    DiffEntry.ChangeType.DELETE -> Kind.DELETED
                    DiffEntry.ChangeType.MODIFY -> Kind.MODIFIED
                    else -> return@mapNotNull null
                }
                val path = relative(if (kind == Kind.DELETED) entry.oldPath else entry.newPath) ?: return@mapNotNull null
                if (!wanted(path)) return@mapNotNull null
                Change(
                    kind,
                    path,
                    entry.oldId.toObjectId().takeIf { kind != Kind.ADDED },
                    entry.newId.toObjectId().takeIf { kind != Kind.DELETED },
                )
            }.sortedBy { it.path }
        }
    }

    /** [from] and its first parents, newest first: the commits a branch's own runs made. */
    fun history(from: Commit, limit: Int = Int.MAX_VALUE): List<Commit> = RevWalk(repository).use { walk ->
        buildList {
            var current: RevCommit? = walk.parseCommit(from.id)
            while (current != null && size < limit) {
                add(commitOf(current))
                current = current.parents.firstOrNull()?.let { walk.parseCommit(it) }
            }
        }
    }

    private fun relative(path: String): String? = when {
        subpath.isEmpty() -> path
        path.startsWith("$subpath/") -> path.removePrefix("$subpath/")
        else -> null
    }

    override fun close() = repository.close()

    companion object {
        private const val SHORT = 7

        /**
         * `~/fp/registry/in/import@branch/out` as its path and revision, or null when
         * it names no revision. The last `@` splits, so a path may contain one -- except
         * an `@{` git itself writes in a revision: `branch/out@{1}`, `HEAD@{yesterday}`.
         */
        fun split(spec: String): Pair<String, String>? {
            val at = (spec.length - 2 downTo 1).firstOrNull { spec[it] == '@' && spec[it + 1] != '{' } ?: return null
            return spec.substring(0, at) to spec.substring(at + 1)
        }

        /** The repository is found by walking up from [directory], as `git` itself does. */
        fun open(directory: Path): GitSource {
            if (!Files.isDirectory(directory)) throw GitSourceException("not a directory: $directory")
            val real = directory.toRealPath()
            val builder = FileRepositoryBuilder().findGitDir(real.toFile())
            if (builder.gitDir == null) throw GitSourceException("not inside a git repository: $directory")
            val repository = builder.setMustExist(true).build()
            if (repository.isBare) {
                repository.close()
                throw GitSourceException("a bare repository has no directory to point at: $directory")
            }
            val subpath = repository.workTree.toPath().toRealPath().relativize(real).toString().replace('\\', '/')
            return GitSource(repository, subpath, real)
        }
    }
}
