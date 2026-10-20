package dev.fhirwalk.repl.command

import dev.fhirwalk.model.FhirVersion
import dev.fhirwalk.model.FhirVersionUnavailable
import dev.fhirwalk.repl.KeyedStore
import dev.fhirwalk.repl.Output
import dev.fhirwalk.repl.Pool
import dev.fhirwalk.repl.SavedPool
import dev.fhirwalk.repl.SavedState
import dev.fhirwalk.repl.Session
import dev.fhirwalk.repl.Workspaces
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions
import org.jline.reader.LineReader

/** `:workspace`, and what a workspace keeps of a session: its saved state and its up-arrow history. */
internal class WorkspaceCommands(
    private val state: ReplState,
    private val pools: PoolCommands,
    private val git: GitCommands,
    private val focus: FocusCommands,
) : CommandGroup {

    private val session: Session get() = state.session

    override val commands = listOf(command(":workspace", run = ::switchWorkspace))

    /**
     * Brings back what [saved] describes, each part on its own: a path that has gone
     * is reported and kept, so it loads again once it is back; a focus step that no
     * longer resolves ends the focus where it got to.
     *
     * A session on another version than the one the workspace was left on gets
     * neither its pools nor its focus: a pool is one version, so they belong to the
     * other one, just as `:fhir` empties the pools. They stay saved until something
     * changes.
     */
    fun restore(saved: SavedState, out: Output) {
        val leftOn = saved.fhir?.takeIf { it != session.version.id }
        val paths = saved.pools.sumOf { it.loads.size }
        val restoring = if (leftOn != null && saved.pools.any { it.loads.isNotEmpty() || it.focus.isNotEmpty() }) {
            out.error(
                "the workspace was left on ${leftOn.uppercase()}, so its $paths path(s) and focus " +
                    "are not restored in ${session.version.id.uppercase()} — saved until something changes",
            )
            saved.copy(pools = emptyList(), current = null)
        } else {
            saved
        }
        restoring.limit?.let {
            state.limitSet = it
            state.rowLimit = it
        }
        // All of them first, so what each one reports says which pool it is about.
        restoring.pools.forEach { session.open(it.name) }
        if (restoring.pools.isNotEmpty() && restoring.pools.none { it.name == Pool.DEFAULT }) session.close(Pool.DEFAULT)
        for (pool in restoring.pools) {
            session.switchTo(pool.name)
            if (pool.source != null) {
                if (git.openGit(pool.source, pool.at, pool.deltaScope, out)) {
                    focus.restoreFocus(pool.focus, out)
                } else {
                    session.current.unavailable = pool
                    out.line { error("  kept in the workspace, to open once it is back") }
                }
                continue
            }
            for (path in pool.loads) {
                if (path !in session.current.loadedPaths) session.current.loadedPaths += path
                if (Files.exists(Paths.get(path))) {
                    pools.load(path, out)
                } else {
                    val file = state.workspace.dir?.resolve("state")?.let { " (or drop it from $it)" }.orEmpty()
                    out.error("$path is missing${state.inPool()} — kept in the workspace, to load once it is back$file")
                }
            }
            focus.restoreFocus(pool.focus, out)
        }
        session.switchTo(restoring.current ?: session.pools.first().name)
        // Whatever could not be restored stays saved: nothing is written until something changes.
        state.lastSaved = stateNow()
    }

    private fun stateNow(): SavedState =
        SavedState(
            session.version.id,
            state.limitSet,
            session.pools.map { pool ->
                val git = pool.git
                pool.unavailable ?: SavedPool(
                    pool.name,
                    pool.loadedPaths.toList(),
                    focus.focusSteps(pool.focus),
                    source = git?.spec,
                    at = git?.commit?.id?.name,
                    deltaScope = git?.deltaScope == true,
                )
            },
            session.current.name,
        )

    /** Writes the state when it has changed since it was last written or restored. */
    fun saveState(out: Output) {
        val now = stateNow()
        if (now == state.lastSaved) return
        state.workspace.state.write(now)
        state.lastSaved = now
        state.reportStorageProblems(out)
    }

    /**
     * Points the line editor's up-arrow history at the workspace's file.
     *
     * JLine's `load()` only clears what it holds when the file exists, so a new
     * workspace would inherit the old one's lines -- and save them as its own. An
     * empty file first makes every switch start from what the workspace has. It is
     * created owner-only, as JLine warns on every start about one others can read.
     */
    fun useLineHistory(reader: LineReader, out: Output) {
        val file = state.workspace.lineHistory ?: return
        if (file.parent?.let(Files::isDirectory) != true) {
            // Switching to a workspace does not create it, or a typo would stay as
            // one. Until something saved creates it, its lines are kept in memory;
            // purge() clears the old workspace's from memory, and with no file set
            // touches none on disk.
            reader.variables.remove(LineReader.HISTORY_FILE)
            reader.history.purge()
            state.historyPending = true
            return
        }
        state.historyPending = false
        reader.setVariable(LineReader.HISTORY_FILE, file)
        try {
            if (!Files.exists(file)) {
                if (file.fileSystem.supportedFileAttributeViews().contains("posix")) {
                    Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                } else {
                    Files.createFile(file)
                }
            }
            reader.history.load()
        } catch (e: java.io.IOException) {
            out.error("cannot use $file for line history: ${e.message}")
        }
    }

    /**
     * Once the workspace's directory exists, moves the lines typed so far into its
     * history file: they were typed in this workspace, so they belong to it.
     */
    fun attachPendingHistory(reader: LineReader, out: Output) {
        if (!state.historyPending || state.workspace.lineHistory?.parent?.let(Files::isDirectory) != true) return
        val typed = reader.history.map { it.line() }
        useLineHistory(reader, out)
        typed.forEach { reader.history.add(it) }
    }

    /** `:workspace` shows the current one and lists the rest; `:workspace biobank` switches. */
    private fun switchWorkspace(argument: String, out: Output) {
        val workspace = state.workspace
        val workspaces = state.workspaces
        if (argument.isBlank()) {
            val where = workspace.dir?.let { "  ($it)" }.orEmpty()
            out.text("workspace ${workspace.name}$where")
            workspaces.available().filter { it != workspace.name }.takeIf { it.isNotEmpty() }?.let {
                out.text("  also: ${it.joinToString(", ")}")
            }
            return
        }
        if (!Workspaces.valid(argument)) {
            out.text("not a valid workspace name: $argument — ${KeyedStore.RULE}")
            return
        }
        if (argument == workspace.name) {
            out.text("already in workspace $argument")
            return
        }
        val next = workspaces.open(argument)
        // Before anything can create it.
        val isNew = argument !in workspaces.available()
        val saved = next.state.read()
        // As at startup: a version this build cannot open falls back to the default.
        val notes = mutableListOf<String>()
        val nextSession = startSession(saved.fhir?.let(FhirVersion::parse), explicit = false) { notes += it }
        // The old workspace's state was saved after the last command that changed it.
        state.reader?.history?.save()
        state.workspace = next
        session.close()
        state.session = nextSession
        state.lastPicks = null
        state.nameable = null
        state.lastListing = null
        state.lastExpression = null
        state.lastView = null
        state.limitSet = null
        state.rowLimit = state.windowRowLimit
        state.reader?.let { useLineHistory(it, out) }
        out.text(
            "workspace $argument, FHIR ${session.version.id.uppercase()}" +
                if (isNew) " — new, created once something is saved" else "",
        )
        notes.forEach { out.error(it) }
        state.reportStorageProblems(out)
        restore(saved, out)
    }

    companion object {
        /**
         * The session for [version], or the default one when the workspace was left on
         * a version this build cannot open. A version asked for on the command line is
         * not second-guessed: that failure is the caller's to report.
         */
        fun startSession(version: FhirVersion?, explicit: Boolean, note: (String) -> Unit): Session {
            if (version == null || explicit) return Session(version ?: FhirVersion.DEFAULT)
            return try {
                Session(version)
            } catch (e: FhirVersionUnavailable) {
                note("${e.message} — starting on ${FhirVersion.DEFAULT.id} instead")
                Session(FhirVersion.DEFAULT)
            }
        }
    }
}
