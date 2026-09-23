package app.menosan.common

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.time.Duration

/**
 * Keeps calls to one Gemini model under the key's free-tier quota, so we fall back on our own terms instead of
 * collecting 429s from Google.
 *
 * - **Per minute:** calls are spaced at least `60 s / perMinute` apart (4 s at 15 RPM). No bursts, so no 60-second
 *   window ever holds more than [perMinute] calls, and a caller that is next in line never waits longer than one gap.
 * - **Per day:** at most [perDay] calls per Pacific-time day, the day Google's daily quotas reset on.
 *
 * In memory, one per model per service (each service runs one instance, docs/ENVIRONMENTS.md). A restart forgets
 * the day's count; Google's own 429 is then the backstop, and every caller already falls back on errors.
 * Uses real time (not the injected app clock), because Google's quota does.
 */
class GeminiRateLimiter(
    val perMinute: Int,
    val perDay: Int,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    init {
        require(perMinute > 0 && perDay > 0) { "limits must be positive" }
    }

    enum class Result { ACQUIRED, BUSY, DAILY_LIMIT_REACHED }

    private val intervalMs: Long = (60_000L + perMinute - 1) / perMinute
    private val mutex = Mutex()
    private var nextFreeAt = Long.MIN_VALUE
    private var day: LocalDate? = null
    private var usedToday = 0

    /**
     * Reserves the next free slot and waits for it. Returns [Result.BUSY] without waiting when that slot is more
     * than [maxWait] away, and [Result.DAILY_LIMIT_REACHED] when today's calls are used up. Neither uses a slot.
     */
    suspend fun acquire(maxWait: Duration): Result {
        val waitMs = mutex.withLock {
            val now = nowMillis()
            val today = Instant.ofEpochMilli(now).atZone(QUOTA_ZONE).toLocalDate()
            if (today != day) {
                day = today
                usedToday = 0
            }
            if (usedToday >= perDay) return Result.DAILY_LIMIT_REACHED
            val start = maxOf(now, nextFreeAt)
            if (start - now > maxWait.inWholeMilliseconds) return Result.BUSY
            nextFreeAt = start + intervalMs
            usedToday++
            start - now
        }
        if (waitMs > 0) delay(waitMs)
        return Result.ACQUIRED
    }

    companion object {
        /** Google resets daily Gemini API quotas at midnight Pacific time. */
        val QUOTA_ZONE: ZoneId = ZoneId.of("America/Los_Angeles")
    }
}

/**
 * A [GeminiClient] that asks [limiter] for a slot before each call and fails with a [GeminiException] when it
 * gets none, so photo analysis maps it to ANALYSIS_FAILED and intervention selection falls back to rules (§6.2).
 * Callers add [maxQueueWait] to their own timeout, which then still covers only the Gemini call itself.
 */
class ThrottledGeminiClient(
    private val delegate: GeminiClient,
    private val limiter: GeminiRateLimiter,
    override val maxQueueWait: Duration,
) : GeminiClient {
    override suspend fun generateJson(request: GeminiRequest): String =
        when (limiter.acquire(maxQueueWait)) {
            GeminiRateLimiter.Result.ACQUIRED -> delegate.generateJson(request)
            GeminiRateLimiter.Result.BUSY ->
                throw GeminiException("Gemini busy: no free slot within $maxQueueWait (limit ${limiter.perMinute} per minute)")
            GeminiRateLimiter.Result.DAILY_LIMIT_REACHED ->
                throw GeminiException("Gemini daily limit reached (${limiter.perDay} per Pacific-time day)")
        }
}
