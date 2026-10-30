package dev.fhirwalk.repl.command

import dev.fhirwalk.complete.FhirPathCompleter
import dev.fhirwalk.eval.Focus
import dev.fhirwalk.eval.Outcome
import dev.fhirwalk.pool.GitSource
import dev.fhirwalk.pool.GitSourceException
import dev.fhirwalk.pool.ResourceHistory
import dev.fhirwalk.repl.GitPool
import dev.fhirwalk.repl.MetaPool
import dev.fhirwalk.repl.Output
import dev.fhirwalk.repl.Pool
import dev.fhirwalk.repl.SavedPool
import dev.fhirwalk.repl.Session
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.WeakHashMap

/** A git pool: opening one at a revision, moving it along its line, and reading its history. */
internal class GitCommands(
    private val state: ReplState,
    private val focus: FocusCommands,
    private val metas: MetaCommands,
) : CommandGroup {

    private val session: Session get() = state.session

    override val commands = listOf(
        command(":log", run = ::gitLog),
        command(":at") { argument, out ->
            if (argument.isBlank()) {
                out.text("usage: :at <rev|date>  —  a branch, tag, id, branch~2, or 2026-03-11 for the line as of then")
            } else {
                at(argument, out)
            }
        },
        command(":step", run = ::step),
        command(":trace", run = ::trace),
        command(":delta") { _, out -> delta(out) },
        command(":scope", run = ::scope),
    )

    /**
     * Makes the current pool [spec]'s directory at a revision: [at] when it still
     * exists, else the tip of the line [spec] names.
     *
     * @return false when it could not be opened; what went wrong has been said.
     */
    fun openGit(spec: String, at: String?, deltaScope: Boolean, out: Output, gitDir: Path? = null): Boolean {
        val (path, revision) = GitSource.split(spec) ?: run {
            out.error("$spec names no revision — dir@rev, e.g. registry/in/import@branch/out")
            return false
        }
        val source = try {
            GitSource.open(Paths.get(state.expandHome(path)).toAbsolutePath().normalize())
        } catch (e: GitSourceException) {
            out.error("${e.message}")
            return false
        } catch (e: Exception) {
            // A directory deleted while it is being opened.
            out.error("cannot open $path: ${e.message ?: e.javaClass.simpleName}")
            return false
        }
        if (gitDir != null && source.gitDir != gitDir) {
            source.close()
            out.error("${source.path} is in $gitDir no longer, but in ${source.gitDir}")
            return false
        }
        // `dir@` follows whatever branch HEAD is on; the line saved is that branch, so
        // the pool keeps following it even after HEAD moves elsewhere.
        val line = revision.ifEmpty {
            source.headLine()?.also { head ->
                // A detached HEAD has no branch to follow, so the pool stays at its commit.
                if (!source.isBranch(head)) {
                    out.hint("  HEAD is detached — pinned at ${GitSource.label(head)}; :at <branch> to follow one")
                }
            } ?: run {
                source.close()
                out.error("${source.path} is in a repository with no commits yet")
                return false
            }
        }
        val left = at?.let { id ->
            source.resolve(id) ?: null.also {
                out.error("${id.take(7)} is gone from ${source.path} — opening the tip of $line instead")
            }
        }
        val start = left ?: source.resolve(line) ?: run {
            source.close()
            out.error("no commit $line in ${source.path}")
            return false
        }
        val git = GitPool(source, line, session.importer, session.pool)
        // Attached only once loaded: a pool whose revision could not be read must not
        // look like a git pool to the prompt or to the saved state.
        val report = try {
            git.load(start)
        } catch (e: Exception) {
            source.close()
            out.error("cannot read ${source.path} at ${start.short}: ${e.message ?: e.javaClass.simpleName}")
            return false
        }
        session.current.git = git
        session.current.unavailable = null
        git.deltaScope = deltaScope
        state.withProblems(out, "${state.poolName()} at ${revision(git)}: ${report.summary()}", report.problems)
        return true
    }

    /**
     * What [checkSources] remembers of an unavailable pool: the tip it was last tried
     * at, so one that still fails is not read again before every line, and the
     * repository it was emptied from, so that while that one is gone an enclosing
     * repository is not taken for it. By the pool itself, not its name: a pool closed
     * and opened again under the same name starts afresh.
     */
    private class Watch(var triedAt: String? = null, val gitDir: Path? = null)

    private val watched = WeakHashMap<Pool, Watch>()

    /**
     * For a pool made unavailable because [openGit] just failed: what it was tried at,
     * so the next check does not say it is back and fail the same way again.
     */
    fun unavailable(pool: Pool, saved: SavedPool) {
        pool.unavailable = saved
        watched[pool] = Watch(triedAt = saved.source?.let { tipOf(it, null) })
    }

    /**
     * Notices a git pool's repository going and coming back, as a test run that
     * deletes and rebuilds it does between two lines. A pool whose directory or
     * commit has gone is emptied, rather than answering from what is no longer on
     * disk, and kept as [Pool.unavailable]; an unavailable pool opens again once its
     * line has a commit -- at the one it was at if that is still there, else at the
     * tip. A pool whose commit is still there never moves.
     *
     * @return whether any pool changed, so the workspace state is saved.
     */
    fun checkSources(out: Output): Boolean {
        val current = session.current.name
        var changed = false
        try {
            for (pool in session.pools) {
                session.switchTo(pool.name)
                // A repository half deleted or half written can fail any read; the
                // check must never take the session down with it.
                val moved = try {
                    check(pool, out)
                } catch (e: Exception) {
                    out.error("cannot check ${state.poolName()}: ${e.message ?: e.javaClass.simpleName}")
                    false
                }
                if (!moved) continue
                changed = true
                // Rows picked or listed from the current pool no longer name what it holds.
                if (pool.name == current) {
                    state.lastPicks = null
                    state.lastListing = null
                }
            }
        } finally {
            session.switchTo(current)
        }
        return changed
    }

    /** @return whether the pool was emptied or opened again. */
    private fun check(pool: Pool, out: Output): Boolean {
        val git = pool.git
        val replaced = git != null && !git.readable
        if (replaced) empty(pool, git!!)
        val saved = pool.unavailable ?: return replaced
        if (reopen(pool, saved, if (replaced) "was replaced" else "is back", out)) return true
        if (replaced) {
            val gitDir = watched[pool]?.gitDir
            val why = when {
                !Files.isDirectory(git!!.source.path) -> "${git.source.path} is gone"
                gitDir != null && !Files.isDirectory(gitDir) -> "$gitDir is gone"
                tipOf(git.spec, gitDir) == null -> "${git.source.path} holds no commit of ${git.lineLabel} now"
                // The reopen said why; it is tried again when the line moves.
                else -> null
            }
            out.error("${why?.let { "$it — " }.orEmpty()}${state.poolName()} emptied, to open again once it is back")
        }
        return replaced
    }

    private fun empty(pool: Pool, git: GitPool) {
        pool.unavailable = SavedPool(
            pool.name,
            focus = focus.focusSteps(pool.focus),
            source = git.spec,
            at = git.commit.id.name,
            deltaScope = git.deltaScope,
        )
        pool.git = null
        pool.focus = null
        pool.pool.clear()
        git.source.close()
        watched[pool] = Watch(gitDir = git.source.gitDir)
    }

    private fun reopen(pool: Pool, saved: SavedPool, how: String, out: Output): Boolean {
        val spec = saved.source ?: return false
        val watch = watched.getOrPut(pool) { Watch() }
        val tip = tipOf(spec, watch.gitDir) ?: return false
        if (watch.triedAt == tip) return false
        watch.triedAt = tip
        out.text("${GitSource.split(spec)?.first ?: spec} $how")
        if (!openGit(spec, saved.at, saved.deltaScope, out, watch.gitDir)) return false
        watched.remove(pool)
        focus.restoreFocus(saved.focus, out)
        return true
    }

    /**
     * The commit [spec]'s line is at now, or null while there is none to open: cheap,
     * as it reads only refs. With [gitDir], only in that repository.
     */
    private fun tipOf(spec: String, gitDir: Path?): String? {
        val (path, line) = GitSource.split(spec) ?: return null
        val dir = runCatching { Paths.get(state.expandHome(path)) }.getOrNull() ?: return null
        if (!Files.isDirectory(dir)) return null
        return runCatching {
            GitSource.open(dir).use { source ->
                if (gitDir != null && source.gitDir != gitDir) return@use null
                source.resolve(line.ifEmpty { source.headLine() ?: return@use null })?.id?.name
            }
        }.getOrNull()
    }

    /**
     * A directory loads from disk as it is, uncommitted changes included, even inside a
     * repository: switching to git silently would change what the pool holds. So the
     * history is only pointed out.
     */
    fun gitHint(path: String, out: Output) {
        val dir = runCatching { Paths.get(state.expandHome(path)) }.getOrNull() ?: return
        if (!Files.isDirectory(dir)) return
        val head = GitSource.historyAt(dir) ?: return
        val where = if (head.startsWith("HEAD ")) head else "on $head"
        out.hint("  in a git repository, $where — :open <name> ${path.trimEnd('/')}@ opens its history; TAB after the @ lists revisions")
    }

    /**
     * The current git pool's line, newest first, for TAB after `:at`: the time that
     * names each commit and its short id, the commit it sits at marked as `:log`
     * marks it, what it changed and its subject alongside.
     */
    fun commitChoices(): List<FhirPathCompleter.CommitChoice> {
        val git = session.current.git ?: return emptyList()
        val commits = runCatching { git.history(MAX_CHOICES) }.getOrDefault(emptyList())
        val names = state.commitTimes.names(commits)
        val width = names.maxOfOrNull { it.length } ?: 0
        return commits.zip(names) { commit, name ->
            val marker = if (commit.id == git.commit.id) "*" else " "
            FhirPathCompleter.CommitChoice(commit.short, name, "$marker ${name.padEnd(width)}  ${commit.short}", commit.subject) {
                runCatching { git.changedFiles(commit) }.getOrNull()
            }
        }
    }

    /** `3f2a1c0 on branch/out, 2026-10-17 14:02 sync`. */
    private fun revision(git: GitPool): String =
        "${git.commit.short} on ${git.lineLabel}, ${COMMIT_TIME.format(git.commit.time)} ${git.commit.subject}"

    /** `Δ12: 2 added, 10 modified`. */
    private fun deltaSummary(git: GitPool): String {
        val delta = git.delta
        val parts = listOf(delta.added.size to "added", delta.modified.size to "modified", delta.deleted.size to "deleted")
            .filter { it.first > 0 }
            .joinToString(", ") { (count, what) -> "$count $what" }
        return "Δ${delta.size}" + if (parts.isEmpty()) ": nothing changed here" else ": $parts"
    }

    /** The current pool's git side, or null after saying why there is none. */
    private fun gitPool(out: Output): GitPool? = session.current.git ?: run {
        out.text("${state.poolName()} is not a git pool — :open <name> <dir>@<rev> opens one")
        null
    }

    private fun gitLog(argument: String, out: Output) {
        val git = gitPool(out) ?: return
        val words = argument.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val (skips, rest) = words.partition { it.startsWith("+") }
        val (limits, labels) = rest.partition { it.toIntOrNull() != null }
        val skip = skips.singleOrNull()?.removePrefix("+")?.toIntOrNull()?.takeIf { it >= 0 }
        val limit = limits.singleOrNull()?.toIntOrNull()?.takeIf { it > 0 }
        if (skips.size > 1 || limits.size > 1 || labels.size > 1 ||
            (skips.isNotEmpty() && skip == null) || (limits.isNotEmpty() && limit == null)
        ) {
            out.text(LOG_USAGE)
            return
        }
        val page = limit ?: DEFAULT_LOG
        val skipped = skip ?: 0
        labels.singleOrNull()?.let { label ->
            resourceLog(git, label, page, skipped, out)
            return
        }
        // A damaged repository throws from any read, as in a move: a history that
        // cannot be walked is an error, a commit whose changes cannot be read a Δ?.
        // One more than the page, to know whether there is an older one.
        val read = try {
            git.history(minOf(Int.MAX_VALUE.toLong(), skipped.toLong() + page + 1).toInt())
        } catch (e: Exception) {
            out.error("cannot read ${git.source.path}: ${e.message ?: e.javaClass.simpleName}")
            return
        }
        if (read.isEmpty()) {
            out.text("${git.lineLabel} names no commit any more")
            return
        }
        val commits = read.drop(skipped).take(page)
        if (commits.isEmpty()) {
            out.text("only ${read.size} commit(s) on ${git.lineLabel}")
            return
        }
        var unreadable = 0
        out.listing(
            header = listOf(Output.text("${git.lineLabel} in ${git.source.path}, newest first")),
            rows = commits.map { commit ->
                val count = runCatching { git.changedFiles(commit) }.getOrNull()
                if (count == null) unreadable++
                commitRow(git, commit) {
                    count("Δ${count ?: "?"}".padStart(6))
                    +"  "
                    shrink(commit.subject)
                }
            },
        )
        if (unreadable > 0) {
            out.line { error("  Δ? — what $unreadable commit(s) changed cannot be read from ${git.source.path}") }
        }
        if (commits.none { it.id == git.commit.id }) {
            out.hint("  (at ${git.commit.short}, which is not among these)")
        }
        if (read.size.toLong() > skipped.toLong() + page) {
            val size = if (page == DEFAULT_LOG) "" else "$page "
            out.hint("  older: :log $size+${skipped + page}")
        }
    }

    /** `* 3f2a1c0  2026-10-17 14:02  ` and then [rest], marked when the pool is at the commit. */
    private fun commitRow(git: GitPool, commit: GitSource.Commit, rest: Output.LineBuilder.() -> Unit): Output.Line = Output.line {
        +(if (commit.id == git.commit.id) "* " else "  ")
        index(commit.short)
        +"  ${COMMIT_TIME.format(commit.time)}  "
        rest()
    }

    /** What a resource's history could not be read for, or null when it can be. */
    private fun untraceable(label: String): String? = when {
        ResourceHistory.isLabel(label) -> null
        else -> "$label is not a label a history can follow — Type/id, as :pool and :delta show them"
    }

    /** `:log Specimen/s1`: the commits of the line that added, changed or deleted one resource. */
    private fun resourceLog(git: GitPool, label: String, page: Int, skipped: Int, out: Output) {
        untraceable(label)?.let {
            out.text(it)
            return
        }
        // One more than the page, to know whether there is an older one.
        val problems = mutableListOf<String>()
        val read = try {
            git.history(label) { problems += it }.take(minOf(Int.MAX_VALUE.toLong(), skipped.toLong() + page + 1).toInt()).toList()
        } catch (e: Exception) {
            out.error("cannot read ${git.source.path}: ${e.message ?: e.javaClass.simpleName}")
            return
        }
        problems.forEach { out.error(it) }
        if (read.isEmpty()) {
            out.text("no commit on ${git.lineLabel} added, changed or deleted $label")
            return
        }
        val changes = read.drop(skipped).take(page)
        if (changes.isEmpty()) {
            out.text("only ${read.size} commit(s) on ${git.lineLabel} touched $label")
            return
        }
        out.listing(
            header = listOf(Output.text("$label on ${git.lineLabel}, newest first")),
            rows = changes.map { change ->
                commitRow(git, change.commit) {
                    count(change.kind.name.lowercase().padEnd(8))
                    +"  "
                    shrink(change.commit.subject)
                }
            },
        )
        if (read.size.toLong() > skipped.toLong() + page) {
            val size = if (page == DEFAULT_LOG) "" else "$page "
            out.hint("  older: :log $label $size+${skipped + page}")
        }
    }

    /**
     * `:trace Specimen/s1 type.coding.code`: the expression's answer after each commit
     * that changed the resource, newest first, with what it was before -- a row only
     * where the answer changed, or the resource came or went. Read from the history
     * alone, so the pool stays where it is. With a focus, `:trace <expr>` traces the
     * focused resource, or the focused element by the steps that reached it.
     *
     * With no expression the answer is the resource itself (or the focused element),
     * so every commit that changed its content is a row. Each row is a preview;
     * `:more` prints every answer whole, as pretty-printed JSON.
     */
    private fun trace(argument: String, out: Output) {
        val git = gitPool(out) ?: return
        val first = argument.substringBefore(' ')
        val (label, expression) = if (ResourceHistory.isLabel(first)) {
            first to argument.substringAfter(' ', "").trim().ifBlank { null }
        } else {
            val focus = session.focus ?: run {
                out.text(TRACE_USAGE)
                return
            }
            val path = focusedPath(focus, argument) ?: run {
                out.text("${focus.label} was not reached by steps from ${focus.resource.label} — :trace ${focus.resource.label} <expr>")
                return
            }
            focus.resource.label to path.ifBlank { null }
        }
        untraceable(label)?.let {
            out.text(it)
            return
        }
        if (expression != null) {
            if (state.reportSyntaxError(expression, out)) return
            // The history is of one resource; what it references is read from the pool,
            // which is at another revision.
            if (Regex("""\bresolve\s*\(""").containsMatchIn(expression)) {
                out.text("resolve() would read the pool at ${git.commit.short}, not the revision traced — trace the reference itself")
                return
            }
        }
        val problems = mutableListOf<String>()
        val changes = try {
            git.history(label) { problems += it }.toList()
        } catch (e: Exception) {
            out.error("cannot read ${git.source.path}: ${e.message ?: e.javaClass.simpleName}")
            return
        }
        // Before the rows: a resource that seems to come and go may be a file that broke.
        problems.forEach { out.error(it) }
        if (changes.isEmpty()) {
            out.text("no commit on ${git.lineLabel} added, changed or deleted $label")
            return
        }
        // Oldest first, so each answer can say what it replaced.
        var before: String? = null
        var beforeExactly: String? = null
        val traced = mutableListOf<Traced>()
        for (change in changes.asReversed()) {
            val outcome = change.resource?.let { resource ->
                if (expression == null) Outcome.Values(listOf(resource)) else session.evaluator.evaluate(resource, expression)
            }
            val answer = when (outcome) {
                null -> "deleted"
                is Outcome.Failure -> "! ${outcome.message}"
                is Outcome.Values -> outcome.values.joinToString(", ") { session.render.value(it) }.ifEmpty { "{}" }
            }
            val exactly = exactly(outcome)
            // The history already holds only commits that changed the resource's
            // content, so with no expression every one is a row. An answer is
            // compared whole: its one-line form runs whitespace together, which a
            // string's value may differ by.
            if (expression != null && exactly == beforeExactly && change.kind != GitSource.Kind.ADDED) continue
            traced += Traced(change, outcome, answer, was = before)
            before = answer
            beforeExactly = exactly
        }
        traced.reverse()
        val what = expression?.let { "  $it" }.orEmpty()
        out.listing(
            header = listOf(Output.text("$label$what on ${git.lineLabel}, newest first — ${traced.size} change(s) in ${changes.size} commit(s) that touched it")),
            rows = traced.map { row ->
                commitRow(git, row.change.commit) {
                    if (expression == null) {
                        // A resource's answer is the whole of it: what happened says more than what it was.
                        count(row.kind.padEnd(8))
                        if (row.outcome != null) {
                            +"  "
                            shrink(row.answer, Output.Kind.PREVIEW, cutsEnd = true)
                        }
                    } else {
                        when (row.outcome) {
                            null -> hint(row.answer)
                            is Outcome.Failure -> shrink(row.answer, Output.Kind.ERROR)
                            else -> shrink(row.answer)
                        }
                        if (row.came) hint("  (added)") else row.was?.let { shrink("  (was $it)", Output.Kind.HINT) }
                    }
                }
            },
            // Each answer whole under its commit; what it was is the row below.
            full = traced.flatMap { row ->
                listOf(commitRow(git, row.change.commit) { count(row.kind) }) +
                    when (val outcome = row.outcome) {
                        null -> emptyList()
                        is Outcome.Failure -> listOf(Output.line { +"      "; error(row.answer) })
                        is Outcome.Values ->
                            if (outcome.values.isEmpty()) listOf(Output.text("      {}")) else outcome.values.flatMap { state.expanded("      ", it) }
                    }
            },
        )
    }

    /**
     * [outcome] as a trace compares it: every value whole, whitespace in its strings
     * included, and never equal to a failure or a deletion.
     */
    private fun exactly(outcome: Outcome?): String = when (outcome) {
        null -> "-"
        is Outcome.Failure -> "!${outcome.message}"
        is Outcome.Values -> "=" + outcome.values.joinToString("\u0000") { session.render.pretty(it).joinToString("\n") }
    }

    /**
     * A commit in a trace: what [change] made the answer, as [answer] reads it on one
     * line, and what it [was] before, if anything.
     */
    private class Traced(val change: ResourceHistory.Change, val outcome: Outcome?, val answer: String, val was: String?) {
        val came: Boolean get() = change.kind == GitSource.Kind.ADDED

        /** `added`, `modified`, `deleted`, as `:log <label>` says. */
        val kind: String get() = change.kind.name.lowercase()
    }

    /**
     * [expression] as the focused resource would take it: the focus's own steps, and
     * the expression run on what they reach -- through `select()`, so a union or a
     * literal applies to the element rather than binding to the last step. Null for
     * a focus not reached by steps from its resource.
     */
    private fun focusedPath(focus: Focus, expression: String): String? {
        if (focus.isResource) return expression
        val steps = focus.label.takeIf { it.startsWith("${focus.resource.label}.") }
            ?.removePrefix("${focus.resource.label}.") ?: return null
        val relative = focus.step(expression)
        return if (relative.isEmpty()) steps else "($steps).select($relative)"
    }

    private fun at(revision: String, out: Output) {
        val git = gitPool(out) ?: return
        moved(git, git.at(revision), out)
    }

    private fun step(argument: String, out: Output) {
        val git = gitPool(out) ?: return
        val by = when {
            argument.isBlank() -> -1
            Regex("[+-]\\d{1,6}").matches(argument) -> argument.removePrefix("+").toInt()
            else -> {
                out.text("usage: :step [-n|+n]  —  -1 is one commit back, +1 one forward along ${git.lineLabel}")
                return
            }
        }
        if (by == 0) {
            out.text("at ${git.commit.short}")
            return
        }
        moved(git, git.step(by), out)
    }

    /**
     * Moving is how you ask what a run changed, so the scope goes to the delta. The
     * focus is found again in the new revision by the steps that reached it.
     */
    private fun moved(git: GitPool, move: GitPool.Move, out: Output) {
        when (move) {
            is GitPool.Move.Refused -> out.text(move.message)
            is GitPool.Move.Failed -> out.error(move.message)
            is GitPool.Move.Moved -> {
                val steps = focus.focusSteps(session.focus)
                session.focus = null
                state.lastPicks = null
                git.deltaScope = true
                move.note?.let { out.text(it) }
                state.withProblems(out, "${state.poolName()} at ${revision(git)} — ${deltaSummary(git)}", move.report.problems)
                focus.restoreFocus(steps, out)
            }
        }
    }

    private fun delta(out: Output) {
        val git = gitPool(out) ?: return
        val delta = git.delta
        val against = delta.parent?.let { "against ${it.short}" } ?: "against nothing: the first commit"
        val header = "${git.commit.short} $against — ${deltaSummary(git)}"
        if (delta.size == 0) {
            out.text(header)
            return
        }
        fun row(sign: String, label: String, origin: String?) = Output.line {
            +"  $sign "
            label(label.padEnd(ReplState.LABEL_WIDTH))
            origin?.let { hint(" ($it)") }
        }
        out.listing(
            listOf(Output.text(header)),
            delta.added.map { row("+", it.label, it.origin) } +
                delta.modified.map { row("~", it.label, it.origin) } +
                delta.deleted.map { row("-", it, null) },
        )
    }

    private fun scope(argument: String, out: Output) {
        session.current.meta?.let {
            memberScope(it, argument, out)
            return
        }
        val git = session.current.git
        when (argument.lowercase()) {
            "" -> out.text(
                if (git?.deltaScope == true) {
                    "scope: the delta, ${session.pool.inScope().size} of ${session.pool.size} resource(s) — :scope all for everything"
                } else {
                    "scope: all ${session.pool.size} resource(s)" + if (git != null) " — :scope delta for what ${git.commit.short} changed" else ""
                },
            )
            "delta" -> {
                val pool = gitPool(out) ?: return
                pool.deltaScope = true
                out.text("scope: the delta, ${session.pool.inScope().size} resource(s) — ${deltaSummary(pool)}")
            }
            "all" -> {
                git?.deltaScope = false
                out.text("scope: all ${session.pool.size} resource(s)")
            }
            else -> out.text("usage: :scope [delta|all]")
        }
    }

    /**
     * In a meta pool the scope is a bundle: `:scope specimen-r1`, and `:scope all` for
     * every one, read again first so that every one includes what arrived since. A
     * bundle named is not read again: its name is from the last read, and could
     * change with it.
     */
    private fun memberScope(meta: MetaPool, argument: String, out: Output) {
        val pool = session.pool
        when {
            argument.isBlank() -> out.text(
                meta.scoped?.let { "scope: bundle $it, ${pool.inScope().size} of ${pool.size} resource(s) — :scope all for every bundle" }
                    ?: "scope: all ${meta.members.size} bundle(s), ${pool.size} resource(s) — :scope <bundle> for one, :members lists them",
            )
            argument.equals("all", ignoreCase = true) -> {
                metas.refresh(session.current, meta, out)
                meta.scope(pool, null)
                out.text("scope: all ${meta.members.size} bundle(s), ${pool.size} resource(s)")
            }
            meta.members.none { it.name == argument } ->
                out.text("no bundle $argument in ${state.poolName()} — :members lists them; a delta belongs to a git pool")
            else -> {
                meta.scope(pool, argument)
                out.text("scope: bundle $argument, ${pool.inScope().size} resource(s) — :scope all for every bundle")
            }
        }
    }

    private companion object {
        const val DEFAULT_LOG = 20
        /** Commits searched for TAB after `:at`: enough to narrow by id in a long history. */
        const val MAX_CHOICES = 2000
        const val LOG_USAGE = "usage: :log [label] [n] [+skip]  —  :log 10 +20 is the 21st to 30th commit back; :log Specimen/s1 those that changed it"
        const val TRACE_USAGE = "usage: :trace <label> [expr]  —  or :trace [expr] with a focus, e.g. :trace Specimen/s1 type.coding.code; " +
            "with no expression, the whole resource"

        val COMMIT_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
    }
}
