package dev.fhirwalk

import dev.fhirwalk.model.FhirVersion
import dev.fhirwalk.repl.Repl
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The REPL's own wiring, driven line by line against `samples/basic`: what each
 * command leaves behind for the next. The rules themselves are in
 * [FocusResolverTest]; this is about which output keeps `:focus [n]` pickable.
 */
class ReplTest {

    private val repl = Repl(FhirVersion.R4, emptyList())

    init {
        run(":load samples/basic")
    }

    /** @return what the line printed. */
    private fun run(line: String): String {
        val text = StringWriter()
        PrintWriter(text).use { repl.dispatch(line, it) }
        return text.toString()
    }

    private fun assertContains(output: String, expected: String) =
        assertTrue(output.contains(expected), "expected \"$expected\" in:\n$output")

    @Test
    fun `an expression under a focus numbers its rows for a pick`() {
        run(":focus Patient/pat1")
        run("name")
        assertContains(run(":focus [1]"), "focus Patient/pat1.name[1]")
    }

    @Test
    fun `a pick survives stepping back up, for trying the next row`() {
        run(":focus Observation.code.coding")
        assertContains(run(":focus [0]"), "focus Observation/obs1.code.coding")
        run(":focus ..")
        assertContains(run(":focus [1]"), "focus Observation/obs2.code.coding")
    }

    @Test
    fun `command output does not replace the rows`() {
        run(":focus Observation.code.coding")
        run(":pool")
        run(":more")
        assertContains(run(":focus [2]"), "focus Observation/obs3.code.coding")
    }

    @Test
    fun `a pool-wide listing replaces the rows with nothing`() {
        run(":focus Observation.code.coding")
        run(":all Patient.name")
        assertContains(run(":focus [1]"), "nothing numbered to pick from")
    }

    @Test
    fun `an empty result replaces the rows with nothing`() {
        run(":focus Patient/pat1")
        run("name.given")
        run("telecom.nope")
        assertContains(run(":focus [1]"), "nothing numbered to pick from")
    }

    @Test
    fun `a focus that did not pick replaces the rows`() {
        run(":focus Observation.code.coding")
        // A label: the focus changed and came back, through no pick.
        run(":focus Patient/pat1")
        run(":focus")
        assertContains(run(":focus [1]"), "nothing numbered to pick from")
    }

    @Test
    fun `a malformed or overlong pick is reported as a pick`() {
        run(":focus Observation.code.coding")
        assertContains(run(":focus [-1]"), "no [-1] — that listing has [0] to [2]")
        assertContains(run(":focus [99999999999]"), "no [99999999999]")
        assertContains(run(":focus [ 1 ]"), "focus Observation/obs2.code.coding")
    }
}
