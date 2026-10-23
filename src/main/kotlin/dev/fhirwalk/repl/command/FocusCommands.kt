package dev.fhirwalk.repl.command

import dev.fhirwalk.eval.Focus
import dev.fhirwalk.eval.FocusResolver.Resolution
import dev.fhirwalk.repl.Output
import dev.fhirwalk.repl.Session

/** `:focus`, and how a focus is written down and found again: in the saved state, and after a git move. */
internal class FocusCommands(private val state: ReplState) : CommandGroup {

    private val session: Session get() = state.session

    override val commands = listOf(command(":focus", run = ::focus))

    /**
     * A label or `#index` picks a resource; anything else is an expression, run
     * against the current focus (or across the pool when there is none), whose one
     * result becomes the focus. Each new focus remembers the last, for `:focus ..`.
     * The rules live in [dev.fhirwalk.eval.FocusResolver]; this only reports.
     */
    private fun focus(argument: String, out: Output) {
        val current = session.focus
        if (argument.isBlank()) {
            session.focus = null
            // Clears the lot: pool-wide rows would otherwise come back into play.
            state.lastPicks = null
            out.text("focus cleared — expressions now run across the pool")
            return
        }
        if (argument == "..") {
            session.focus = current?.parent
            when {
                current == null -> out.text("nothing in focus")
                current.parent == null -> out.text("focus cleared — expressions now run across the pool")
                else -> announceFocus(current.parent, out)
            }
            return
        }
        val lastPicks = state.lastPicks
        val resolution = session.focusResolver.resolve(argument, current, lastPicks)
        // An expression here is an evaluation too, and not a recorded one; a label,
        // #index or pick runs nothing and leaves the last expression nameable.
        if (resolution.evaluated) state.nameable = null
        state.lastPicks = when (resolution) {
            is Resolution.Several -> resolution.picks
            // Picking, or failing to, leaves the same rows on offer for the next pick.
            is Resolution.CannotPick -> lastPicks
            is Resolution.Focused -> lastPicks.takeIf { resolution.picked }
            else -> null
        }
        when (resolution) {
            is Resolution.Focused -> {
                session.focus = resolution.focus
                announceFocus(resolution.focus, out)
            }
            is Resolution.NotInPool -> out.text("not in pool: ${resolution.argument}")
            is Resolution.CannotPick -> out.text(resolution.reason)
            is Resolution.Invalid ->
                out.error("not in pool, and not a valid expression: ${resolution.message}")
            is Resolution.Failed -> out.error("${resolution.message}")
            is Resolution.Nothing -> {
                // The hint points at :why, which should not need the expression retyped.
                state.lastExpression = argument
                val label = if (resolution.couldBeLabel) "not a pool label, and " else ""
                // A label there names its bundle, which is easily missed when typing one.
                val meta = if (resolution.couldBeLabel && session.current.meta != null) {
                    "; in a meta pool a label names its bundle, <bundle>:$argument — :members lists them"
                } else {
                    ""
                }
                out.text("$label$argument yields nothing — :why$meta")
            }
            is Resolution.Several -> {
                val results = resolution.picks.results
                val byResource = resolution.owners > 1
                val counted = if (byResource) "from ${resolution.owners} resources" else ""
                out.listing(
                    header = listOf(
                        Output.text(
                            listOf("${results.size} values", counted).filter { it.isNotEmpty() }.joinToString(" ") +
                                "; focus needs one — pick it with  :focus [n]",
                        ),
                    ),
                    rows = results.mapIndexed { idx, found ->
                        state.valueRow(found.value, idx, found.owner.label.takeIf { byResource })
                    },
                    full = results.flatMapIndexed { idx, found ->
                        state.expanded("  [$idx] ${if (byResource) found.owner.label + "  " else ""}", found.value)
                    },
                )
            }
        }
    }

    /**
     * A listing of one, so `:more` straight after `:focus` shows the focused value
     * whole. A long element path is exactly when you want to see what it landed on.
     */
    private fun announceFocus(focus: Focus, out: Output) {
        val what = if (focus.isResource) focus.resource.origin else "${focus.typeName} in ${focus.resource.origin}"
        out.listing(
            header = listOf(Output.text("focus ${focus.label}  ($what)")),
            rows = listOf(state.valueRow(focus.node)),
            full = state.expanded("  ", focus.node),
        )
    }

    /** Follows [steps], as [focusSteps] wrote them, as far as they still reach one value. */
    fun restoreFocus(steps: List<String>, out: Output) {
        if (steps.isEmpty()) return
        var current: Focus? = null
        for (step in steps) {
            when (val resolution = session.focusResolver.resolve(step, current)) {
                is Resolution.Focused -> current = resolution.focus
                else -> {
                    out.error("focus restored only as far as ${current?.label ?: "the pool"} — $step no longer reaches one value")
                    break
                }
            }
        }
        session.focus = current
        current?.let { out.text("focus ${it.label}${state.inPool()}") }
    }

    /**
     * The `:focus` arguments that reach [focus] again, outermost first. A label is
     * its parent's plus the step that reached it, indexer included, so the step is
     * what follows the parent's label. A pool-wide element focus has no parent; it
     * goes by way of its resource, which `:focus ..` then steps back to.
     */
    fun focusSteps(focus: Focus?): List<String> =
        generateSequence(focus) { it.parent }.toList().asReversed().flatMap { step ->
            val parent = step.parent
            when {
                step.isResource -> listOf(step.resource.label)
                parent == null -> listOf(step.resource.label, stepAfter(step.resource.label, step.label))
                else -> listOf(stepAfter(parent.label, step.label))
            }
        }

    /**
     * What [label] adds to [base]. A step that adds nothing, such as `:focus $this`,
     * leaves the label as it was; saving the label itself would replay as an
     * expression, so the step is saved as the one that changes nothing.
     */
    private fun stepAfter(base: String, label: String): String =
        if (label.startsWith("$base.")) label.removePrefix("$base.") else "\$this"
}
