package dev.fhirwalk.repl

import dev.fhirwalk.pool.Importer
import dev.fhirwalk.pool.ResourcePool
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.streams.asSequence

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
 * anything else under the directory is skipped. A member's resources are kept
 * between scans while its file is unchanged, so moving the cut-off parses only
 * what it lets in.
 */
class MetaPool(var definition: MetaDefinition) {

    /** A bundle in the pool, named by its path below the directory; see [names]. */
    data class Member(val name: String, val path: Path, val modified: Instant, val resources: Int)

    /** What a [scan] found. */
    data class Scan(
        val members: Int,
        val older: Int,
        val skipped: Int,
        val problems: List<String>,
    )

    var members: List<Member> = emptyList()
        private set

    /** The member pool-wide evaluation is narrowed to, by `:scope <member>`; null for all of them. */
    var scoped: String? = null
        private set

    private val parsed = HashMap<Path, Pair<Instant, List<Importer.Parsed>>>()

    val dir: Path get() = Paths.get(definition.dir)

    /**
     * Reads the directory again and fills [pool] with the members, from scratch: a
     * part cannot be taken out of a pool, and the census has to forget it too.
     *
     * @param since the cut-off as of now; null for every bundle.
     */
    fun scan(importer: Importer, pool: ResourcePool, since: Instant?): Scan {
        val root = dir
        val files = Files.walk(root).use { stream ->
            stream.asSequence()
                .filter { it.isRegularFile() && importer.reads(it.fileName.toString()) && it.extension.lowercase() !in STREAMS }
                .sortedBy { it.toString() }
                .toList()
        }
        val problems = mutableListOf<String>()
        var older = 0
        var skipped = 0
        val kept = mutableListOf<Triple<Path, Instant, List<Importer.Parsed>>>()
        for (file in files) {
            val modified = runCatching { Files.getLastModifiedTime(file).toInstant() }.getOrNull() ?: continue
            if (since != null && modified < since) {
                older++
                continue
            }
            val relative = root.relativize(file).toString()
            val cached = parsed[file]?.takeIf { it.first == modified }?.second
            val resources = cached ?: run {
                val report = Importer.Report()
                val read = runCatching { importer.parse(relative, file.readText(), report) }.getOrElse {
                    problems += "$relative: unreadable (${it.message})"
                    return@run null
                }
                when {
                    report.bundles > 0 -> read.also { problems += report.problems }
                    // A Bundle that would not parse, or parses only in another version.
                    report.problems.isNotEmpty() -> null.also { problems += report.problems }
                    else -> null.also { skipped++ }
                }
            } ?: continue
            kept += Triple(file, modified, resources)
        }
        // Only the members are kept: what the cut-off drops is parsed again if it comes back.
        parsed.clear()
        kept.forEach { (file, modified, resources) -> parsed[file] = modified to resources }

        pool.clear()
        val named = names(root, kept.map { it.first })
        members = kept.mapIndexed { idx, (file, modified, resources) ->
            val name = named[idx]
            resources.forEach { pool.add(it.resource, it.fullUrl, it.origin, name) }
            Member(name, file, modified, resources.size)
        }
        pool.settleReferences()
        // Kept across a scan while its member is still there.
        scope(pool, scoped?.takeIf { name -> members.any { it.name == name } })
        return Scan(members.size, older, skipped, problems)
    }

    /** Narrows pool-wide evaluation to [member], or with null widens it to all of them. */
    fun scope(pool: ResourcePool, member: String?) {
        scoped = member
        pool.scope = member?.let { name -> pool.all().filterTo(HashSet()) { it.part == name } }
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
         * gets a `b`, and a name that comes twice a number.
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
                generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base-$it" }.first { used.add(it) }
            }
        }

        private val UNNAMEABLE = Regex("[^A-Za-z0-9_-]")
    }
}
