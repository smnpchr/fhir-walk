package dev.fhirwalk.repl

import dev.fhirwalk.pool.GitSource
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Commits named by when they were made, so `:at` can be typed and completed by date
 * rather than by id: `2026-03`, `2026-03-11`, `2026-03-11T11:00`.
 *
 * A date names the line as it stood at the end of that period: the first commit,
 * walking back from the tip along first parents, made before the period ended.
 * That is the order `:log` shows, so a rebase that left times out of order picks
 * what the listing suggests rather than the latest timestamp.
 *
 * Times are read and written in [zone], the one `:log` shows them in.
 */
class CommitTimes(private val zone: ZoneId = ZoneId.systemDefault()) {

    private val minute = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm").withZone(zone)
    private val second = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(zone)
    private val display = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zone)

    /**
     * When the period [text] names ends, exclusive; null when it is not shaped like a
     * date at all, so it can be read as a revision instead. A date has a `-` after
     * its year, which no commit id has. Throws [java.time.DateTimeException] for one
     * shaped like a date that is none, such as `2026-13`.
     */
    fun end(text: String): Instant? = period(text)?.second?.let(::instant)

    /**
     * When the period [text] names begins, as [end] reads it: what a meta pool's
     * cut-off keeps from. Null when it is not shaped like a date.
     */
    fun start(text: String): Instant? = period(text)?.first?.let(::instant)

    private fun period(text: String): Pair<LocalDateTime, LocalDateTime>? {
        val match = DATE.matchEntire(text) ?: return null
        val (year, month, day, hour, min, sec) = match.destructured
        return when {
            day.isEmpty() -> YearMonth.of(year.toInt(), month.toInt()).atDay(1).atStartOfDay().let { it to it.plusMonths(1) }
            hour.isEmpty() -> LocalDate.of(year.toInt(), month.toInt(), day.toInt()).atStartOfDay().let { it to it.plusDays(1) }
            else -> {
                val start = LocalDateTime.of(year.toInt(), month.toInt(), day.toInt(), hour.toInt(), min.ifEmpty { "0" }.toInt(), sec.ifEmpty { "0" }.toInt())
                start to start.plus(1, if (sec.isNotEmpty()) ChronoUnit.SECONDS else if (min.isNotEmpty()) ChronoUnit.MINUTES else ChronoUnit.HOURS)
            }
        }
    }

    // Where a local time comes twice, as when clocks go back, it is the later of the
    // two: a period has not ended until both have passed.
    private fun instant(time: LocalDateTime): Instant = time.atZone(zone).withLaterOffsetAtOverlap().toInstant()

    /** The commit of [history] (newest first) that the line stood at when [end] came. */
    fun asOf(history: List<GitSource.Commit>, end: Instant): GitSource.Commit? = history.firstOrNull { it.time < end }

    /**
     * How each commit of [history] (newest first) is typed after `:at`: its minute,
     * or its second where a newer commit shares the minute, or its short id where
     * neither would name it back. Each one names its own commit under [asOf].
     */
    fun names(history: List<GitSource.Commit>): List<String> {
        // The oldest time among the newer commits: a name is good when it ends after
        // this commit and no later than that, so none of them is picked before it.
        // Checked rather than assumed, since a local time can name two instants.
        var oldestNewer = Instant.MAX
        return history.map { commit ->
            val name = listOf(minute, second).map { it.format(commit.time) }
                .firstOrNull { end(it)!!.let { end -> end > commit.time && end <= oldestNewer } } ?: commit.short
            oldestNewer = minOf(oldestNewer, commit.time)
            name
        }
    }

    /** As `:log` shows a commit's time; [end] reads it back, space and all. */
    fun shown(commit: GitSource.Commit): String = shown(commit.time)

    fun shown(time: Instant): String = display.format(time)

    private companion object {
        val DATE = Regex("""(\d{4})-(\d{2})(?:-(\d{2})(?:[T ](\d{2})(?::(\d{2})(?::(\d{2}))?)?)?)?""")
    }
}
