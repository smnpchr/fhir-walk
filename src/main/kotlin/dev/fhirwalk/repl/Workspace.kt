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
 * @param loads absolute paths, in the order they were loaded.
 * @param limit rows per listing, when set with `:limit`; 0 is off.
 * @param focus the `:focus` arguments that reach the focus, outermost first.
 */
data class SavedState(
    val fhir: String? = null,
    val loads: List<String> = emptyList(),
    val limit: Int? = null,
    val focus: List<String> = emptyList(),
)

/**
 * [SavedState] as `key<TAB>value` lines. Unknown keys are skipped, so a state file
 * written by a later version still restores what this one understands.
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
        fun all(key: String) = entries.filter { it.first == key }.map { it.second }
        return SavedState(
            fhir = all(FHIR).lastOrNull(),
            loads = all(LOAD).distinct(),
            limit = all(LIMIT).lastOrNull()?.toIntOrNull()?.takeIf { it >= 0 },
            focus = all(FOCUS),
        )
    }

    fun write(state: SavedState) {
        val lines = listOfNotNull(state.fhir?.let { "$FHIR\t$it" }) +
            state.loads.map { "$LOAD\t$it" } +
            listOfNotNull(state.limit?.let { "$LIMIT\t$it" }) +
            state.focus.map { "$FOCUS\t$it" }
        file.replace(lines.joinToString("") { "$it\n" })
    }

    private companion object {
        const val FHIR = "fhir"
        const val LOAD = "load"
        const val LIMIT = "limit"
        const val FOCUS = "focus"
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
