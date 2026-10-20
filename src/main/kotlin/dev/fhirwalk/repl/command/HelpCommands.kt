package dev.fhirwalk.repl.command

import dev.fhirwalk.repl.Output

/**
 * `:help`, the commands, and `:manual`, how they fit together: kept apart so the
 * command list is not scrolled away by the prose after it. The text is [manual]'s.
 *
 * @param aliases each command's other spellings, so `:help :q` finds `:quit`.
 */
internal class HelpCommands(private val manual: Manual, private val aliases: () -> Map<String, String>) : CommandGroup {

    override val commands = listOf(
        command(":help", ":?") { argument, out -> if (argument.isBlank()) help(out) else helpOn(argument, out) },
        command(":manual") { argument, out -> if (argument.isBlank()) topics(out) else topic(argument, out) },
    )

    private fun help(out: Output) {
        out.text("Type a FHIRPath expression, or a command:")
        val width = manual.commands.maxOf { it.usage.length } + 4
        for (group in manual.groups) {
            out.text("")
            out.line { hint(group.title) }
            group.commands.forEach { command -> out.line { usage(command, width); +command.summary } }
        }
        out.text("")
        out.hint(":help <command> for more on one, :manual for how they fit together")
    }

    /** `:help focus`, `:help :focus`, `:help :q`: one command, and where the manual explains it. */
    private fun helpOn(argument: String, out: Output) {
        val typed = if (argument.startsWith(":")) argument else ":$argument"
        val command = manual.command(aliases()[typed] ?: typed) ?: run {
            out.text("no command $typed — :help lists them")
            return
        }
        out.line { usage(command, 0) }
        out.text("  ${command.summary}")
        command.details?.let { details ->
            out.text("")
            details.lines().forEach { out.text("  $it") }
        }
        command.topic?.let { topic ->
            out.text("")
            out.hint("more in :manual $topic")
        }
    }

    private fun topics(out: Output) {
        out.text("The manual, one topic at a time:")
        val width = manual.topics.maxOf { it.name.length } + 2
        manual.topics.forEach { topic -> out.line { +"  "; label(topic.name.padEnd(width)); +topic.title } }
        out.text("")
        out.hint(":manual <topic> prints one, :manual all the lot")
    }

    private fun topic(argument: String, out: Output) {
        val shown = if (argument.equals("all", ignoreCase = true)) {
            manual.topics
        } else {
            listOf(
                manual.topic(argument) ?: run {
                    out.text("no topic $argument — :manual lists them")
                    return
                },
            )
        }
        shown.forEachIndexed { idx, topic ->
            if (idx > 0) out.text("")
            out.line { label(topic.title) }
            topic.text.forEach { out.text(it) }
        }
    }

    /** `  :load <path>`, the name picked out, padded to [width]. */
    private fun Output.LineBuilder.usage(command: Manual.CommandDoc, width: Int) {
        +"  "
        label(command.name)
        +(if (command.args.isEmpty()) "" else " ${command.args}").padEnd(maxOf(0, width - command.name.length))
    }
}
