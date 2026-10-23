package dev.fhirwalk.repl.command

import dev.fhirwalk.repl.KeyedStore
import dev.fhirwalk.repl.MetaDefinition
import dev.fhirwalk.repl.MetaPool
import dev.fhirwalk.repl.MetaStore
import dev.fhirwalk.repl.Output
import dev.fhirwalk.repl.Pool
import dev.fhirwalk.repl.Session
import java.nio.file.Paths
import kotlin.io.path.isDirectory

/** Meta pools: defining one over a directory of bundles, reading it when asked, and moving its cut-off. */
internal class MetaCommands(private val state: ReplState) : CommandGroup {

    private val session: Session get() = state.session

    private val metas: MetaStore get() = state.workspace.metas

    override val commands = listOf(
        command(":meta", run = ::meta),
        command(":unmeta", run = ::unmeta),
        command(":cutoff", run = ::cutOff),
        command(":members", run = ::members),
    )

    /** `:meta` lists, `:meta exports` reads one, `:meta exports <dir> [cut-off]` defines it and reads it. */
    private fun meta(argument: String, out: Output) {
        state.reportStorageProblems(out)
        if (argument.isBlank()) {
            list(out)
            return
        }
        val name = argument.substringBefore(' ').trim()
        val rest = argument.substringAfter(' ', "").trim()
        if (rest.isEmpty()) {
            val definition = metas.get(name) ?: run {
                out.text("no meta pool $name — :meta lists them, :meta $name <dir> [cut-off] defines one")
                return
            }
            open(name, definition, out)
            return
        }
        if (!KeyedStore.valid(name)) {
            out.text("not a valid meta pool name: $name — ${KeyedStore.RULE}")
            return
        }
        if (session.pool(name)?.let { it.meta == null } == true) {
            out.text("pool $name is open and is no meta pool — pick another name, or :close $name first")
            return
        }
        // A last word that reads as a cut-off is one; a path with a space in it still works.
        val last = rest.substringAfterLast(' ', "")
        val cutOff = last.takeIf { it.isNotEmpty() && MetaPool.since(it, state.commitTimes) != null }
        val dir = Paths.get(state.expandHome(if (cutOff == null) rest else rest.dropLast(last.length).trim())).toAbsolutePath().normalize()
        if (!dir.isDirectory()) {
            // `archive 30days` is a directory and a mistyped cut-off, not a directory called that.
            val before = rest.dropLast(last.length).trim()
            val mistyped = cutOff == null && last.isNotEmpty() && Paths.get(state.expandHome(before)).isDirectory()
            out.text(if (mistyped) "not a cut-off: $last — an age (30d, 12h) or a date (2026-10-01)" else "not a directory: $dir")
            return
        }
        val definition = MetaDefinition(dir.toString(), cutOff)
        val before = metas.set(name, definition)
        state.reportStorageProblems(out)
        if (before != null && before != definition) out.text("meta pool $name was ${describe(before)}")
        open(name, definition, out)
    }

    private fun list(out: Output) {
        val all = metas.all()
        if (all.isEmpty()) {
            out.text("no meta pools — :meta <name> <dir> [cut-off] defines one over a directory of bundles")
            return
        }
        val width = all.keys.maxOf { it.length }
        out.listing(
            header = emptyList(),
            rows = all.map { (name, definition) ->
                val meta = session.pool(name)?.meta
                Output.line {
                    +(if (session.current.name == name) "* " else "  ")
                    label(name.padEnd(width))
                    +"  "
                    hint((meta?.let { "${it.members.size} bundle(s)" } ?: "not read").padEnd(14))
                    +"  "
                    shrink(describe(definition))
                }
            },
        )
    }

    /** `<dir>, since 30d`. */
    private fun describe(definition: MetaDefinition): String =
        definition.dir + (definition.cutOff?.let { ", since $it" } ?: ", every bundle")

    private fun unmeta(name: String, out: Output) {
        if (name.isBlank()) {
            out.text("usage: :unmeta <name>")
            return
        }
        val before = metas.remove(name) ?: run {
            state.reportStorageProblems(out)
            out.text("no meta pool $name — :meta lists them")
            return
        }
        state.reportStorageProblems(out)
        val closed = session.pool(name)?.takeIf { it.meta != null }?.let { pool ->
            // There is always a pool to evaluate against.
            if (session.pools.size == 1) session.open(Pool.DEFAULT)
            val wasCurrent = session.current.name == name
            if (wasCurrent) state.lastPicks = null
            when {
                !session.close(name) -> {
                    // The only pool, and called what a new one would be: it stays, emptied.
                    pool.meta = null
                    pool.focus = null
                    pool.pool.clear()
                    " — emptied pool $name, the only one"
                }
                wasCurrent -> " — closed it, now in pool ${session.current.name}"
                else -> " — closed it"
            }
        }.orEmpty()
        out.text("forgot meta pool $name (${describe(before)})$closed")
    }

