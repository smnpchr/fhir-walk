package dev.fhirwalk

import dev.fhirwalk.model.FhirVersion
import dev.fhirwalk.repl.Repl
import dev.fhirwalk.repl.Workspaces
import kotlin.test.Test
import kotlin.test.assertEquals
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
    }

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
