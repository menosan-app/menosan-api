package app.menosan.common

import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

object WeekCalc {
    const val TIMEZONE_ID = "Asia/Manila"
    val ZONE: ZoneId = ZoneId.of(TIMEZONE_ID)

    fun weekStart(instant: Instant): LocalDate =
        instant.atZone(ZONE).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))

    fun weekEnd(weekStart: LocalDate): LocalDate = weekStart.plusDays(6)

    fun currentWeekStart(clock: Clock): LocalDate = weekStart(clock.instant())

    fun isWeekStart(date: LocalDate): Boolean = date.dayOfWeek == DayOfWeek.SUNDAY

    fun startInstant(weekStart: LocalDate): Instant = weekStart.atStartOfDay(ZONE).toInstant()

    fun endExclusive(weekStart: LocalDate): Instant = weekStart.plusDays(7).atStartOfDay(ZONE).toInstant()

    fun isClosed(weekStart: LocalDate, clock: Clock): Boolean = !clock.instant().isBefore(endExclusive(weekStart))

    fun isCurrentWeek(weekStart: LocalDate, clock: Clock): Boolean = weekStart == currentWeekStart(clock)
}
