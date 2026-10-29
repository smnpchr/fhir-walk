package dev.fhirwalk.repl.command

import dev.fhirwalk.eval.Focus
import dev.fhirwalk.eval.FocusResolver
import dev.fhirwalk.eval.ViewBuilder
import dev.fhirwalk.pool.GitSource
import dev.fhirwalk.pool.PooledResource
import dev.fhirwalk.repl.CommitTimes
import dev.fhirwalk.repl.ExpressionLog
import dev.fhirwalk.repl.NameStore
import dev.fhirwalk.repl.Output
import dev.fhirwalk.repl.SavedState
import dev.fhirwalk.repl.Session
import dev.fhirwalk.repl.Style
import dev.fhirwalk.repl.ViewStore
import dev.fhirwalk.repl.Workspace
import dev.fhirwalk.repl.Workspaces
import java.nio.file.Files
import java.nio.file.Paths
import org.hl7.fhir.instance.model.api.IBase
import org.jline.reader.LineReader

/**
 * What the commands share: the session and workspace they act on, what the last
 * commands left behind for the next one, and the helpers more than one group uses.
 * Each [CommandGroup] holds the one [dev.fhirwalk.repl.Repl] made.
 */
internal class ReplState(
    val workspaces: Workspaces,
    var workspace: Workspace,
    var session: Session,
) {

    val log: ExpressionLog get() = workspace.log
    val names: NameStore get() = workspace.names
    val views: ViewStore get() = workspace.views

    val commitTimes = CommitTimes()

    /** Set by `:limit`, and saved; null leaves the limit to the window size. */
    var limitSet: Int? = null

    /** What the state file was last written with, or found holding, so unchanged state is not rewritten. */
    var lastSaved: SavedState? = null

    /** The columns of the last `:view`, for `:name-view`. */
    var lastView: List<ViewBuilder.Column>? = null

    /** Null until [dev.fhirwalk.repl.Repl.run] has built it; the tests drive the commands without one. */
    var reader: LineReader? = null

    /**
     * Set while the workspace has no directory yet, so its up-arrow history is kept
     * in memory; [WorkspaceCommands.attachPendingHistory] moves it to the file once
     * something saved has created the directory.
     */
    var historyPending = false

    /**
     * The last listing printed, so `:more` can print it whole: trimmed to the window
     * when it was shown, both in rows and in the width of each part. Truncating
     * without a way back to the whole thing is worse than printing it all.
     */
    var lastListing: Output.Listed? = null

    /**
     * The last expression that was run, so a bare `:why` can re-diagnose it.
     *
     * The hint that leads to `:why` is printed the moment an expression comes back
     * empty, which is exactly when retyping a long path — or a path with an extension
     * URL in it — is most annoying. Only expressions are remembered, so `:pool`
     * between the two does not lose it.
     */
    var lastExpression: String? = null

    /**
     * What `:name-expr <name>` with no number names: the expression just run, if it was
     * pool-wide and matched -- the ones that are recorded. Every evaluation replaces
     * it, so a focus-relative or empty one in between leaves nothing to name rather
     * than an older expression the user has stopped looking at.
     */
    var nameable: String? = null

    /**
     * The rows of the last result, so `:focus [n]` can pick one. Every result
     * replaces it -- a numbered one with its rows, any other (`:all`, an empty or
     * failed one, a `:focus` that did not pick) with nothing -- so `[n]` never
     * reaches past what is on screen. Command output (`:pool`, `:more`, `:why`, a
     * focus announcement) is not a result and leaves it alone, which is what lets
     * `:focus [0]`, `:focus ..`, `:focus [1]` work. A pick made under a different
     * focus is refused by the resolver.
     */
    var lastPicks: FocusResolver.Picks? = null

    /** Rows printed per listing before the rest is held for `:more`; 0 = unlimited. */
    var rowLimit = DEFAULT_ROW_LIMIT

    /** What [rowLimit] is without a `:limit`: the window's height, once [dev.fhirwalk.repl.Repl.run] knows it. */
    var windowRowLimit = DEFAULT_ROW_LIMIT

    var columns = DEFAULT_COLUMNS

    /** Plain until [dev.fhirwalk.repl.Repl.run] has a terminal to ask; see [Style.detect]. */
    var style = Style.PLAIN

    // ------------------------------------------------------------------- storage

    private val reportedProblems = mutableSetOf<String>()

    /**
     * Each problem once, wherever the log or the names are touched first: at
     * startup, or when a write fails later. Asking for the entries is what reads
     * the files, so a problem is known by the time this looks.
     */
    fun reportStorageProblems(out: Output) {
        log.entries()
        names.all()
        views.all()
        workspace.metas.all()
        workspace.problems.filter { reportedProblems.add(it) }.forEach {
            out.error("$it — changes are kept for this session only")
        }
    }

    fun record(expression: String, out: Output) {
        log.record(expression)
        reportStorageProblems(out)
    }

    // ---------------------------------------------------------------- expressions

    /**
     * A syntax error belongs to the expression, not to the data: report it once,
     * rather than once per resource in the pool.
     *
     * @return true when the expression could not be parsed and nothing should run.
     */
    fun reportSyntaxError(expression: String, out: Output): Boolean {
        val error = session.evaluator.validate(expression) ?: return false
        out.error("$error")
        // FHIRPath strings are single-quoted; " delimits an identifier, so a
        // double-quoted URL fails with a message that never mentions quoting.
        if (expression.contains('"')) {
            out.text("  FHIRPath string literals use single quotes — try '…' rather than \"…\"")
        }
        return true
    }

    /**
     * One value on one line, shortened to fit: `[idx] Type  value`.
     *
     * @param label adds a resource column, for rows drawn from several resources.
     */
    fun valueRow(value: IBase, index: Int? = null, label: String? = null): Output.Line = Output.line {
        +"  "
        index?.let {
            index("[$it]")
            +" "
        }
        label?.let {
            label(it.padEnd(LABEL_WIDTH))
            +" "
        }
        type(session.render.typeOf(value))
        +"  "
        shrink(session.render.value(value), Output.Kind.PREVIEW, cutsEnd = true)
    }

    /**
     * One value in full: a primitive stays on the label's line, while anything
     * structured gets pretty-printed JSON underneath it.
     */
    fun expanded(label: String, value: IBase): List<Output.Line> {
        val type = session.render.typeOf(value)
        val lines = session.render.pretty(value)
        if (lines.size == 1) {
            return listOf(
                Output.line {
                    index(label)
                    type(type)
                    +"  "
                    value(lines.first())
                },
            )
        }
        val indent = " ".repeat(label.length + 2)
        return listOf(Output.line { index(label); type(type) }) + lines.map { line -> Output.line { +indent; json(line) } }
    }

    // ---------------------------------------------------------------------- pools

    /** `pool`, or `pool specimen` once there are several to tell apart. */
    fun poolName(): String =
        if (session.pools.size > 1) "pool ${session.current.name}" else "pool"

    /** ` (pool specimen)`, once there are several pools to tell apart. */
    fun inPool(): String =
        if (session.pools.size > 1) " (pool ${session.current.name})" else ""

    /** [header], and under it any files that could not be read, held back past the row limit. */
    fun withProblems(out: Output, header: String, problems: List<String>) {
        if (problems.isEmpty()) {
            out.text(header)
            return
        }
        out.listing(listOf(Output.text(header)), problems.map { problem -> Output.line { +"  "; error("! $problem") } })
    }

    /**
     * [resource]'s label as a row shows it: without its bundle when that is the one
     * scoped to, which the prompt already names. `:more` and lookups keep the whole
     * label, and `:focus Patient/p1` finds it in the scoped bundle.
     */
    fun shown(resource: PooledResource): String = shown(resource.label, resource.part)

    /** [focus]'s label as the prompt shows it; see [shown]. A focus in another bundle keeps its own. */
    fun shown(focus: Focus): String = shown(focus.label, focus.resource.part)

    private fun shown(label: String, part: String?): String {
        val scoped = session.current.meta?.scoped
        return if (scoped != null && part == scoped) label.removePrefix("$scoped:") else label
    }

    /** How `:open` and its completion read a path argument; see [GitSource.revisionSpec]. */
    fun gitSpecOf(token: String): Pair<String, String>? =
        GitSource.revisionSpec(token) { Files.exists(Paths.get(expandHome(it))) }

    fun expandHome(path: String): String =
        if (path.startsWith("~")) System.getProperty("user.home") + path.drop(1) else path

    companion object {
        const val UNLIMITED = 0
        const val LABEL_WIDTH = 28
        const val PROMPT_WIDTH = 48
        /** A scoped bundle's name in the prompt; `:scope` alone prints it whole. */
        const val PROMPT_BUNDLE_WIDTH = 24
        /** Used until the terminal reports its size; a dumb terminal never does. */
        const val DEFAULT_ROW_LIMIT = 20
        const val DEFAULT_COLUMNS = 100
    }
}
