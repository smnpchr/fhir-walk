package dev.fhirwalk.repl.command

import dev.fhirwalk.repl.Output

/** How much of a listing is printed: `:limit` sets it, `:more` prints the rest. */
internal class DisplayCommands(private val state: ReplState) : CommandGroup {

    override val commands = listOf(
        command(":more") { _, out -> more(out) },
        command(":limit", run = ::limit),
    )

    private fun more(out: Output) {
        val listing = state.lastListing ?: run {
            out.text("nothing listed yet")
            return
        }
        out.whole(listing)
    }

    private fun limit(argument: String, out: Output) {
        when {
            argument.isBlank() ->
                out.text(
                    "rows per listing: " + if (state.rowLimit <= 0) "unlimited" else state.rowLimit.toString(),
                )
            argument.equals("off", ignoreCase = true) || argument == "0" -> {
                state.rowLimit = ReplState.UNLIMITED
                state.limitSet = ReplState.UNLIMITED
                out.text("row limit off — listings print in full and the terminal scrolls")
            }
            else -> {
                val rows = argument.toIntOrNull()
                if (rows == null || rows < 1) {
                    out.text("usage: :limit <rows|off>")
                } else {
                    state.rowLimit = rows
                    state.limitSet = rows
                    out.text("rows per listing: $rows")
                }
            }
        }
    }
}
