package app.menosan.jobs

import app.menosan.common.ApiException
import app.menosan.common.ErrorCode
import app.menosan.common.WeekCalc
import app.menosan.plugins.ApiJson
import app.menosan.reports.ReportService
import app.menosan.reports.parseWeekStart
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.time.Clock
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

private val log = LoggerFactory.getLogger("app.menosan.jobs")

const val JOB_KEY_HEADER = "X-Job-Key"

@Serializable
data class WeeklyReportsJobRequest(val weekStart: String? = null)

@Serializable
data class WeeklyReportsJobResponse(val weekStart: String, val created: Int)

/**
 * Checks `X-Job-Key` against JOB_KEY. With no JOB_KEY configured the internal endpoints don't exist (404);
 * a missing or wrong key is 401. BE-5's `/internal/dev` tools reuse this.
 */
fun ApplicationCall.requireJobKey(jobKey: String?) {
    if (jobKey == null) throw ApiException(ErrorCode.NOT_FOUND, "Not found.")
    val given = request.headers[JOB_KEY_HEADER] ?: throw ApiException(ErrorCode.UNAUTHENTICATED, "A valid job key is required.")
    if (!MessageDigest.isEqual(given.toByteArray(), jobKey.toByteArray())) {
        throw ApiException(ErrorCode.UNAUTHENTICATED, "A valid job key is required.")
    }
}

/**
 * `POST /internal/jobs/weekly-reports` (§8.2), for an external cron. Optional body `{weekStart}`
 * (default: the week that just closed). Idempotent: only missing reports are generated.
 */
fun Route.weeklyReportJobRoute(jobKey: String?, reports: ReportService, clock: Clock) {
    post("/internal/jobs/weekly-reports") {
        call.requireJobKey(jobKey)
        val text = call.receiveText()
        val body = if (text.isBlank()) {
            WeeklyReportsJobRequest()
        } else {
            try {
                ApiJson.decodeFromString(WeeklyReportsJobRequest.serializer(), text)
            } catch (_: SerializationException) {
                throw ApiException(ErrorCode.VALIDATION_FAILED, "The request is malformed or has invalid fields.")
            } catch (_: IllegalArgumentException) {
                throw ApiException(ErrorCode.VALIDATION_FAILED, "The request is malformed or has invalid fields.")
            }
        }
        val week = body.weekStart?.let { parseWeekStart(it) } ?: WeekCalc.currentWeekStart(clock).minusDays(7)
        if (!WeekCalc.isClosed(week, clock)) {
            throw ApiException(ErrorCode.VALIDATION_FAILED, "That week hasn't closed yet.")
        }
        val created = reports.generateMissing(week)
        call.respond(WeeklyReportsJobResponse(week.toString(), created))
    }
}

/** When the in-process job runs: every Sunday 00:05 PHT (`5 16 * * 6` UTC), plan §5.5. */
object WeeklySchedule {
    private val RUN_AT: LocalTime = LocalTime.of(0, 5)

    /** The first run strictly after [now]. */
    fun nextRun(now: Instant): Instant {
        val local = now.atZone(WeekCalc.ZONE)
        var run = local.toLocalDate().with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)).atTime(RUN_AT).atZone(WeekCalc.ZONE)
        if (!run.toInstant().isAfter(now)) run = run.plusWeeks(1)
        return run.toInstant()
    }
}

/**
 * In-process weekly report job. It re-reads the injected clock every [pollInterval], so a staging clock
 * override that jumps past Sunday 00:05 triggers it too. The external cron and lazy catch-up cover sleeping hosts.
 */
fun Application.startWeeklyReportScheduler(
    clock: Clock,
    reports: ReportService,
    pollInterval: kotlin.time.Duration = 1.minutes,
): Job = launch {
    while (isActive) {
        val next = WeeklySchedule.nextRun(clock.instant())
        log.info("Next weekly report run at {}", next)
        while (true) {
            val remaining = Duration.between(clock.instant(), next)
            if (remaining.isNegative || remaining.isZero) break
            delay(minOf(remaining, pollInterval.toJavaDuration()).toMillis().coerceAtLeast(1))
        }
        try {
            reports.generateMissing(null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("Weekly report job failed: {}", e.javaClass.name)
        }
    }
}
