package dev.fhirwalk.repl.command

import dev.fhirwalk.repl.Output

/**
 * One `:command`, as the REPL dispatches it.
 *
 * Only what the code needs is here. How the command is written, what it does and
 * where the manual explains it are text, kept in `help.yaml` under [name] and read
 * by [Manual]; every command must have an entry there, and every entry a command.
 */
internal interface Command {
    /** What is typed, colon included: `:load`. */
    val name: String

    /** Other spellings that run it, which `:help` does not list: `:q` for `:quit`. */
    val aliases: List<String> get() = emptyList()

    /** Whether the REPL ends once it has run. */
    val exits: Boolean get() = false

    /**
     * Does what the line asks, saying so into [out] -- never to the terminal, which
     * is the [Printer]'s. The workspace's state is saved after it, even when it throws.
     *
     * @param argument everything after the name, trimmed; blank when there is none.
     */
    fun run(argument: String, out: Output)
}

/** A command whose [run] is a lambda, for the groups that define several. */
internal fun command(
    name: String,
    vararg aliases: String,
    exits: Boolean = false,
    run: (argument: String, out: Output) -> Unit,
): Command = object : Command {
    override val name = name
    override val aliases = aliases.toList()
    override val exits = exits
    override fun run(argument: String, out: Output) = run(argument, out)
}

/**
 * Commands that belong together and share their helpers: the pool commands, the git
 * ones, and so on. Each group reaches the REPL's state through a [ReplState], and the
 * groups it builds on through its constructor.
 */
internal interface CommandGroup {
    val commands: List<Command>
}
