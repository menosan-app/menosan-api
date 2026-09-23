package app.menosan.common

import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * Logging weeks run from Sunday 00:00 to Saturday 23:59:59.999 in Asia/Manila (plan §4, SFR5.3).
 * Never uses the JVM default zone.
 */
object WeekCalc {
    const val TIMEZONE_ID = "Asia/Manila"
    val ZONE: ZoneId = ZoneId.of(TIMEZONE_ID)

    fun weekStart(instant: Instant): LocalDate =
        instant.atZone(ZONE).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))

    fun weekEnd(weekStart: LocalDate): LocalDate = weekStart.plusDays(6)

    fun currentWeekStart(clock: Clock): LocalDate = weekStart(clock.instant())

    fun isWeekStart(date: LocalDate): Boolean = date.dayOfWeek == DayOfWeek.SUNDAY

    /** First instant of the week (Sunday 00:00 PHT). */
    fun startInstant(weekStart: LocalDate): Instant = weekStart.atStartOfDay(ZONE).toInstant()

    /** First instant after the week (the next Sunday 00:00 PHT). */
    fun endExclusive(weekStart: LocalDate): Instant = weekStart.plusDays(7).atStartOfDay(ZONE).toInstant()

    /** A week is closed once server now ≥ the following Sunday 00:00 PHT (SFR12.1). */
    fun isClosed(weekStart: LocalDate, clock: Clock): Boolean = !clock.instant().isBefore(endExclusive(weekStart))

    /** Entries are editable only in the server's current week (SFR11.1–11.3). */
    fun isCurrentWeek(weekStart: LocalDate, clock: Clock): Boolean = weekStart == currentWeekStart(clock)
}
