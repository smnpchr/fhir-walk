package dev.fhirwalk.repl

import dev.fhirwalk.complete.FhirPathCompleter
import dev.fhirwalk.model.FhirVersion
import dev.fhirwalk.pool.GitSource
import dev.fhirwalk.repl.command.Command
import dev.fhirwalk.repl.command.DisplayCommands
import dev.fhirwalk.repl.command.EvaluateCommands
import dev.fhirwalk.repl.command.FocusCommands
import dev.fhirwalk.repl.command.GitCommands
import dev.fhirwalk.repl.command.HelpCommands
import dev.fhirwalk.repl.command.Manual
import dev.fhirwalk.repl.command.PoolCommands
import dev.fhirwalk.repl.command.RecordCommands
import dev.fhirwalk.repl.command.ReplState
import dev.fhirwalk.repl.command.WorkspaceCommands
import dev.fhirwalk.repl.command.command
import org.jline.reader.EndOfFileException
import org.jline.reader.LineReader
import org.jline.reader.Reference
import org.jline.reader.UserInterruptException
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import java.io.PrintWriter
import java.nio.file.Paths

/**
 * The read-eval-print loop: reads a line, hands an expression to [EvaluateCommands]
 * and a `:command` to the [Command] of that name, and prints what it said.
 *
 * The commands live in their groups ([PoolCommands], [GitCommands], …), sharing
 * one [ReplState]; what each is called and does is written in `help.yaml`, read by
 * [Manual].
 *
 * @param explicitVersion the version asked for on the command line, which wins over
 *   the one the workspace was left on; null to take the workspace's.
 * @param initialPaths paths from the command line, loaded on top of the workspace's.
 * @param workspaces where workspaces live; `Workspaces(null)` keeps everything in memory.
 * @param fresh start with an empty pool and no focus, leaving the saved state as it
 *   is until something changes.
 */
