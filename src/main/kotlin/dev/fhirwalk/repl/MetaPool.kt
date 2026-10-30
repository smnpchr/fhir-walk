package dev.fhirwalk.repl

import dev.fhirwalk.pool.Importer
import dev.fhirwalk.pool.ResourcePool
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import kotlin.io.path.extension
import kotlin.io.path.readText

/**
 * What a meta pool is: the directory its bundles are under, and how old a bundle
 * may be to count. Saved, unlike what it holds, which is read only when asked for.
 *
 * @param dir absolute.
 * @param cutOff as typed: an age (`30d`, `12h`), kept relative so it moves on with
 *   the clock, or a date (`2026-10-01`); null for every bundle.
 */
data class MetaDefinition(val dir: String, val cutOff: String? = null)

/**
 * The meta pools' definitions, one `name<TAB>dir<TAB>cut-off` per line.
 *
 * Apart from the state file, since a definition is something made on purpose, like a
 * name or a view: it outlives a `:fhir` switch, and is never loaded at startup.
 *
 * @param file where definitions persist, or null to keep them for this session only.
 */
class MetaStore(file: Path?) {

    private val store = KeyedStore(
        file,
        encode = { definition: MetaDefinition -> listOf(definition.dir, definition.cutOff.orEmpty()) },
    ) { fields ->
        fields.firstOrNull()?.takeIf { it.isNotEmpty() }?.let { MetaDefinition(it, fields.getOrNull(1)?.takeIf(String::isNotEmpty)) }
    }

    val problem: String? get() = store.problem

    fun all(): Map<String, MetaDefinition> = store.all()

    fun get(name: String): MetaDefinition? = store.get(name)

    fun set(name: String, definition: MetaDefinition): MetaDefinition? = store.set(name, definition)

    fun remove(name: String): MetaDefinition? = store.remove(name)
}

/**
 * The bundles under a directory, in one pool: each bundle a part of it, so
 * `resolve()` stays inside the bundle while an expression runs over all of them.
 * What sets it apart from a loaded pool is that it is read from its definition,
 * again whenever asked, and never restored at startup.
 *
 * A member is a file that is one Bundle, modified no earlier than the cut-off;
 * anything else under the directory is skipped. What a file was found to be is
 * kept between scans while it is unchanged, so moving the cut-off parses only
 * what it lets in.
 */
class MetaPool(var definition: MetaDefinition) {

    /** A bundle in the pool, named by its path below the directory; see [names]. */
    data class Member(val name: String, val path: Path, val modified: Instant, val resources: Int)

    /**
     * What a [scan] found.
     *
     * @param missing the directory is not there, and the pool was left as it was.
     * @param unscoped the bundle the scope was on, when it is no longer a member.
     * @param unchanged asked to read only if the members changed, they had not, and
     *   the pool was left as it was; [problems] are then only those of files read afresh.
     * @param changes how the members differ from the read before.
     */
    data class Scan(
        val members: Int,
        val older: Int,
        val skipped: Int,
        val problems: List<String>,
        val missing: Boolean = false,
        val unscoped: String? = null,
        val unchanged: Boolean = false,
        val changes: Changes = Changes(),
    )

    /**
     * How the members of one scan differ from those of the one before: bundles new,
     * gone, modified in place, and named differently because of what arrived or went.
     */
    data class Changes(val added: Int = 0, val gone: Int = 0, val modified: Int = 0, val renamed: Int = 0)

    /** Most recently modified first, which is what `:members` and `:scope` want to the top. */
    var members: List<Member> = emptyList()
        private set

    /**
     * The file pool-wide evaluation is narrowed to, by `:scope <member>`. Kept by
     * path, not by name: a name depends on the other files there, so one that
     * arrives can give another bundle the name scoped to.
     */
    private var scopedPath: Path? = null

    /** The member pool-wide evaluation is narrowed to; null for all of them. */
    val scoped: String? get() = scopedPath?.let { path -> members.firstOrNull { it.path == path }?.name }

    /**
     * A file as a scan found it: a member's resources, or null for one that is no
     * Bundle or would not parse, with what went wrong.
     */
    private class Read(val modified: Instant, val resources: List<Importer.Parsed>?, val problems: List<String>)

    private val reads = HashMap<Path, Read>()

    val dir: Path get() = Paths.get(definition.dir)

    /**
     * The same definition and scope, with nothing read yet: what `:fhir` reads in
     * the new version, since resources parsed in the old one are of no use there.
     */
    fun unread(): MetaPool = MetaPool(definition).also { it.scopedPath = scopedPath }

