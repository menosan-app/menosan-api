package app.menosan.common

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WeekCalcTest {
    // Week of Sun 2026-09-27 .. Sat 2026-10-03 (PHT) = 2026-09-26T16:00Z .. 2026-10-03T15:59:59.999Z
    private val week = LocalDate.parse("2026-09-27")

    private fun clockAt(iso: String): Clock = Clock.fixed(Instant.parse(iso), ZoneOffset.UTC)

    @Test
    fun `Saturday 23-59-59 PHT belongs to the current week`() {
        assertEquals(week, WeekCalc.weekStart(Instant.parse("2026-10-03T15:59:59Z")))
        assertEquals(week, WeekCalc.weekStart(Instant.parse("2026-10-03T15:59:59.999Z")))
    }

    @Test
    fun `Sunday 00-00 PHT starts the next week`() {
        assertEquals(week.plusDays(7), WeekCalc.weekStart(Instant.parse("2026-10-03T16:00:00Z")))
    }

    @Test
    fun `week starts at Saturday 16-00 UTC`() {
        assertEquals(week.minusDays(7), WeekCalc.weekStart(Instant.parse("2026-09-26T15:59:59.999Z")))
        assertEquals(week, WeekCalc.weekStart(Instant.parse("2026-09-26T16:00:00Z")))
        assertEquals(Instant.parse("2026-09-26T16:00:00Z"), WeekCalc.startInstant(week))
        assertEquals(Instant.parse("2026-10-03T16:00:00Z"), WeekCalc.endExclusive(week))
    }

    @Test
    fun `week start is always a Sunday and week end a Saturday`() {
        var instant = Instant.parse("2026-09-20T00:00:00Z")
        repeat(24 * 14) {
            val start = WeekCalc.weekStart(instant)
            assertTrue(WeekCalc.isWeekStart(start), "for $instant")
            assertEquals(java.time.DayOfWeek.SATURDAY, WeekCalc.weekEnd(start).dayOfWeek)
            instant = instant.plusSeconds(3600)
        }
    }

    @Test
    fun `week is closed only from the following Sunday 00-00 PHT`() {
        assertFalse(WeekCalc.isClosed(week, clockAt("2026-10-03T15:59:59.999Z")))
        assertTrue(WeekCalc.isClosed(week, clockAt("2026-10-03T16:00:00Z")))
    }

    @Test
    fun `current week follows the clock across the boundary`() {
        assertTrue(WeekCalc.isCurrentWeek(week, clockAt("2026-10-03T15:59:59Z")))
        assertFalse(WeekCalc.isCurrentWeek(week, clockAt("2026-10-03T16:00:00Z")))
        assertEquals(week.plusDays(7), WeekCalc.currentWeekStart(clockAt("2026-10-03T16:00:00Z")))
    }

    @Test
    fun `JVM default zone does not affect week math`() {
        val original = TimeZone.getDefault()
        try {
            for (zone in listOf("America/Los_Angeles", "UTC", "Pacific/Kiritimati")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                assertEquals(week, WeekCalc.weekStart(Instant.parse("2026-10-03T15:59:59Z")), zone)
                assertEquals(week.plusDays(7), WeekCalc.weekStart(Instant.parse("2026-10-03T16:00:00Z")), zone)
            }
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun `overridable clock jumps to the override and keeps ticking`() {
        val base = object : Clock() {
            var now: Instant = Instant.parse("2026-09-23T00:00:00Z")
            override fun instant() = now
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId?) = this
        }
        val clock = OverridableClock(base)
        clock.setOverride(Instant.parse("2026-10-03T15:59:00Z"))
        assertEquals(week, WeekCalc.currentWeekStart(clock))
        base.now = base.now.plusSeconds(60)
        assertEquals(week.plusDays(7), WeekCalc.currentWeekStart(clock))
        clock.setOverride(null)
        assertEquals(base.now, clock.instant())
        assertFalse(clock.isOverridden)
    }
}
