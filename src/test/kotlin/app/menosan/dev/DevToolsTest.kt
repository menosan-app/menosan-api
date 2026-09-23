package app.menosan.dev

import app.menosan.createAccount
import app.menosan.entryBody
import app.menosan.errorCode
import app.menosan.json
import app.menosan.putEntry
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Wed 2026-05-20 12:00 PHT: current week 2026-05-17, so seeding 3 weeks covers 04-26, 05-03, and 05-10. */
private val NOW: Instant = Instant.parse("2026-05-20T04:00:00Z")
private val SEEDED_WEEKS = listOf("2026-04-26", "2026-05-03", "2026-05-10")

class DevToolsTest {

    // ---- guards ----

    @Test
    fun `dev routes don't exist unless enabled, outside prod, with a job key`() {
        for (app in listOf(DevApp(NOW, devToolsEnabled = false), DevApp(NOW, appEnv = "prod"), DevApp(NOW, jobKey = null))) {
            app.test {
                val response = devPost("/clock", """{"now":"2026-10-04T00:05:00Z"}""")
                assertEquals(HttpStatusCode.NotFound, response.status)
                assertEquals("NOT_FOUND", response.errorCode())
            }
            assertTrue(app.clock.instant().isBefore(Instant.parse("2026-06-01T00:00:00Z")), "the clock must not move")
        }
    }

    @Test
    fun `a missing or wrong job key is rejected`() {
        DevApp(NOW).test {
            for (key in listOf(null, "wrong-key")) {
                for (path in listOf("/clock", "/seed-history", "/reset", "/reports/generate")) {
                    val response = devPost(path, "{}", key = key)
                    assertEquals(HttpStatusCode.Unauthorized, response.status, "$path key=$key")
                    assertEquals("UNAUTHENTICATED", response.errorCode())
                }
            }
            assertEquals(HttpStatusCode.Unauthorized, client.get("/internal/dev/clock").status)
        }
    }

    // ---- clock ----

    @Test
    fun `clock can be read, set, and cleared`() {
        val app = DevApp(NOW)
        app.test {
            val read = client.get("/internal/dev/clock") { header("X-Job-Key", DEV_JOB_KEY) }.json()
            assertTrue(read["overridden"]!!.jsonPrimitive.boolean)
            assertEquals("2026-05-17", read["currentWeekStart"]!!.jsonPrimitive.content)

            val set = devPost("/clock", """{"now":"2026-10-04T00:05:00Z"}""")
            assertEquals(HttpStatusCode.OK, set.status)
            assertEquals("2026-10-04", set.json()["currentWeekStart"]!!.jsonPrimitive.content)
            assertTrue(set.json()["serverNow"]!!.jsonPrimitive.content.startsWith("2026-10-04T00:05"))

            // The rest of the API sees the new time.
            createAccount(app.token)
            val week = client.get("/v1/weeks/current") { bearerAuth(app.token) }.json()
            assertEquals("2026-10-04", week["weekStart"]!!.jsonPrimitive.content)

            for (bad in listOf("""{"now":"tomorrow"}""", """{"now":"2026-10-04"}""")) {
                val response = devPost("/clock", bad)
                assertEquals(HttpStatusCode.BadRequest, response.status, bad)
                assertEquals("now", response.json()["error"]!!.jsonObject["details"]!!.jsonObject["field"]!!.jsonPrimitive.content)
            }

            for (clear in listOf("{}", """{"now":null}""", null)) {
                devPost("/clock", """{"now":"2026-10-04T00:05:00Z"}""")
                val cleared = devPost("/clock", clear)
                assertEquals(HttpStatusCode.OK, cleared.status)
                assertFalse(cleared.json()["overridden"]!!.jsonPrimitive.boolean, "clear with $clear")
                assertFalse(app.clock.isOverridden)
            }
        }
    }

    // ---- seed-history ----