class Repl(
    explicitVersion: FhirVersion?,
    private val initialPaths: List<String>,
    workspaces: Workspaces = Workspaces.inHome(),
    workspaceName: String = Workspaces.DEFAULT,
    fresh: Boolean = false,
) {

    /** Said once [open] has somewhere to say it. */
    private val startupNotes = mutableListOf<String>()

    private val state: ReplState

    /** Read before the session exists, because it decides the session's version. */
    private val restoring: SavedState

    init {
        val workspace = workspaces.open(workspaceName)
        restoring = if (fresh) SavedState() else workspace.state.read()
        val session = WorkspaceCommands.startSession(
            explicitVersion ?: restoring.fhir?.let(FhirVersion::parse),
            explicit = explicitVersion != null,
        ) { startupNotes += it }
        state = ReplState(workspaces, workspace, session)
    }

    private val session: Session get() = state.session

    /** The workspace in use, for tests. */
    internal val currentWorkspace: Workspace get() = state.workspace

    private val manual = Manual.load()

    private val evaluateCommands = EvaluateCommands(state)
    private val focusCommands = FocusCommands(state)
    private val gitCommands = GitCommands(state, focusCommands)
    private val poolCommands = PoolCommands(state, gitCommands)
    private val workspaceCommands = WorkspaceCommands(state, poolCommands, gitCommands, focusCommands)

    private val commands: List<Command> = listOf(
        poolCommands, gitCommands, evaluateCommands, focusCommands, RecordCommands(state), workspaceCommands,
        DisplayCommands(state),
        // Asked for only when :help runs; the aliases include :help's own, so they
        // cannot be known before this list is.
        HelpCommands(manual, RENAMED) { aliases },
    ).flatMap { it.commands } + command(":quit", ":exit", ":q", exits = true) { _, _ -> }

    /** Every spelling that runs a command, the aliases included, to the command. */
    private val byName: Map<String, Command> =
        commands.flatMap { command -> (listOf(command.name) + command.aliases).map { it to command } }.toMap()

    private val aliases: Map<String, String> =
        commands.flatMap { command -> command.aliases.map { it to command.name } }.toMap()

    init {
        manual.requireDocuments(commands.map { it.name }.toSet())
    }

    /** For the TAB menu, in the order `:help` lists them. */
    private val commandInfo: List<FhirPathCompleter.CommandInfo> =
        manual.commands.map { FhirPathCompleter.CommandInfo(it.name, it.args, it.summary) }

    fun run() {
        val terminal: Terminal = TerminalBuilder.builder().dumb(true).build()
        val out = terminal.writer()
        // Built directly rather than through LineReaderBuilder, which can only make a
        // plain LineReaderImpl: the menu's group order needs the subclass.
        val reader = FhirWalkLineReader(terminal).apply {
            parser = FhirPathParser()
            completer = FhirPathCompleter(
                { session.navigator }, { session.pool }, { commandInfo }, { session.focus },
                { state.log.entries() }, { state.names.all() }, { state.views.all().keys }, { state.workspaces.available() },
                { session.pools.map { it.name } }, { session.current.git?.source?.refNames().orEmpty() },
                { dir -> runCatching { GitSource.refNamesAt(Paths.get(state.expandHome(dir))) }.getOrDefault(emptyList()) },
                state::gitSpecOf,
                gitCommands::commitChoices,
                { manual.topics.map { FhirPathCompleter.CommandInfo(it.name, "", it.title) } },
            )
            // AUTO_LIST prints a passive list that arrow keys cannot touch, so it
            // costs an extra TAB before anything is navigable -- press an arrow at a
            // passive list and you get command history instead of the next candidate.
            // The menu shows the same grouped list and does accept arrows, so skip
            // the passive stage and let the first TAB open the menu.
            option(LineReader.Option.AUTO_LIST, false)
            option(LineReader.Option.AUTO_MENU, true)
            option(LineReader.Option.AUTO_GROUP, true)
            option(LineReader.Option.GROUP_PERSIST, true)
            option(LineReader.Option.CASE_INSENSITIVE, true)
            option(LineReader.Option.DISABLE_EVENT_EXPANSION, true)
        }
        state.reader = reader
        show(out) { workspaceCommands.useLineHistory(reader, it) }
        bindArrowKeys(reader)
        state.style = Style.detect(terminal)

        // Fill the window and keep the rest for :more, rather than a fixed guess.
        if (terminal.height > MIN_ROW_LIMIT + 8) state.rowLimit = terminal.height - 8
        state.windowRowLimit = state.rowLimit
        if (terminal.width > MIN_VALUE_WIDTH) state.columns = terminal.width

        out.println("fhir-walk — FHIRPath over a local resource pool.  :help for commands, :manual for the guide")
        open(out)
        out.println(if (session.pool.size == 0) "pool is empty — :load <path> to import" else "TAB on an empty line lists the resource types in the pool")
        out.flush()

        while (true) {
            // Before the prompt too, so it shows where the pool is now.
            show(out) { checkSources(it) }
            out.flush()
            val line = try {
                reader.readLine(prompt())
            } catch (e: UserInterruptException) {
                continue
            } catch (e: EndOfFileException) {
                break
            }
            val input = line?.trim().orEmpty()
            if (input.isEmpty()) continue
            try {
                if (!dispatch(input, out)) break
            } catch (e: Exception) {
                out.println(state.style.error("! ${e.message ?: e.javaClass.simpleName}"))
            }
            show(out) { workspaceCommands.attachPendingHistory(reader, it) }
            out.flush()
        }
        out.println("bye")
        out.flush()
        reader.history.save()
        terminal.close()
    }

    /**
     * Restores the workspace, then loads the command line's paths on top. Internal,
     * like [dispatch], so tests can start a session without a terminal.
     */
    internal fun open(out: PrintWriter) = show(out) { open(it) }

    private fun open(out: Output) {
        startupNotes.forEach { out.error(it) }
        startupNotes.clear()
        val where = if (state.workspace.name == Workspaces.DEFAULT) "" else "workspace ${state.workspace.name}, "
        out.text("${where}FHIR ${session.version.id.uppercase()}")
        state.reportStorageProblems(out)
        workspaceCommands.restore(restoring, out)
        initialPaths.forEach { poolCommands.load(it, out) }
        workspaceCommands.saveState(out)
    }

    /**
     * The pool's name goes before a focus once there are several to tell apart, or
     * when it is a git pool, whose revision always shows. With the scope on the
     * delta, the delta's size stands where the pool's would.
     */
    private fun prompt(): String {
        val git = session.current.git
        val where = session.current.name + git?.let { "@${it.commit.short}" }.orEmpty()
        val focus = session.focus?.label?.let { abbreviate(it, ReplState.PROMPT_WIDTH) }
        val scope = when {
            focus == null && git?.deltaScope == true -> "$where Δ${git.delta.size}"
            focus == null -> "$where(${session.pool.size})"
            session.pools.size > 1 || git != null -> "$where:$focus"
            else -> focus
        }
        return "${session.version.id}:$scope> "
    }

    /**
     * One line of input, as [run] hands it over. Internal so tests can drive the
     * REPL without a terminal: none of this needs one.
     *
     * @return false to exit.
     */
    internal fun dispatch(input: String, out: PrintWriter): Boolean {
        val output = Output()
        try {
            return dispatch(input, output)
        } finally {
            show(output, out)
        }
    }

    /**
     * As [dispatch], but what the line had to say is returned rather than printed:
     * what a command found, apart from the window it would be shown in. For tests.
     */
    internal fun outputOf(input: String): Output = Output().also {
        dispatch(input, it)
        remember(it)
    }

    /**
     * The current git pool's line, for TAB after `:at`; see [GitCommands.commitChoices].
     * Internal so tests can check what the menu would offer.
     */
    internal fun commitChoices(): List<FhirPathCompleter.CommitChoice> = gitCommands.commitChoices()

    /** Prints what [say] says, as everything is printed: fitted, coloured, and kept for `:more`. */
    private fun show(out: PrintWriter, say: (Output) -> Unit) = show(Output().also(say), out)

    private fun show(output: Output, out: PrintWriter) {
        remember(output)
        Printer(state.style, state.columns, state.rowLimit).print(output, out)
    }

    private fun remember(output: Output) {
        output.listing?.let { state.lastListing = it }
    }

    private fun dispatch(input: String, out: Output): Boolean {
        // The repository may have gone while the prompt waited; nothing should answer from it.
        checkSources(out)
        if (!input.startsWith(":")) {
            // `all Specimen.subject` is a dropped colon, not an expression. Saying so
            // beats handing the whole line to the FHIRPath parser -- unless the word is
            // an element where the expression runs: `name` is a command and, far more
            // often, Patient.name.
            val firstWord = input.substringBefore(' ')
            val command = RENAMED[":$firstWord"] ?: ":$firstWord"
            if (command in byName && !(isElementHere(firstWord) && parses(input))) {
                out.error("not a FHIRPath expression — did you mean  $command${input.drop(firstWord.length)}")
                return true
            }
            evaluateCommands.evaluate(input, out)
            return true
        }
        val name = input.substringBefore(' ')
        val argument = input.substringAfter(' ', "").trim()
        // Saved even when the command throws part-way: a path already in the pool
        // must not be missing from the workspace. Expressions change nothing saved.
        try {
            val command = byName[name]
            when {
                command != null -> {
                    command.run(argument, out)
                    return !command.exits
                }
                name in RENAMED -> {
                    val now = RENAMED.getValue(name)
                    out.error("$name is now $now — did you mean  $now${argument.let { if (it.isEmpty()) "" else " $it" }}")
                }
                else -> out.text("unknown command $name — :help for the list")
            }
            return true
        } finally {
            workspaceCommands.saveState(out)
        }
    }

    /** See [GitCommands.checkSources]. */
    private fun checkSources(out: Output) {
        if (gitCommands.checkSources(out)) workspaceCommands.saveState(out)
    }

    /**
     * Whether the line is FHIRPath at all. `name family #1` starts with an element
     * name too, but no expression continues that way, so it is a dropped colon.
     */
    private fun parses(input: String): Boolean = session.evaluator.validate(input) == null

    /** Whether [word] is a child of the focus, or with none, of a resource type in the pool. */
    private fun isElementHere(word: String): Boolean {
        val paths = session.focus?.let { listOf(it.path) } ?: session.pool.types()
        return paths.any { path -> session.navigator.childrenAt(path).any { it.name == word } }
    }

    /**
     * Binds both encodings of the arrow keys, in the line editor and in the
     * completion menu.
     *
     * JLine asks the terminal for application-cursor-keys mode (DECCKM, the
     * `ESC [ ? 1 h` it writes with every prompt) and then binds only the SS3 form
     * (`ESC O A`) that the mode implies. Terminals and multiplexers that keep sending
     * the CSI form (`ESC [ A`) are left with arrow keys that type a literal "[A" --
     * which is why navigation worked in some terminals and not in others. Binding
     * both forms makes it independent of whether the mode took effect.
     *
     * In the menu the same keys walk the candidates, so a long extension URL can be
     * picked with Up/Down rather than by counting TAB presses.
     */
    private fun bindArrowKeys(reader: LineReader) {
        val keyMaps = reader.keyMaps
        keyMaps[LineReader.MAIN]?.apply {
            bind(Reference(LineReader.UP_LINE_OR_SEARCH), ESC_CSI + "A", ESC_SS3 + "A")
            bind(Reference(LineReader.DOWN_LINE_OR_SEARCH), ESC_CSI + "B", ESC_SS3 + "B")
            bind(Reference(LineReader.FORWARD_CHAR), ESC_CSI + "C", ESC_SS3 + "C")
            bind(Reference(LineReader.BACKWARD_CHAR), ESC_CSI + "D", ESC_SS3 + "D")
        }
        keyMaps[LineReader.MENU]?.apply {
            bind(Reference(LineReader.MENU_COMPLETE), ESC_CSI + "B", ESC_SS3 + "B")
            bind(Reference(LineReader.REVERSE_MENU_COMPLETE), ESC_CSI + "A", ESC_SS3 + "A")
        }
    }

    internal companion object {
        private const val MIN_VALUE_WIDTH = Printer.MIN_VALUE_WIDTH
        private const val MIN_ROW_LIMIT = 8

        /** The naming commands' old spellings, before each said what it names (#29). */
        val RENAMED = mapOf(
            ":name" to ":name-expr",
            ":unname" to ":unname-expr",
            ":save" to ":name-view",
            ":unsave" to ":unname-view",
        )

        /**
         * The two encodings an arrow key arrives in, depending on whether
         * application-cursor-keys mode took effect: CSI (ESC [ A) or SS3 (ESC O A).
         */
        private const val ESC_CSI = "\u001b["
        private const val ESC_SS3 = "\u001bO"
    }
}
