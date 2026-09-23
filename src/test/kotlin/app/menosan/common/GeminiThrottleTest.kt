package app.menosan.common

import app.menosan.interventions.FakeGemini
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The free-tier throttle: calls spaced 60 s / RPM apart, at most RPD per Pacific-time day. */
class GeminiThrottleTest {
    /** A limiter on a hand-moved clock, so waits are computed but never slept (unless a test wants them). */
    private class FakeTime(start: String) {
        var now: Long = Instant.parse(start).toEpochMilli()
        fun advance(d: Duration) { now += d.inWholeMilliseconds }
    }

    private fun limiter(time: FakeTime, perMinute: Int = 15, perDay: Int = 500) =
        GeminiRateLimiter(perMinute, perDay) { time.now }

    private val request = GeminiRequest("system", "prompt", "{}", timeout = 1.seconds)

    @Test
    fun `calls are spaced 60 s per RPM apart and a slot too far away is BUSY`() = runBlocking {
        val time = FakeTime("2026-09-24T02:00:00Z")
        val limiter = limiter(time, perMinute = 15) // one call every 4 s

        assertEquals(GeminiRateLimiter.Result.ACQUIRED, limiter.acquire(Duration.ZERO))
        // The next slot is 4 s away: more than the caller will wait.
        assertEquals(GeminiRateLimiter.Result.BUSY, limiter.acquire(3_999.milliseconds))
        time.advance(4.seconds)
        assertEquals(GeminiRateLimiter.Result.ACQUIRED, limiter.acquire(Duration.ZERO))
        // BUSY didn't use a slot, so the one after is again exactly 4 s later.
        time.advance(3_999.milliseconds)
        assertEquals(GeminiRateLimiter.Result.BUSY, limiter.acquire(Duration.ZERO))
        time.advance(1.milliseconds)
        assertEquals(GeminiRateLimiter.Result.ACQUIRED, limiter.acquire(Duration.ZERO))
    }

    @Test
    fun `no minute ever holds more than RPM calls`() = runBlocking {
        val time = FakeTime("2026-09-24T02:00:00Z")
        val limiter = limiter(time, perMinute = 15)
        val granted = mutableListOf<Long>()
        // Callers arriving every 500 ms for 3 minutes, each taking a slot only when it's free right now.
        repeat(360) {
            if (limiter.acquire(Duration.ZERO) == GeminiRateLimiter.Result.ACQUIRED) granted += time.now
            time.advance(500.milliseconds)
        }
        for (start in granted) {
            val inWindow = granted.count { it >= start && it < start + 60_000 }
            assertTrue(inWindow <= 15, "$inWindow calls within one minute")
        }
        assertEquals(45, granted.size)
    }

    @Test
    fun `a caller within maxWait waits for its slot`() = runBlocking {
        val time = FakeTime("2026-09-24T02:00:00Z")
        val limiter = limiter(time, perMinute = 600) // one call every 100 ms
        val started = System.nanoTime()
        repeat(3) { assertEquals(GeminiRateLimiter.Result.ACQUIRED, limiter.acquire(1.seconds)) }
        val waitedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(waitedMs in 180..1_500, "the 2nd and 3rd calls wait 100 ms and 200 ms (waited $waitedMs ms)")
    }

    @Test
    fun `the daily limit resets at midnight Pacific time`() = runBlocking {
        // 06:59 UTC = 23:59 PDT on Sep 23.
        val time = FakeTime("2026-09-24T06:59:00Z")
        val limiter = limiter(time, perMinute = 60, perDay = 2)

        repeat(2) {
            assertEquals(GeminiRateLimiter.Result.ACQUIRED, limiter.acquire(Duration.ZERO))
            time.advance(1.seconds)
        }
        assertEquals(GeminiRateLimiter.Result.DAILY_LIMIT_REACHED, limiter.acquire(1.seconds))
        time.now = Instant.parse("2026-09-24T06:59:59.999Z").toEpochMilli()
        assertEquals(GeminiRateLimiter.Result.DAILY_LIMIT_REACHED, limiter.acquire(1.seconds))
        time.now = Instant.parse("2026-09-24T07:00:00Z").toEpochMilli() // 00:00 PDT: a new quota day
        assertEquals(GeminiRateLimiter.Result.ACQUIRED, limiter.acquire(Duration.ZERO))
    }

    @Test
    fun `the throttled client calls Gemini only with a slot and fails with a log-safe GeminiException`() = runBlocking {
        val time = FakeTime("2026-09-24T02:00:00Z")
        val gemini = FakeGemini { "{}" }
        val client = ThrottledGeminiClient(gemini, limiter(time, perMinute = 15, perDay = 2), maxQueueWait = 1.seconds)
        assertEquals(1.seconds, client.maxQueueWait)

        assertEquals("{}", client.generateJson(request))
        val busy = assertFailsWith<GeminiException> { client.generateJson(request) }
        assertEquals("Gemini busy: no free slot within 1s (limit 15 per minute)", geminiErrorSummary(busy))

        time.advance(4.seconds)
        client.generateJson(request)
        time.advance(4.seconds)
        val daily = assertFailsWith<GeminiException> { client.generateJson(request) }
        assertEquals("Gemini daily limit reached (2 per Pacific-time day)", geminiErrorSummary(daily))
        assertEquals(2, gemini.requests.size, "refused calls never reach Gemini")
    }

    @Test
    fun `limits must be positive`() {
        assertFailsWith<IllegalArgumentException> { GeminiRateLimiter(0, 500) }
        assertFailsWith<IllegalArgumentException> { GeminiRateLimiter(15, 0) }
    }
}