    @Test
    fun `seed-history builds past reports with adoptions and measured impact`() {
        val app = DevApp(NOW)
        app.test {
            createAccount(app.token)
            val seeded = devPost("/seed-history", """{"email":"${app.email.lowercase()}","weeks":3}""")
            assertEquals(HttpStatusCode.OK, seeded.status, seeded.bodyAsText())
            val weeks = seeded.json()["weeks"]!!.jsonArray.map { it.jsonObject }
            assertEquals(SEEDED_WEEKS, weeks.map { it["weekStart"]!!.jsonPrimitive.content })
            // The latest report is left for the tester to adopt from the app.
            assertEquals(listOf(1, 1, 0), weeks.map { it["adopted"]!!.jsonArray.size })
            assertEquals(listOf(0, 1, 1), weeks.map { it["impacts"]!!.jsonPrimitive.int })
            weeks.forEach { assertTrue(it["hotspots"]!!.jsonArray.isNotEmpty()) }

            val list = reportList(app.token)
            assertEquals(SEEDED_WEEKS.reversed(), list.map { it.jsonObject["weekStart"]!!.jsonPrimitive.content })
            assertEquals(listOf(true, false, false), list.map { it.jsonObject["isLatest"]!!.jsonPrimitive.boolean })
            assertEquals(listOf(0, 1, 1), list.map { it.jsonObject["adoptedCount"]!!.jsonPrimitive.int })

            // The seed logs less of each adopted target the next week, so the demo shows progress.
            for (week in SEEDED_WEEKS.drop(1)) {
                val impacts = report(app.token, week)["impacts"]!!.jsonArray.map { it.jsonObject }
                assertEquals(1, impacts.size)
                assertEquals("DECREASED", impacts.single()["result"]!!.jsonPrimitive.content)
                assertTrue(impacts.single()["followupQuantity"]!!.jsonPrimitive.int < impacts.single()["baselineQuantity"]!!.jsonPrimitive.int)
            }

            // The latest seeded report has library recommendations the tester can adopt through the normal API.
            val latest = report(app.token, SEEDED_WEEKS.last())
            val pick = latest["hotspots"]!!.jsonArray.first().jsonObject["recommendations"]!!.jsonArray.first().jsonObject
            val adopt = client.post("/v1/reports/${SEEDED_WEEKS.last()}/adoptions") {
                bearerAuth(app.token)
                contentType(ContentType.Application.Json)
                setBody("""{"interventionIds":["${pick["interventionId"]!!.jsonPrimitive.content}"]}""")
            }
            assertEquals(HttpStatusCode.OK, adopt.status)
        }
    }

    @Test
    fun `seeding is deterministic, refuses weeks with data, and works again after a reset`() {
        val app = DevApp(NOW)
        app.test {
            createAccount(app.token)
            createAccount(app.otherToken)
            val otherEmail = client.get("/v1/me") { bearerAuth(app.otherToken) }.json()["email"]!!.jsonPrimitive.content

            val first = devPost("/seed-history", """{"email":"${app.email}","seed":7}""").json()
            val second = devPost("/seed-history", """{"email":"$otherEmail","seed":7}""").json()
            fun shape(o: kotlinx.serialization.json.JsonObject) = o["weeks"]!!.jsonArray.map {
                val w = it.jsonObject
                listOf(w["entries"], w["analyzedQuantity"], w["hotspots"], w["adopted"]).toString()
            }
            assertEquals(shape(first), shape(second))

            val again = devPost("/seed-history", """{"email":"${app.email}"}""")
            assertEquals(HttpStatusCode.Conflict, again.status)
            assertEquals("CONFLICT", again.errorCode())
            assertEquals(SEEDED_WEEKS.first(), again.json()["error"]!!.jsonObject["details"]!!.jsonObject["weekStart"]!!.jsonPrimitive.content)

            val reset = devPost("/reset", """{"email":"${app.email}"}""")
            assertEquals(HttpStatusCode.OK, reset.status)
            assertEquals(3, reset.json()["reportsDeleted"]!!.jsonPrimitive.int)
            assertTrue(reset.json()["entriesDeleted"]!!.jsonPrimitive.int > 0)
            assertEquals(0, reportList(app.token).size)
            assertEquals(3, reportList(app.otherToken).size) // only that account was reset
            // The account itself survives.
            assertEquals(HttpStatusCode.OK, client.get("/v1/me") { bearerAuth(app.token) }.status)

            assertEquals(HttpStatusCode.OK, devPost("/seed-history", """{"email":"${app.email}","weeks":1}""").status)
            assertEquals(1, reportList(app.token).size)
        }
    }

