package dev.fhirwalk.repl

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Where a session left off, so the next one can pick up there.
 *
 * A description of the state rather than a replay of commands: replaying `:load`,
 * `:fhir` and `:focus` breaks as soon as a file moves or an expression changes,
 * while each part of this can be restored, or reported as gone, on its own.
 *
 * @param limit rows per listing, when set with `:limit`; 0 is off.
 * @param pools in the order they were opened.
 * @param current the pool that was current.
 */
data class SavedState(
    val fhir: String? = null,
    val limit: Int? = null,
    val pools: List<SavedPool> = emptyList(),
    val current: String? = null,
)

/**
 * @param loads absolute paths, in the order they were loaded.
 * @param focus the `:focus` arguments that reach the focus, outermost first.
 * @param source for a git pool, its directory and line: `/abs/import@branch/out`.
 * @param at the commit a git pool was left at, which it restores at rather than the
 *   line's tip: what you were looking at, not what has arrived since.
 * @param deltaScope whether a git pool's evaluation ran over its delta only.
 */
data class SavedPool(
    val name: String,
    val loads: List<String> = emptyList(),
    val focus: List<String> = emptyList(),
    val source: String? = null,
    val at: String? = null,
    val deltaScope: Boolean = false,
)

/**
 * [SavedState] as `key<TAB>value` lines. Unknown keys are skipped, so a state file
 * written by a later version still restores what this one understands.
 *
 * A `pool` line starts a pool, and the `load` and `focus` lines after it are that
 * pool's. Lines before any `pool` line belong to the pool called
 * [Pool.DEFAULT]: a file from before there were several pools reads as one.
 */
class StateFile(file: Path?) {

    private val file = WatchedFile(file)

    val problem: String? get() = file.problem

    fun read(): SavedState {
        val lines = file.readIfChanged(force = true) ?: return SavedState()
        val entries = lines.mapNotNull { line ->
            val key = line.substringBefore('\t', "").trim()
            val value = line.substringAfter('\t', "").trim()
            (key to value).takeIf { key.isNotEmpty() && value.isNotEmpty() }
        }
        fun last(key: String) = entries.lastOrNull { it.first == key }?.second
        // Each pool's lines, under its name, in the order the pools first appear.
        val sections = LinkedHashMap<String, MutableList<Pair<String, String>>>()
        var section = Pool.DEFAULT
        for ((key, value) in entries) {
            when (key) {
                POOL -> section = value.also { sections.getOrPut(it) { mutableListOf() } }
                LOAD, FOCUS, SOURCE, AT, SCOPE -> sections.getOrPut(section) { mutableListOf() } += key to value
            }
        }
        val pools = sections.map { (name, lines) ->
            SavedPool(
                name,
                loads = lines.filter { it.first == LOAD }.map { it.second }.distinct(),
                focus = lines.filter { it.first == FOCUS }.map { it.second },
                source = lines.lastOrNull { it.first == SOURCE }?.second,
                at = lines.lastOrNull { it.first == AT }?.second,
                deltaScope = lines.lastOrNull { it.first == SCOPE }?.second == DELTA,
            )
        }
        return SavedState(
            fhir = last(FHIR),
            limit = last(LIMIT)?.toIntOrNull()?.takeIf { it >= 0 },
            pools = pools,
            current = last(CURRENT)?.takeIf { name -> pools.any { it.name == name } },
        )
    }

    /**
     * The default pool, first, goes without a `pool` line, so a session that never
     * opens another pool writes what it always did -- unless it is empty and others
     * follow, when only the line keeps it.
     */
    fun write(state: SavedState) {
        val lines = listOfNotNull(state.fhir?.let { "$FHIR\t$it" }) +
            listOfNotNull(state.limit?.let { "$LIMIT\t$it" }) +
            state.pools.flatMapIndexed { idx, pool ->
                val body = listOfNotNull(
                    pool.source?.let { "$SOURCE\t$it" },
                    pool.at?.let { "$AT\t$it" },
                    "$SCOPE\t$DELTA".takeIf { pool.deltaScope },
                ) + pool.loads.map { "$LOAD\t$it" } + pool.focus.map { "$FOCUS\t$it" }
                val implied = idx == 0 && pool.name == Pool.DEFAULT && (body.isNotEmpty() || state.pools.size == 1)
                (if (implied) emptyList() else listOf("$POOL\t${pool.name}")) + body
            } +
            listOfNotNull(state.current?.takeIf { it != state.pools.firstOrNull()?.name }?.let { "$CURRENT\t$it" })
        file.replace(lines.joinToString("") { "$it\n" })
    }

    private companion object {
        const val FHIR = "fhir"
        const val LIMIT = "limit"
        const val POOL = "pool"
        const val LOAD = "load"
        const val FOCUS = "focus"
        const val CURRENT = "current"
        const val SOURCE = "source"
        const val AT = "at"
        const val SCOPE = "scope"
        const val DELTA = "delta"
    }
}

/**
 * Everything a session keeps between runs, in one directory: the expression log,
 * names, saved views, the saved state and the line editor's history.
 *
 * Separate workspaces keep separate datasets apart -- the expressions worth keeping
 * for one pool are noise against another.
 *
 * @param dir where the files live, or null to keep everything in memory.
 */
class Workspace(val name: String, val dir: Path?) {
    val log = ExpressionLog(dir?.resolve("expressions"))
    val names = NameStore(dir?.resolve("names"))
    val views = ViewStore(dir?.resolve("views"))
    val state = StateFile(dir?.resolve("state"))

    /** For JLine's up-arrow history; null keeps it in memory. */
    val lineHistory: Path? = dir?.resolve("line-history")

    val problems: List<String> get() = listOfNotNull(log.problem, names.problem, views.problem, state.problem)
}

/**
 * The workspaces under one home. The default one is the home directory itself, so
 * the files from before workspaces existed belong to it; named ones live under
 * `workspaces/<name>/`, created on first write.
 *
 * @param home `~/.fhir-walk`, or null for workspaces that keep everything in memory.
 */
class Workspaces(private val home: Path?) {

    fun open(name: String): Workspace {
        require(valid(name)) { "not a valid workspace name: $name" }
        val dir = home?.let { if (name == DEFAULT) it else it.resolve(SUBDIR).resolve(name) }
        return Workspace(name, dir)
    }

    /** The default first, then the named ones that exist, alphabetically. */
    fun available(): List<String> {
        val named = home?.resolve(SUBDIR)?.takeIf { Files.isDirectory(it) }?.let { dir ->
            Files.list(dir).use { entries ->
                entries.filter { Files.isDirectory(it) }.map { it.fileName.toString() }.toList()
            }
        }.orEmpty()
        return listOf(DEFAULT) + named.filter { valid(it) && it != DEFAULT }.sorted()
    }

    companion object {
        const val DEFAULT = "default"
        private const val SUBDIR = "workspaces"

        fun valid(name: String): Boolean = KeyedStore.valid(name)

        /** `~/.fhir-walk`. */
        fun inHome(): Workspaces = Workspaces(Paths.get(System.getProperty("user.home"), ".fhir-walk"))
    }
}
