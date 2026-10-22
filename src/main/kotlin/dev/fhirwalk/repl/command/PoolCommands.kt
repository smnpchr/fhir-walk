package dev.fhirwalk.repl.command

import dev.fhirwalk.model.FhirVersion
import dev.fhirwalk.model.FhirVersionUnavailable
import dev.fhirwalk.pool.Importer
import dev.fhirwalk.repl.KeyedStore
import dev.fhirwalk.repl.Output
import dev.fhirwalk.repl.Pool
import dev.fhirwalk.repl.SavedPool
import dev.fhirwalk.repl.Session
import dev.fhirwalk.repl.abbreviate
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.streams.asSequence

/** Loading into pools, opening and switching them, and the FHIR version they are all read in. */
internal class PoolCommands(private val state: ReplState, private val git: GitCommands) : CommandGroup {

    private val session: Session get() = state.session

    override val commands = listOf(
        command(":load") { argument, out -> if (argument.isBlank()) out.text("usage: :load <path>") else load(argument, out) },
        command(":pool") { argument, out -> if (argument.isBlank()) poolSummary(out) else switchPool(argument, out) },
        command(":pools") { _, out -> listPools(out) },
        command(":open", run = ::openPool),
        command(":close", run = ::closePool),
        command(":open-bundles", run = ::openBundles),
        command(":prune", run = ::prune),
        command(":fhir", run = ::switchVersion),
    )

    fun load(path: String, out: Output) {
        session.current.git?.let {
            out.text("${state.poolName()} is ${it.source.path}@${it.lineLabel} — :at <rev> moves it; :open another pool to load files")
            return
        }
        val report = session.importer.load(Paths.get(state.expandHome(path)))
        // Absolute, so the next session finds it from wherever it is started.
        val absolute = Paths.get(state.expandHome(path)).toAbsolutePath().normalize().toString()
        val loaded = session.current.loadedPaths
        if (report.files > 0 && absolute !in loaded) loaded += absolute
        state.withProblems(out, "loaded ${report.summary()}; ${state.poolName()} now ${session.pool.size}", report.problems)
    }

    private fun poolSummary(out: Output) {
        if (session.pool.size == 0) {
            out.text("pool is empty")
            return
        }
        val census = session.pool.census
        val git = session.current.git
        val which = listOfNotNull(
            "pool ${session.current.name}".takeIf { session.pools.size > 1 },
            git?.let { "at ${it.commit.short} on ${it.lineLabel}" },
        ).joinToString(" ").let { if (it.isEmpty()) "" else "$it: " }
        val scoped = if (git?.deltaScope == true) "; scope: the delta's ${session.pool.inScope().size}" else ""
        out.listing(
            listOf(Output.text("$which${session.pool.size} resource(s), FHIR ${session.version.id.uppercase()}$scoped")),
            session.pool.typeCounts().map { (type, count) ->
                Output.line {
                    +"  "
                    type(type.padEnd(ReplState.LABEL_WIDTH))
                    +" "
                    count(count.toString())
                }
            } +
                Output.text(
                    "census: ${census.extensions.size} extension url/path pair(s), " +
                        "${census.pathCounts.size} populated path(s)",
                ),
        )
    }

    private fun listPools(out: Output) {
        val width = session.pools.maxOf { it.name.length }
        out.listing(
            header = emptyList(),
            rows = session.pools.map { pool ->
                Output.line {
                    +(if (pool === session.current) "* " else "  ")
                    label(pool.name.padEnd(width))
                    +"  "
                    count(pool.pool.size.toString().padStart(6))
                    +"  "
                    shrink(sourceOf(pool))
                    pool.focus?.let { hint("  focus ${abbreviate(it.label, ReplState.PROMPT_WIDTH)}") }
                }
            },
            full = session.pools.flatMap { pool ->
                val paths = if (pool.git != null || pool.unavailable != null) listOf(sourceOf(pool)) else pool.loadedPaths
                listOf(
                    Output.line {
                        +(if (pool === session.current) "* " else "  ")
                        label(pool.name)
                        +"  "
                        count(pool.pool.size.toString())
                        +" resource(s)"
                    },
                ) + paths.map { Output.text("      $it") } + listOfNotNull(pool.focus?.let { Output.text("      focus ${it.label}") })
            },
        )
    }

