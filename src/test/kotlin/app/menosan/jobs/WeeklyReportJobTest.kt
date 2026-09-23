package app.menosan.jobs

import app.menosan.common.OverridableClock
import app.menosan.errorCode
import app.menosan.json
import app.menosan.reports.ReportApp
import app.menosan.reports.ReportFixtures
import app.menosan.reports.ReportService
import app.menosan.reports.StubReportService
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

class WeeklyReportJobTest {

    // ---- schedule (Sunday 00:05 PHT = Saturday 16:05 UTC) ----

    @Test
    fun `next run is the coming Sunday 00_05 in Manila`() {
        fun next(iso: String) = WeeklySchedule.nextRun(Instant.parse(iso)).toString()
        assertEquals("2026-10-03T16:05:00Z", next("2026-09-30T04:00:00Z")) // Wednesday
        assertEquals("2026-10-03T16:05:00Z", next("2026-10-03T15:59:59Z")) // Sat 23:59:59 PHT
        assertEquals("2026-10-03T16:05:00Z", next("2026-10-03T16:04:59.999Z")) // Sun 00:04:59.999 PHT
        assertEquals("2026-10-10T16:05:00Z", next("2026-10-03T16:05:00Z")) // exactly at the run: the next week
        assertEquals("2026-10-10T16:05:00Z", next("2026-10-04T10:00:00Z"))
    }

    @Test
    fun `scheduler runs the job once the clock passes the run time`() {
        val clock = OverridableClock()
        val runs = AtomicInteger()
        val reports = object : ReportService by StubReportService {
            override suspend fun generateMissing(weekStart: LocalDate?): Int {
                assertEquals(null, weekStart)
                runs.incrementAndGet()
                return 0
            }
        }
        testApplication {
            application {
                // Set just before the scheduler reads it: if app startup ate the margin, it would wait a week.
                clock.setOverride(Instant.parse("2026-10-03T16:04:59Z"))
                startWeeklyReportScheduler(clock, reports, pollInterval = 20.milliseconds)
            }
            startApplication()
            withTimeout(5_000) { while (runs.get() == 0) delay(10) }
        }
        assertEquals(1, runs.get())
    }

    // ---- POST /internal/jobs/weekly-reports ----

    @Test
    fun `endpoint does not exist without a configured job key`() {
        ReportApp(jobKey = null).test {
            val response = client.post("/internal/jobs/weekly-reports") { header(JOB_KEY_HEADER, "anything") }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    @Test
    fun `missing or wrong job key is rejected`() {
        ReportApp(jobKey = "secret-key").test {
            val missing = client.post("/internal/jobs/weekly-reports")
            assertEquals(HttpStatusCode.Unauthorized, missing.status)
            assertEquals("UNAUTHENTICATED", missing.errorCode())
            val wrong = client.post("/internal/jobs/weekly-reports") { header(JOB_KEY_HEADER, "secret-kez") }
            assertEquals(HttpStatusCode.Unauthorized, wrong.status)
        }
    }

    @Test
    fun `generates missing reports for the requested week, idempotently`() {
        // A week only this test uses, since the job covers every user.
        val week = LocalDate.parse("2026-07-05")
        val f = ReportFixtures(now = Instant.parse("2026-07-15T04:00:00Z"))
        val app = ReportApp(f, jobKey = "secret-key")
        runBlocking {
            f.log(app.alice, week, "RES_SACHETS", 2)
            f.log(app.bob, week, "REC_GLASS", 1)
        }
        app.test {
            suspend fun run(body: String) = client.post("/internal/jobs/weekly-reports") {
                header(JOB_KEY_HEADER, "secret-key")
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            val first = run("""{"weekStart":"2026-07-05"}""")
            assertEquals(HttpStatusCode.OK, first.status)
            assertEquals("2026-07-05", first.json()["weekStart"]!!.jsonPrimitive.content)
            assertEquals("2", first.json()["created"]!!.jsonPrimitive.content)
            assertEquals("0", run("""{"weekStart":"2026-07-05"}""").json()["created"]!!.jsonPrimitive.content)

            // No body: the week that just closed.
            val default = client.post("/internal/jobs/weekly-reports") { header(JOB_KEY_HEADER, "secret-key") }
            assertEquals(HttpStatusCode.OK, default.status)
            assertEquals("2026-07-05", default.json()["weekStart"]!!.jsonPrimitive.content)

            for (bad in listOf("""{"weekStart":"2026-07-12"}""", """{"weekStart":"2026-07-06"}""", """{"weekStart":""")) {
                val response = run(bad)
                assertEquals(HttpStatusCode.BadRequest, response.status, bad)
                assertEquals("VALIDATION_FAILED", response.errorCode())
            }
        }
    }
}
