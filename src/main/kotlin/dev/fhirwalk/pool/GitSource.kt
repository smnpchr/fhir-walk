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
    /** The repository's `.git`, which tells it from one enclosing it. */
    val gitDir: Path,
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

    /** Whether the repository still holds [id]: not once it is deleted, or replaced by one with another history. */
    fun has(id: ObjectId): Boolean = runCatching { repository.objectDatabase.has(id) }.getOrDefault(false)

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

    /**
     * What `dir@` with nothing after the `@` follows: the branch HEAD is on, or HEAD's
     * full commit id when it is detached. Null in a repository with no commits yet,
     * whose HEAD names a branch that does not exist.
     */
    fun headLine(): String? {
        val head = runCatching { repository.resolve(Constants.HEAD) }.getOrNull() ?: return null
        val full = runCatching { repository.fullBranch }.getOrNull()
        return if (full != null && full.startsWith(Constants.R_HEADS)) full.removePrefix(Constants.R_HEADS) else head.name
    }

    /** Whether HEAD's tree holds this directory: whether it has a history to open at all. */
    fun hasHistory(): Boolean {
        val head = runCatching { repository.resolve("${Constants.HEAD}^{tree}") }.getOrNull() ?: return false
        if (subpath.isEmpty()) return true
        return runCatching { TreeWalk.forPath(repository, subpath, head)?.use { true } ?: false }.getOrDefault(false)
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
         * A trailing `@` gives an empty revision: whatever branch HEAD is on.
         */
        fun split(spec: String): Pair<String, String>? {
            val at = (spec.length - 1 downTo 1).firstOrNull { spec[it] == '@' && spec.getOrNull(it + 1) != '{' }
                ?: return null
            return spec.substring(0, at) to spec.substring(at + 1)
        }

        /**
         * For the hint that a directory has a history to open: where HEAD is in the
         * repository [directory] is inside -- its branch, or `HEAD detached at 3f2a1c0`
         * -- or null when there is no history of this directory to open: outside any
         * repository, before the first commit, or a directory no commit holds
         * (untracked or ignored).
         */
        fun historyAt(directory: Path): String? = runCatching {
            open(directory).use { source ->
                val line = source.headLine()?.takeIf { source.hasHistory() } ?: return@use null
                if (source.isBranch(line)) line else "HEAD detached at ${line.take(SHORT)}"
            }
        }.getOrNull()

        /**
         * A token as `:open` and its completion both read it: a path that exists as
         * written is a path, even with an `@` in it; otherwise [split].
         */
        fun revisionSpec(token: String, exists: (String) -> Boolean): Pair<String, String>? =
            if (runCatching { exists(token) }.getOrDefault(false)) null else split(token)

        /** A full commit id as the short one, anything else (a branch, a tag) as it is. */
        fun label(revision: String): String =
            if (revision.length == 40 && revision.all { it in '0'..'9' || it in 'a'..'f' }) revision.take(SHORT) else revision

        /** The branches and tags of the repository [directory] is inside, for completing `dir@`. */
        fun refNamesAt(directory: Path): List<String> =
            runCatching { open(directory).use { it.refNames() } }.getOrDefault(emptyList())

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
            return GitSource(repository, subpath, real, repository.directory.toPath().toAbsolutePath().normalize())
        }
    }
}