    /** Rows from another pool cannot be picked here, so a switch forgets them. */
    private fun switchPool(name: String, out: Output) {
        when {
            session.pool(name) == null ->
                out.text("no pool $name — :pools lists them, :open $name <path> adds one")
            name == session.current.name -> out.text("already in pool $name")
            else -> {
                session.switchTo(name)
                state.lastPicks = null
                val focus = session.focus?.let { ", focus ${it.label}" }.orEmpty()
                out.text("pool $name: ${session.pool.size} resource(s)$focus")
            }
        }
    }

    /** `:open specimen` adds an empty pool; `:open specimen <path>` loads into it too. */
    private fun openPool(argument: String, out: Output) {
        val name = argument.substringBefore(' ').trim()
        val path = argument.substringAfter(' ', "").trim()
        when {
            name.isEmpty() -> out.text("usage: :open <name> [path]")
            !KeyedStore.valid(name) -> out.text("not a valid pool name: $name — ${KeyedStore.RULE}")
            session.pool(name) != null -> out.text("pool $name is already open — :pool $name switches to it")
            else -> {
                val before = session.current.name
                session.open(name)
                session.switchTo(name)
                state.lastPicks = null
                when {
                    path.isEmpty() -> out.text("pool $name — empty, :load <path> to import")
                    state.gitSpecOf(path) != null ->
                        if (!git.openGit(path, null, false, out)) {
                            session.close(name)
                            session.switchTo(before)
                        }
                    else -> {
                        load(path, out)
                        git.gitHint(path, out)
                    }
                }
            }
        }
    }

    private fun closePool(name: String, out: Output) {
        when {
            name.isBlank() -> out.text("usage: :close <name>")
            session.pool(name) == null -> out.text("no pool $name — :pools lists them")
            session.pools.size == 1 -> out.text("$name is the only pool — there is always one to evaluate against")
            else -> {
                val wasCurrent = name == session.current.name
                session.close(name)
                if (wasCurrent) state.lastPicks = null
                out.text("closed pool $name" + if (wasCurrent) " — now in pool ${session.current.name}" else "")
            }
        }
    }

    /**
     * A pool for each Bundle under a directory, named by its path, so a tree of
     * exports can be compared one bundle at a time; resolve() stays inside each.
     * Run again, it opens only what has arrived since.
     */
    private fun openBundles(argument: String, out: Output) {
        if (argument.isBlank()) {
            out.text("usage: :open-bundles <dir>")
            return
        }
        val root = Paths.get(state.expandHome(argument.trim())).toAbsolutePath().normalize()
        if (!root.isDirectory()) {
            out.text("not a directory: ${argument.trim()}")
            return
        }
        val files = Files.walk(root).use { stream ->
            stream.asSequence().filter { it.isRegularFile() && session.importer.reads(it.name) }.sortedBy { it.toString() }.toList()
        }
        // Already open by path, not by name: a name depends on the other files there,
        // so one that arrives can rename all of them.
        val open = session.pools.flatMapTo(HashSet()) { it.loadedPaths }
        val (known, arrived) = files.partition { it.toString() in open }
        // Parsed before anything is named, so a stray file that is no Bundle does not
        // keep the names from dropping what every bundle's path ends in.
        val problems = mutableListOf<String>()
        var notBundles = 0
        // `bundle.json: …` says nothing where every bundle is called that.
        fun where(file: Path) = root.relativize(file).parent?.let { "$it/" }.orEmpty()
        val bundles = arrived.mapNotNull { file ->
            val report = Importer.Report()
            val parsed = runCatching { session.importer.parse(file.name, file.readText(), report) }.getOrElse {
                problems += "${where(file)}${file.name}: unreadable (${it.message})"
                return@mapNotNull null
            }
            when {
                report.bundles > 0 -> Triple(file, parsed, report)
                // A Bundle that would not parse, or parses only in another version.
                report.problems.isNotEmpty() -> null.also { problems += report.problems.map { "${where(file)}$it" } }
                else -> null.also { notBundles++ }
            }
        }
        // The open ones are named along, so the new names drop what they all share.
        val named = (known + bundles.map { it.first }).sortedBy { it.toString() }
        val names = named.zip(poolNames(root, named, session.pools.map { it.name }.toSet())).toMap()
        val opened = mutableListOf<String>()
        for ((file, parsed, report) in bundles) {
            val name = names.getValue(file)
            val pool = session.open(name)!!
            parsed.forEach { pool.pool.add(it.resource, it.fullUrl, it.origin) }
            pool.pool.settleReferences()
            pool.loadedPaths += file.toString()
            opened += name
            problems += report.problems.map { "$name: $it" }
        }
        val already = known.size
        val header = buildList {
            add(
                when (opened.size) {
                    0 -> "no new pools"
                    1 -> "opened pool ${opened.single()}"
                    else -> "opened ${opened.size} pools, ${opened.first()} … ${opened.last()}"
                },
            )
            if (already > 0) add("$already already open")
            if (notBundles > 0) add("$notBundles file(s) skipped, not a Bundle")
        }.joinToString("; ")
        val hint = if (opened.isNotEmpty()) " — :pools lists them, :pool <name> switches" else ""
        state.withProblems(out, header + hint, problems)
    }

