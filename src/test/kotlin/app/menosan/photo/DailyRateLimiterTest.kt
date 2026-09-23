package app.menosan.photo

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DailyRateLimiterTest {
    private class MutableClock(var now: Instant) : Clock() {
        override fun instant() = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?) = this
    }

    private val alice = UUID.randomUUID()
    private val bob = UUID.randomUUID()

    @Test
    fun `each user gets the limit per Manila day`() {
        val clock = MutableClock(Instant.parse("2026-09-30T15:59:59Z")) // Wed 23:59:59 PHT
        val limiter = DailyRateLimiter(2, clock)
        assertTrue(limiter.tryAcquire(alice))
        assertTrue(limiter.tryAcquire(alice))
        assertFalse(limiter.tryAcquire(alice))
        assertFalse(limiter.tryAcquire(alice)) // a refused call doesn't use anything
        assertTrue(limiter.tryAcquire(bob), "users don't share a quota")
        assertEquals(Instant.parse("2026-09-30T16:00:00Z"), limiter.resetsAt())

        clock.now = Instant.parse("2026-09-30T16:00:00Z") // Thu 00:00 PHT
        assertTrue(limiter.tryAcquire(alice))
        assertTrue(limiter.tryAcquire(alice))
        assertFalse(limiter.tryAcquire(alice))
        assertEquals(Instant.parse("2026-10-01T16:00:00Z"), limiter.resetsAt())
    }
}
