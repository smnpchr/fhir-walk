package dev.fhirwalk

import dev.fhirwalk.model.FhirVersion
import dev.fhirwalk.repl.Repl
import dev.fhirwalk.repl.Workspaces
import dev.fhirwalk.repl.command.Manual
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `:help` lists the commands and nothing else; `:help <command>` and `:manual` say
 * the rest. All of it comes from help.yaml, which must match the commands defined.
 */
class HelpTest {

    private val repl = Repl(FhirVersion.R4, emptyList(), Workspaces(null))

    private fun lines(line: String): List<String> = repl.outputOf(line).lines()

    private fun assertContains(lines: List<String>, expected: String) =
        assertTrue(lines.any { it.contains(expected) }, "expected \"$expected\" in:\n${lines.joinToString("\n")}")

    @Test
    fun `help lists every command once and ends at the list`() {
        val help = lines(":help")
        val listed = help.map { it.trim() }.filter { it.startsWith(":") && !it.startsWith(":help <") }.map { it.substringBefore(' ') }
        assertEquals(listed.distinct(), listed)
        assertTrue(listed.containsAll(listOf(":load", ":focus", ":trace", ":view", ":manual", ":quit")), "$listed")
        // The manual's prose is not in it any more.
        assertTrue(help.none { it.contains("Several pools can be open at once") }, help.joinToString("\n"))
        assertContains(help.takeLast(1), ":manual")
    }

    @Test
    fun `help on a command takes it with or without the colon, and by an alias`() {
        assertEquals(lines(":help :at"), lines(":help at"))
        assertContains(lines(":help at"), ":at <rev|date>")
        assertContains(lines(":help at"), "branch~2")
        assertContains(lines(":help at"), "more in :manual git")
        assertContains(lines(":help :q"), ":quit")
        assertContains(lines(":? load"), ":load <path>")
        assertContains(lines(":help nope"), "no command :nope")
        // Unquoted, the " #n" in it would start a YAML comment and cut it short.
        assertContains(lines(":help name-expr"), "or #n, for :view; alone, list")
        // In any case, as :manual and TAB take it.
        assertEquals(lines(":help load"), lines(":help LOAD"))
    }

    @Test
    fun `help on an old spelling says what it became`() {
        val help = lines(":help name")
        assertContains(help, ":name is now :name-expr")
        assertContains(help, ":name-expr [name [#n]]")
    }

    @Test
    fun `the shipped manual keeps its lines to the width it asks for`() {
        val manual = Manual.load()
        val long = manual.topics.flatMap { it.text } +
            manual.commands.flatMap { it.details?.lines().orEmpty().map { line -> "  $line" } }
        assertEquals(emptyList(), long.filter { it.length > 76 })
    }

    @Test
    fun `a manual that does not match the commands fails, saying which`() {
        val manual = Manual.parse(manual(":load", ":pool"))
        manual.requireDocuments(setOf(":load", ":pool"))
        val failure = assertFailsWith<IllegalStateException> { manual.requireDocuments(setOf(":load", ":quit")) }
        assertContains(listOf(failure.message.orEmpty()), "undocumented [:quit], no such command [:pool]")
    }

    @Test
    fun `a manual written wrongly fails when read`() {
        fun fails(yaml: String, expected: String) {
            val failure = assertFailsWith<IllegalArgumentException> { Manual.parse(yaml) }
            assertContains(listOf(failure.message.orEmpty()), expected)
        }
        fails(manual(":load", ":load"), ":load documented twice")
        fails(manual(":load") + topic("git") + topic("Git"), "topic git given twice")
        fails(manual(":load") + topic("all"), "cannot be called all")
        fails(manual(":load", topic = "nope"), ":load refers to no topic nope")
        // Unquoted, YAML reads [n] as a list: say so rather than that it is missing.
        fails(manual(":load").replace("args: \"\"", "args: [n]"), "args is not text")
    }

    private fun manual(vararg names: String, topic: String? = null): String =
        "commands:\n  - group: Pools\n    commands:\n" + names.joinToString("") { name ->
            "      - name: \"$name\"\n        args: \"\"\n        summary: does $name\n" +
                (topic?.let { "        topic: $it\n" } ?: "")
        } + "topics:\n"

    private fun topic(name: String): String =
        "  - name: $name\n    title: About $name\n    text: |\n      Some text.\n"

    @Test
    fun `the manual lists its topics and prints one, or all`() {
        val topics = lines(":manual")
        assertContains(topics, "git")
        assertContains(topics, "workspaces")
        val git = lines(":manual git")
        assertContains(git, "Pools from git revisions")
        assertContains(git, ":trace Specimen/s1 type.coding.code")
        assertTrue(git.none { it.contains("A workspace keeps") })
        assertContains(lines(":manual GIT"), "Pools from git revisions")
        val all = lines(":manual all")
        assertContains(all, "Pools from git revisions")
        assertContains(all, "A workspace keeps")
        assertContains(lines(":manual nope"), "no topic nope")
    }
}