    /**
     * Closes the loaded pools whose paths have all gone, and with a cut-off those not
     * modified since. Git pools are left alone: they are watched, and reopen when
     * their repository comes back.
     */
    private fun prune(argument: String, out: Output) {
        val cutOff = if (argument.isBlank()) null else cutOff(argument.trim(), out) ?: return
        val loaded = session.pools.filter { it.git == null && it.unavailable == null && it.loadedPaths.isNotEmpty() }
        val reasons = loaded.mapNotNull { pool ->
            val present = pool.loadedPaths.map { Paths.get(it) }.filter { Files.exists(it) }
            when {
                present.isEmpty() -> pool to "gone"
                cutOff == null -> null
                else -> present.maxOf(::modified).takeIf { it < cutOff }?.let { pool to "modified ${state.commitTimes.shown(it)}" }
            }
        }
        if (reasons.isEmpty()) {
            val newer = cutOff?.let { " and modified since ${argument.trim()}" }.orEmpty()
            out.text("nothing to prune — ${loaded.size} loaded pool(s), all present$newer")
            return
        }
        val before = session.current.name
        val (closed, kept) = reasons.partition { (pool, _) -> session.close(pool.name) }
        if (session.current.name != before) state.lastPicks = null
        val header = buildList {
            if (closed.isNotEmpty()) add("pruned ${closed.size} pool(s)")
            if (kept.isNotEmpty()) add("kept ${kept.single().first.name}, the only pool left")
            if (session.current.name != before) add("now in pool ${session.current.name}")
        }.joinToString("; ")
        out.listing(
            listOf(Output.text(header)),
            closed.map { (pool, reason) ->
                Output.line {
                    +"  "
                    label(pool.name)
                    +"  "
                    hint(reason)
                }
            },
        )
    }

    /** `30d` or `12h` back from now, or the start of a date: `2026-10`, `2026-10-01`. */
    private fun cutOff(text: String, out: Output): Instant? {
        val start = try {
            AGE.matchEntire(text)?.let { match ->
                val (n, unit) = match.destructured
                val count = n.toLongOrNull() ?: throw ArithmeticException()
                Instant.now().minus(if (unit == "d") Duration.ofDays(count) else Duration.ofHours(count))
            } ?: state.commitTimes.start(text)
        } catch (e: DateTimeException) {
            null
        } catch (e: ArithmeticException) {
            null
        }
        if (start == null) out.text("not a cut-off: $text — an age (30d, 12h) or a date (2026-10-01)")
        return start
    }

