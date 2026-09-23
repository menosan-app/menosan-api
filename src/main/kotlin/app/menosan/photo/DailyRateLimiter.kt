package app.menosan.photo

import app.menosan.common.WeekCalc
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-user daily quota, counted per Manila calendar day from the injected clock.
 * In memory, per server instance: a restart resets it, and several instances each count on their own
 * (see docs/DECISIONS.md). Good enough to protect the Gemini quota during testing.
 */
class DailyRateLimiter(val limit: Int, private val clock: Clock) {
    private class Usage(val day: LocalDate, val count: Int)

    private val usage = ConcurrentHashMap<UUID, Usage>()

    /** Uses one slot for [userId] today. Returns false (and uses nothing) when the quota is spent. */
    fun tryAcquire(userId: UUID): Boolean {
        val today = today()
        var allowed = false
        usage.compute(userId) { _, current ->
            val used = if (current?.day == today) current.count else 0
            if (used < limit) {
                allowed = true
                Usage(today, used + 1)
            } else {
                current
            }
        }
        if (usage.size > PRUNE_THRESHOLD) usage.entries.removeIf { it.value.day != today }
        return allowed
    }

    /** Start of the next Manila day, when every quota resets. */
    fun resetsAt(): Instant = today().plusDays(1).atStartOfDay(WeekCalc.ZONE).toInstant()

    private fun today(): LocalDate = clock.instant().atZone(WeekCalc.ZONE).toLocalDate()

    private companion object {
        const val PRUNE_THRESHOLD = 10_000
    }
}
