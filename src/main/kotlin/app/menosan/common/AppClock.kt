package app.menosan.common

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The application's only source of "now" (plan §4). Staging can shift it with CLOCK_OVERRIDE or
 * `/internal/dev/clock` (BE-5). An override sets the current time, and the clock keeps ticking from there.
 */
class OverridableClock(private val base: Clock = Clock.systemUTC()) : Clock() {
    @Volatile
    private var offset: Duration = Duration.ZERO

    /** Makes [instant] the current time. `null` clears the override. */
    fun setOverride(instant: Instant?) {
        offset = if (instant == null) Duration.ZERO else Duration.between(base.instant(), instant)
    }

    val isOverridden: Boolean get() = !offset.isZero

    override fun instant(): Instant = base.instant().plus(offset)
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = throw UnsupportedOperationException("Use WeekCalc for zones")
}