    /** A file's modification time; a directory's is that of the newest file in it that would load. */
    private fun modified(path: Path): Instant {
        if (!path.isDirectory()) return Files.getLastModifiedTime(path).toInstant()
        return Files.walk(path).use { stream ->
            stream.asSequence().filter { it.isRegularFile() && session.importer.reads(it.name) }
                .maxOfOrNull { Files.getLastModifiedTime(it).toInstant() }
        } ?: Files.getLastModifiedTime(path).toInstant()
    }

    private fun sourceOf(pool: Pool): String {
        pool.git?.let { return "${it.source.path}@${it.lineLabel} at ${it.commit.short}" }
        pool.unavailable?.let { return "unavailable: ${it.source}" }
        return pool.loadedPaths.joinToString(", ").ifEmpty { "nothing loaded" }
    }

    private fun switchVersion(argument: String, out: Output) {
        if (argument.isBlank()) {
            out.text("FHIR ${session.version.id} — available: ${FhirVersion.ids()}")
            return
        }
        val version = FhirVersion.parse(argument) ?: run {
            out.text("unknown version $argument — available: ${FhirVersion.ids()}")
            return
        }
        if (version == session.version) {
            out.text("already on ${version.id}")
            return
        }
        try {
            // The pools keep their names and lose their contents, and with them the
            // paths the workspace would load.
            val old = session
            state.session = Session(version, old.pools.map { it.name })
            // Rows from the old pool; a pool-wide pick would otherwise still look valid.
            state.lastPicks = null
            val reset = when {
                old.pools.none { it.git != null } && session.pools.size > 1 -> "${session.pools.size} pools emptied"
                old.pools.none { it.git != null } -> "pool reset"
                else -> "loaded pools emptied, git pools read again"
            }
            out.text("switched to FHIR ${version.id.uppercase()} — $reset")
            // A git pool's contents are its revision, so it reads that again in the new version.
            for (pool in old.pools) {
                session.switchTo(pool.name)
                pool.unavailable?.let { git.unavailable(session.current, it) }
                val gitPool = pool.git ?: continue
                if (!git.openGit(gitPool.spec, gitPool.commit.id.name, gitPool.deltaScope, out)) {
                    // Kept, as at startup: saved again rather than lost, to open once it can.
                    git.unavailable(
                        session.current,
                        SavedPool(pool.name, source = gitPool.spec, at = gitPool.commit.id.name, deltaScope = gitPool.deltaScope),
                    )
                }
            }
            session.switchTo(old.current.name)
            old.close()
        } catch (e: FhirVersionUnavailable) {
            out.error("${e.message}")
        }
    }

    internal companion object {
        private val AGE = Regex("""(\d+)([dh])""")

        /**
         * A pool name for each of [files] under [root], from its path: the segments
         * joined by `-`, less the extension and any trailing segment every path ends
         * in, such as the `bundle.json` of each `<pipe>/<run>/bundle.json`. What a
         * name cannot hold becomes `-`; one that would start other than with a letter
         * gets a `b`, and a name that comes twice, or is in [taken], a number.
         */
        fun poolNames(root: Path, files: List<Path>, taken: Set<String> = emptySet()): List<String> {
            val parts = files.map { file ->
                val segments = root.relativize(file).map { it.toString() }
                segments.dropLast(1) + segments.last().substringBeforeLast('.')
            }
            var common = 0
            while (parts.size > 1 && parts.all { it.size > common + 1 } && parts.map { it[it.size - 1 - common] }.distinct().size == 1) common++
            val used = taken.toMutableSet()
            return parts.map { segments ->
                val base = segments.dropLast(common).joinToString("-").replace(UNNAMEABLE, "-").ifEmpty { "bundle" }
                    .let { if (it.first() in 'a'..'z' || it.first() in 'A'..'Z') it else "b$it" }
                generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base-$it" }.first { used.add(it) }
            }
        }

        private val UNNAMEABLE = Regex("[^A-Za-z0-9_-]")
    }
}