    /**
     * Reads the directory again and fills [pool] with the members, from scratch: a
     * part cannot be taken out of a pool, and the census has to forget it too. A
     * directory that has gone leaves the pool as it was; one below it that cannot be
     * read is a problem, and the rest is read.
     *
     * @param since the cut-off as of now; null for every bundle.
     * @param ifChanged leave the pool as it is when the members are the same files,
     *   unmodified, as last time: what a look at them reads again for.
     */
    fun scan(importer: Importer, pool: ResourcePool, since: Instant?, ifChanged: Boolean = false): Scan {
        val root = dir
        if (!Files.isDirectory(root)) return Scan(members.size, 0, 0, emptyList(), missing = true)
        val problems = mutableListOf<String>()
        val files = mutableListOf<Path>()
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile && importer.reads(file.fileName.toString()) && file.extension.lowercase() !in STREAMS) files.add(file)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    problems += "${root.relativize(file)}: unreadable (${exc.message})"
                    return FileVisitResult.CONTINUE
                }
            },
        )
        val walked = problems.toList()
        files.sortBy { it.toString() }
        var older = 0
        var skipped = 0
        val inRange = HashMap<Path, Read>()
        val fresh = mutableListOf<String>()
        for (file in files) {
            val modified = runCatching { Files.getLastModifiedTime(file).toInstant() }.getOrNull() ?: continue
            if (since != null && modified < since) {
                older++
                continue
            }
            val read = reads[file]?.takeIf { it.modified == modified } ?: read(importer, root, file, modified).also { fresh += it.problems }
            inRange[file] = read
            problems += read.problems
            if (read.resources == null && read.problems.isEmpty()) skipped++
        }
        // Only what is in range is kept: what the cut-off drops is parsed again if it comes back.
        reads.clear()
        reads.putAll(inRange)

        val kept = files.filter { inRange[it]?.resources != null }
        val previous = members.associateBy { it.path }
        if (ifChanged && kept.size == previous.size && kept.all { previous[it]?.modified == inRange.getValue(it).modified }) {
            // A directory that cannot be read may hide a bundle, so it is said each time.
            return Scan(members.size, older, skipped, walked + fresh, unchanged = true)
        }
        val before = scoped
        pool.clear()
        val named = names(root, kept)
        members = kept.mapIndexed { idx, file ->
            val read = inRange.getValue(file)
            read.resources!!.forEach { pool.add(it.resource, it.fullUrl, it.origin, named[idx]) }
            Member(named[idx], file, read.modified, read.resources.size)
        }.sortedWith(compareByDescending<Member> { it.modified }.thenBy { it.path.toString() })
        pool.settleReferences()
        // Kept across a scan while its file is still a member.
        if (scopedPath != null && members.none { it.path == scopedPath }) scopedPath = null
        applyScope(pool)
        val stayed = members.mapNotNull { member -> previous[member.path]?.let { member to it } }
        val changes = Changes(
            added = members.size - stayed.size,
            gone = previous.size - stayed.size,
            modified = stayed.count { (now, was) -> now.modified != was.modified },
            renamed = stayed.count { (now, was) -> now.name != was.name },
        )
        return Scan(members.size, older, skipped, problems, unscoped = before?.takeIf { scopedPath == null }, changes = changes)
    }

    private fun read(importer: Importer, root: Path, file: Path, modified: Instant): Read {
        val relative = root.relativize(file).toString()
        val report = Importer.Report()
        val parsed = runCatching { importer.parse(relative, file.readText(), report) }.getOrElse {
            return Read(modified, null, listOf("$relative: unreadable (${it.message})"))
        }
        // A Bundle that would not parse, or parses only in another version, has problems and no resources.
        return Read(modified, parsed.takeIf { report.bundles > 0 }, report.problems.toList())
    }

    /** Narrows pool-wide evaluation to [member], or with null widens it to all of them. */
    fun scope(pool: ResourcePool, member: String?) {
        scopedPath = member?.let { name -> members.first { it.name == name }.path }
        applyScope(pool)
    }

    private fun applyScope(pool: ResourcePool) {
        pool.scope = scoped?.let { name -> pool.all().filterTo(HashSet()) { it.part == name } }
    }

    companion object {
        /** A file of one resource per line, which is never one Bundle. */
        private val STREAMS = setOf("ndjson", "jsonl")

        private val AGE = Regex("""(\d+)([dh])""")

        /**
         * When a cut-off typed as [text] begins as of [now]: `30d` or `12h` back from
         * now, or the start of a date (`2026-10`, `2026-10-01`). Null when it is
         * neither, or out of range.
         */
        fun since(text: String, times: CommitTimes, now: Instant = Instant.now()): Instant? =
            try {
                AGE.matchEntire(text)?.let { match ->
                    val (n, unit) = match.destructured
                    val count = n.toLongOrNull() ?: return null
                    now.minus(if (unit == "d") Duration.ofDays(count) else Duration.ofHours(count))
                } ?: times.start(text)
            } catch (e: DateTimeException) {
                null
            } catch (e: ArithmeticException) {
                null
            }

        /**
         * A name for each of [files] under [root], from its path: the segments
         * joined by `-`, less the extension and any trailing segment every path ends
         * in, such as the `bundle.json` of each `<pipe>/<run>/bundle.json`. What a
         * name cannot hold becomes `-`; one that would start other than with a letter
         * gets a `b`, and a name that comes twice, or is `all`, a number.
         */
        fun names(root: Path, files: List<Path>): List<String> {
            val parts = files.map { file ->
                val segments = root.relativize(file).map { it.toString() }
                segments.dropLast(1) + segments.last().substringBeforeLast('.')
            }
            var common = 0
            while (parts.size > 1 && parts.all { it.size > common + 1 } && parts.map { it[it.size - 1 - common] }.distinct().size == 1) common++
            val used = mutableSetOf<String>()
            return parts.map { segments ->
                val base = segments.dropLast(common).joinToString("-").replace(UNNAMEABLE, "-").ifEmpty { "bundle" }
                    .let { if (it.first() in 'a'..'z' || it.first() in 'A'..'Z') it else "b$it" }
                generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base-$it" }
                    .first { !it.equals(SCOPE_ALL, ignoreCase = true) && used.add(it) }
            }
        }

        private val UNNAMEABLE = Regex("[^A-Za-z0-9_-]")

        /** What `:scope` reads as every bundle, in any case; no bundle is given it. */
        private const val SCOPE_ALL = "all"
    }
}