    /** `:cutoff` says what it is; `:cutoff 30d`, `:cutoff 2026-10-01` or `:cutoff none` moves it and reads again. */
    private fun cutOff(argument: String, out: Output) {
        val pool = session.current
        val meta = pool.meta ?: run {
            out.text("${state.poolName()} is no meta pool — a cut-off belongs to one, :meta lists them")
            return
        }
        if (argument.isBlank()) {
            val since = meta.definition.cutOff?.let { MetaPool.since(it, state.commitTimes) }
            out.text(
                when {
                    meta.definition.cutOff == null -> "cut-off: none, every bundle — :cutoff 30d or :cutoff 2026-10-01 sets one"
                    since == null -> "cut-off: ${meta.definition.cutOff} is not one, so every bundle is read"
                    else -> "cut-off: ${meta.definition.cutOff}, bundles modified since ${state.commitTimes.shown(since)}"
                },
            )
            return
        }
        val text = argument.trim()
        val cutOff = if (text.equals("none", ignoreCase = true)) null else text
        if (cutOff != null && MetaPool.since(cutOff, state.commitTimes) == null) {
            out.text("not a cut-off: $cutOff — an age (30d, 12h), a date (2026-10-01) or none")
            return
        }
        val definition = meta.definition.copy(cutOff = cutOff)
        metas.set(pool.name, definition)
        state.reportStorageProblems(out)
        open(pool.name, definition, out)
    }

    private fun members(argument: String, out: Output) {
        val meta = session.current.meta ?: run {
            out.text("${state.poolName()} is no meta pool — :meta lists them")
            return
        }
        val shown = meta.members.filter { it.name.contains(argument, ignoreCase = true) }
        if (shown.isEmpty()) {
            out.text(if (argument.isBlank()) "no bundles in ${state.poolName()}" else "no bundle in ${state.poolName()} is called anything like $argument")
            return
        }
        val width = shown.maxOf { it.name.length }
        out.listing(
            listOf(Output.text("${shown.size} of ${meta.members.size} bundle(s) — :scope <bundle> runs pool-wide over one")),
            rows = shown.map { member ->
                Output.line {
                    +(if (meta.scoped == member.name) "* " else "  ")
                    label(member.name.padEnd(width))
                    +"  "
                    count(member.resources.toString().padStart(6))
                    +"  "
                    hint(state.commitTimes.shown(member.modified))
                    +"  "
                    shrink(meta.dir.relativize(member.path).toString())
                }
            },
        )
    }

    /**
     * Reads a meta pool's directory again, opening the pool first if it is not open,
     * and switches to it. A focus or picked rows would point into what was read
     * before, so both go.
     */
    fun open(name: String, definition: MetaDefinition, out: Output) {
        val dir = Paths.get(definition.dir)
        if (!dir.isDirectory()) {
            out.error("meta pool $name: ${definition.dir} is not a directory — kept, to read once it is back")
            return
        }
        val existing = session.pool(name)
        if (existing != null && existing.meta == null) {
            out.text("pool $name is open and is no meta pool — :close $name first")
            return
        }
        val pool = existing ?: session.open(name)!!
        val meta = pool.meta ?: MetaPool(definition).also { pool.meta = it }
        meta.definition = definition
        val dropped = pool.focus != null
        pool.focus = null
        session.switchTo(name)
        state.lastPicks = null
        read(pool, meta, out, if (dropped) "; focus cleared" else "")
    }

    /** Fills [pool] from its definition, saying what it found. Also how `:fhir` reads one again. */
    fun read(pool: Pool, meta: MetaPool, out: Output, note: String = "") {
        val cutOff = meta.definition.cutOff
        val since = cutOff?.let { MetaPool.since(it, state.commitTimes) }
        val scan = meta.scan(pool.importer, pool.pool, since)
        if (scan.missing) {
            out.error("meta pool ${pool.name}: ${meta.definition.dir} is not a directory — kept, to read once it is back")
            return
        }
        val header = buildList {
            add("meta pool ${pool.name}: ${scan.members} bundle(s), ${pool.pool.size} resource(s)")
            if (scan.older > 0) add("${scan.older} older than $cutOff")
            if (cutOff != null && since == null) add("$cutOff is not a cut-off, so every bundle is read")
            if (scan.skipped > 0) add("${scan.skipped} file(s) skipped, not a Bundle")
            scan.unscoped?.let { add("scope on $it dropped, no longer a bundle here") }
        }.joinToString("; ") + note
        state.withProblems(out, header, scan.problems)
    }
}
