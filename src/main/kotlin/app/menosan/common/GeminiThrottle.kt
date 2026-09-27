package app.menosan.common

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.time.Duration

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
        val QUOTA_ZONE: ZoneId = ZoneId.of("America/Los_Angeles")
    }
}

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