    @Test
    fun `seed-history and reset validate their input`() {
        val app = DevApp(NOW)
        app.test {
            createAccount(app.token)
            fun field(r: kotlinx.serialization.json.JsonObject) =
                r["error"]!!.jsonObject["details"]!!.jsonObject["field"]?.jsonPrimitive?.content
            for ((path, body, expectedField) in listOf(
                Triple("/seed-history", null, null),
                Triple("/seed-history", "{", null),
                Triple("/seed-history", """{"weeks":2}""", "email"),
                Triple("/seed-history", """{"email":" "}""", "email"),
                Triple("/seed-history", """{"email":"${app.email}","weeks":0}""", "weeks"),
                Triple("/seed-history", """{"email":"${app.email}","weeks":9}""", "weeks"),
                Triple("/reset", "{}", "email"),
            )) {
                val response = devPost(path, body)
                assertEquals(HttpStatusCode.BadRequest, response.status, "$path $body")
                assertEquals("VALIDATION_FAILED", response.errorCode())
                assertEquals(expectedField, field(response.json()), "$path $body")
            }
            for (path in listOf("/seed-history", "/reset")) {
                val unknown = devPost(path, """{"email":"nobody-${UUID.randomUUID()}@example.com"}""")
                assertEquals(HttpStatusCode.NotFound, unknown.status)
                assertEquals("NOT_FOUND", unknown.errorCode())
            }
        }
    }

    // ---- reports/generate ----

    @Test
    fun `reports generate builds, returns, and regenerates one report`() {
        val app = DevApp(NOW)
        app.test {
            createAccount(app.token)
            // Log in the current week (2026-05-17), then move to the next week so it's closed.
            val created = putEntry(app.token, UUID.randomUUID(), entryBody(quantity = 4, createdAt = "2026-05-20T03:00:00Z"))
            assertEquals(HttpStatusCode.Created, created.status)
            fun body(week: String, regenerate: Boolean = false) =
                """{"email":"${app.email}","weekStart":"$week","regenerate":$regenerate}"""

            assertEquals(HttpStatusCode.BadRequest, devPost("/reports/generate", body("2026-05-17")).status) // still open
            devPost("/clock", """{"now":"2026-05-27T04:00:00Z"}""")

            val generated = devPost("/reports/generate", body("2026-05-17"))
            assertEquals(HttpStatusCode.OK, generated.status, generated.bodyAsText())
            assertEquals(1, generated.json()["revision"]!!.jsonPrimitive.int)
            assertEquals("RES_SACHETS", generated.json()["hotspots"]!!.jsonArray.first().jsonObject["subcategory"]!!.jsonPrimitive.content)
            assertEquals(1, devPost("/reports/generate", body("2026-05-17")).json()["revision"]!!.jsonPrimitive.int) // idempotent
            assertEquals(2, devPost("/reports/generate", body("2026-05-17", regenerate = true)).json()["revision"]!!.jsonPrimitive.int)

            val empty = devPost("/reports/generate", body("2026-05-10"))
            assertEquals(HttpStatusCode.NotFound, empty.status)
            val notSunday = devPost("/reports/generate", body("2026-05-18"))
            assertEquals(HttpStatusCode.BadRequest, notSunday.status)
            assertEquals("weekStart", notSunday.json()["error"]!!.jsonObject["details"]!!.jsonObject["field"]!!.jsonPrimitive.content)
        }
    }

    private suspend fun ApplicationTestBuilder.reportList(token: String): JsonArray =
        Json.parseToJsonElement(client.get("/v1/reports") { bearerAuth(token) }.bodyAsText()).jsonArray

    private suspend fun ApplicationTestBuilder.report(token: String, week: String) =
        client.get("/v1/reports/$week") { bearerAuth(token) }.also { assertEquals(HttpStatusCode.OK, it.status) }.json()
}
