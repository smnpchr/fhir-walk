package dev.fhirwalk

import dev.fhirwalk.pool.GitSource
import dev.fhirwalk.repl.CommitTimes
import org.eclipse.jgit.lib.ObjectId
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** How `:at` reads a date, and names each commit by its time. */
class CommitTimesTest {

    private val times = CommitTimes(ZoneOffset.UTC)

    private fun commit(n: Int, time: String) =
        GitSource.Commit(ObjectId.fromString("%040x".format(n)), Instant.parse(time), "commit $n", null)

    @Test
    fun `a date names the end of the period it spells`() {
        assertEquals(Instant.parse("2026-04-01T00:00:00Z"), times.end("2026-03"))
        assertEquals(Instant.parse("2026-03-12T00:00:00Z"), times.end("2026-03-11"))
        assertEquals(Instant.parse("2026-03-11T12:00:00Z"), times.end("2026-03-11T11"))
        assertEquals(Instant.parse("2026-03-11T11:01:00Z"), times.end("2026-03-11T11:00"))
        assertEquals(Instant.parse("2026-03-11T11:01:00Z"), times.end("2026-03-11 11:00"), "as :log shows it")
        assertEquals(Instant.parse("2026-03-11T11:00:43Z"), times.end("2026-03-11T11:00:42"))
        assertEquals(Instant.parse("2027-01-01T00:00:00Z"), times.end("2026-12"))
    }

    @Test
    fun `what has no dash after a year is not a date, so it can be a revision`() {
        assertNull(times.end("2026"), "could be an id prefix")
        assertNull(times.end("3f2a1c0"))
        assertNull(times.end("branch/out~2"))
        assertFailsWith<DateTimeException> { times.end("2026-13") }
        assertFailsWith<DateTimeException> { times.end("2026-02-30") }
    }

    @Test
    fun `as of a date is the first commit back from the tip made before it ended`() {
        val history = listOf(commit(3, "2026-03-12T09:00:00Z"), commit(2, "2026-03-11T11:00:00Z"), commit(1, "2026-03-10T08:00:00Z"))
        assertEquals(history[1], times.asOf(history, times.end("2026-03-11")!!))
        assertEquals(history[1], times.asOf(history, times.end("2026-03-11T11:00")!!))
        assertEquals(history[2], times.asOf(history, times.end("2026-03-11T10:59")!!))
        assertEquals(history[0], times.asOf(history, times.end("2026-04")!!))
        assertNull(times.asOf(history, times.end("2026-03-09")!!))
    }

    @Test
    fun `a commit is named by its minute, its second where a newer one shares the minute, else its id`() {
        val history = listOf(
            commit(5, "2026-03-11T11:00:50Z"),
            commit(4, "2026-03-11T11:00:10Z"),
            commit(3, "2026-03-11T11:00:10Z"),
            commit(2, "2026-03-11T10:59:00Z"),
        )
        assertEquals(
            listOf("2026-03-11T11:00", "2026-03-11T11:00:10", history[2].short, "2026-03-11T10:59"),
            times.names(history),
        )
    }

    @Test
    fun `a time that comes twice when the clocks go back still names its own commit`() {
        val berlin = CommitTimes(java.time.ZoneId.of("Europe/Berlin"))
        // 02:30 comes at 00:30Z (summer time) and again at 01:30Z (winter time).
        assertEquals(Instant.parse("2026-10-25T01:31:00Z"), berlin.end("2026-10-25T02:30"))
        val history = listOf(commit(2, "2026-10-25T01:30:00Z"), commit(1, "2026-10-24T10:00:00Z"))
        assertEquals(history[0], berlin.asOf(history, berlin.end(berlin.names(history)[0])!!))
        // The first 02:30:10 is named to the second, read in the later offset; once a
        // newer commit falls inside that second's end, only the id names it.
        val twice = listOf(commit(4, "2026-10-25T01:30:20Z"), commit(3, "2026-10-25T00:30:10Z"))
        assertEquals(listOf("2026-10-25T02:30", "2026-10-25T02:30:10"), berlin.names(twice))
        twice.zip(berlin.names(twice)).forEach { (commit, name) -> assertEquals(commit, berlin.asOf(twice, berlin.end(name)!!), name) }
        val close = listOf(commit(6, "2026-10-25T01:30:10Z"), commit(5, "2026-10-25T00:30:10Z"))
        assertEquals(listOf("2026-10-25T02:30", close[1].short), berlin.names(close))
    }

    @Test
    fun `every name reads back as its own commit, even out of time order`() {
        // A rebase can leave an older commit with the later time.
        val history = listOf(
            commit(4, "2026-03-11T10:00:00Z"),
            commit(3, "2026-03-11T12:00:00Z"),
            commit(2, "2026-03-11T09:30:00Z"),
            commit(1, "2026-03-11T09:30:30Z"),
        )
        val names = times.names(history)
        assertEquals(listOf("2026-03-11T10:00", history[1].short, "2026-03-11T09:30", history[3].short), names)
        history.zip(names).filter { (commit, name) -> name != commit.short }.forEach { (commit, name) ->
            assertEquals(commit, times.asOf(history, times.end(name)!!), name)
        }
    }
}
