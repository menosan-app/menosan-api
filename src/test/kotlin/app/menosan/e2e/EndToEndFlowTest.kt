package app.menosan.e2e

import app.menosan.common.WeekCalc
import app.menosan.dev.DEV_JOB_KEY
import app.menosan.dev.DevApp
import app.menosan.json
import app.menosan.module
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import java.time.DayOfWeek
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Runs [EndToEndScenario] in-process against the embedded Postgres, wired like staging. */
class EndToEndFlowTest {

    @Test
    fun `the full loop works end to end`() {
        // Wed 2026-06-10 12:00 PHT. Weeks 06-07 to 06-21 are used by no other test.
        val weekA = Instant.parse("2026-06-10T04:00:00Z")
        val app = DevApp(weekA)
        val steps = mutableListOf<String>()
        app.test {
            EndToEndScenario(KtorTestDriver(client), app.token, DEV_JOB_KEY, log = { steps += it }).run(weekA)

            // Cleanup ran: real time again, and the account has no reports left.
            assertFalse(app.clock.isOverridden)
            assertEquals("[]", client.get("/v1/reports") { bearerAuth(app.token) }.bodyAsText())
            assertEquals(app.email, client.get("/v1/me") { bearerAuth(app.token) }.json()["email"]!!.jsonPrimitive.content)
        }
        assertEquals(5, steps.size, steps.joinToString("\n"))
    }

    @Test
    fun `the loop also runs over real HTTP, as it does against staging`() {
        // Wed 2026-04-08 12:00 PHT. Weeks 04-05 to 04-19 are used by no other test.
        val weekA = Instant.parse("2026-04-08T04:00:00Z")
        val app = DevApp(weekA)
        val server = embeddedServer(Netty, port = 0, host = "127.0.0.1") { module(app.deps) }.start(wait = false)
        try {
            runBlocking {
                val port = server.engine.resolvedConnectors().first().port
                EndToEndScenario(JdkHttpDriver("http://127.0.0.1:$port/"), app.token, DEV_JOB_KEY).run(weekA)
            }
        } finally {
            server.stop(gracePeriodMillis = 0, timeoutMillis = 2_000)
        }
        assertFalse(app.clock.isOverridden)
    }

    @Test
    fun `staging runs use a Wednesday noon five weeks back`() {
        val start = EndToEndScenario.weekAStartFor(Instant.parse("2026-09-23T04:00:00Z"))
        assertEquals(Instant.parse("2026-08-19T04:00:00Z"), start)
        assertEquals(DayOfWeek.WEDNESDAY, start.atZone(WeekCalc.ZONE).dayOfWeek)
        // Right after the Sunday boundary it still picks a Wednesday.
        assertEquals(DayOfWeek.WEDNESDAY, EndToEndScenario.weekAStartFor(Instant.parse("2026-09-26T16:00:00Z")).atZone(WeekCalc.ZONE).dayOfWeek)
    }
}
