package dev.fhirwalk

import dev.fhirwalk.repl.Style
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StyleTest {

    private val on = Style(true)

    private fun strip(text: String) = text.replace(Regex("\u001b\\[[0-9;]*m"), "")

    @Test
    fun `a disabled style returns its input unchanged`() {
        val line = """{"system": "http://loinc.org", "code": "1234-5"}"""
        assertEquals(line, Style.PLAIN.json(line))
        assertEquals("Patient", Style.PLAIN.type("Patient"))
        assertEquals("! boom", Style.PLAIN.error("! boom"))
    }

    @Test
    fun `highlighting only adds escape codes`() {
        val lines = listOf(
            """{"value": 4.5e-3, "unit": "mg", "active": true, "note": null}""",
            """  "display": "say \"hi\", then: go",""",
            """[{"a": [1, -2]}, "b"]""",
        )
        lines.forEach { assertEquals(it, strip(on.json(it))) }
        lines.forEach { assertEquals(it, strip(on.json(it, keysOnly = true))) }
    }

    @Test
    fun `keys and values are told apart`() {
        val line = on.json("""{"code": "x", "n": 3, "ok": false}""")
        assertTrue(line.contains("\u001b[34m\"code\"\u001b[0m"), line)
        assertTrue(line.contains("\u001b[32m\"x\"\u001b[0m"), line)
        assertTrue(line.contains("\u001b[33m3\u001b[0m"), line)
        assertTrue(line.contains("\u001b[35mfalse\u001b[0m"), line)
    }

    @Test
    fun `a colon inside a string value does not make it a key`() {
        val line = on.json("""  "url": "http://example.org/a:b",""")
        assertTrue(line.contains("\u001b[32m\"http://example.org/a:b\"\u001b[0m"), line)
    }

    @Test
    fun `keys only leaves values plain`() {
        val line = on.value("""{"code": "x", "n": 3}""", keysOnly = true)
        assertTrue(line.contains("\u001b[34m\"code\"\u001b[0m"), line)
        assertTrue(line.contains("\"x\"") && !line.contains("\u001b[32m"), line)
    }

    @Test
    fun `a line cut short inside a string is still highlighted to the end`() {
        val line = """{"text": "a long narrative that was trimmed…"""
        assertEquals(line, strip(on.json(line)))
        assertTrue(on.json(line).endsWith("trimmed…\u001b[0m"))
    }

    @Test
    fun `text that is not json is left alone`() {
        listOf("male", "2020-01-01", "42", "true", "Coding{system=x}").forEach {
            assertEquals(it, on.value(it))
        }
    }

    @Test
    fun `pretty-printed continuation lines are highlighted`() {
        // Inner lines of Render.pretty start with a key or a value, not a bracket.
        val line = """    "family": "Chalmers","""
        assertTrue(on.json(line).contains("\u001b[34m\"family\""), on.json(line))
    }

    @Test
    fun `errors are red`() {
        assertEquals("\u001b[31m! boom\u001b[0m", on.error("! boom"))
    }
}
