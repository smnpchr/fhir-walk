package dev.fhirwalk.repl.command

import dev.fhirwalk.model.FhirVersion
import dev.fhirwalk.model.FhirVersionUnavailable
import dev.fhirwalk.repl.KeyedStore
import dev.fhirwalk.repl.Output
import dev.fhirwalk.repl.Pool
import dev.fhirwalk.repl.SavedPool
import dev.fhirwalk.repl.Session
import dev.fhirwalk.repl.abbreviate
import java.nio.file.Paths

/** Loading into pools, opening and switching them, and the FHIR version they are all read in. */
internal class PoolCommands(private val state: ReplState, private val git: GitCommands) : CommandGroup {

    private val session: Session get() = state.session

    override val commands = listOf(
        command(":load") { argument, out -> if (argument.isBlank()) out.text("usage: :load <path>") else load(argument, out) },
        command(":pool") { argument, out -> if (argument.isBlank()) poolSummary(out) else switchPool(argument, out) },
        command(":pools") { _, out -> listPools(out) },
        command(":open", run = ::openPool),
        command(":close", run = ::closePool),
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
                session.current.unavailable = pool.unavailable
                val gitPool = pool.git ?: continue
                if (!git.openGit(gitPool.spec, gitPool.commit.id.name, gitPool.deltaScope, out)) {
                    // Kept, as at startup: saved again rather than lost, to open once it can.
                    session.current.unavailable =
                        SavedPool(pool.name, source = gitPool.spec, at = gitPool.commit.id.name, deltaScope = gitPool.deltaScope)
                }
            }
            session.switchTo(old.current.name)
            old.close()
        } catch (e: FhirVersionUnavailable) {
            out.error("${e.message}")
        }
    }
}
