package app.menosan.interventions

import app.menosan.AppDeps
import app.menosan.FakeTokenVerifier
import app.menosan.account.ExposedUserRepository
import app.menosan.common.OverridableClock
import app.menosan.db.DbHealthCheck
import app.menosan.db.PostgresTestDb
import app.menosan.errorCode
import app.menosan.json
import app.menosan.module
import app.menosan.plugins.VerifiedToken
import app.menosan.reports.DefaultReportService
import app.menosan.reports.NOW_IN_W3
import app.menosan.reports.NOW_IN_W4
import app.menosan.reports.ReportStore
import app.menosan.reports.W2
import app.menosan.reports.W3
import app.menosan.taxonomy.Taxonomy
import app.menosan.testConfig
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import app.menosan.reports.ReportFixtures as Be3Fixtures

/**
 * BE-3 × BE-4 end to end: the real library engine (Gemini failing → rules) inside BE-3's DefaultReportService,
 * adoption through HTTP answering with the full report, then next week's impact and "Keep it up" pinning.
 */
class AdoptionReportFlowTest {
    private val db = PostgresTestDb.db
    private val taxonomy = Taxonomy.loadDefault()
    private val clock = OverridableClock().apply { setOverride(NOW_IN_W3) } // latest report = W2
    private val entries = Be3Fixtures()
    private val uid = "uid-flow-" + UUID.randomUUID()

    private val deps = run {
        val engine = LibraryInterventionEngine(ExposedInterventionRepository(db), FakeGemini.failing(), taxonomy)
        AppDeps(
            config = testConfig, clock = clock, taxonomy = taxonomy, dbHealth = DbHealthCheck { true },
            tokenVerifier = FakeTokenVerifier(mapOf("t" to VerifiedToken(uid, null, null))),
            users = ExposedUserRepository(db),
            reports = DefaultReportService(ReportStore(db), clock, taxonomy, engine),
            interventions = engine,
            adoptions = ExposedAdoptionService(db, clock),
        )
    }

    private fun JsonObject.hotspot(code: String): JsonObject =
        this["hotspots"]!!.jsonArray.map { it.jsonObject }.single { it["subcategory"]!!.jsonPrimitive.content == code }

    private fun JsonObject.recommendations(): List<JsonObject> = this["recommendations"]!!.jsonArray.map { it.jsonObject }

    private fun JsonObject.str(key: String) = this[key]!!.jsonPrimitive.content

    @Test
    fun `recommend, adopt with the full report, then measure impact and pin what worked`() = testApplication {
        application { module(deps) }
        val user = entries.newUser(uid)
        entries.log(user, W2, "RES_SACHETS", 6)
        entries.log(user, W2, "RES_SACHETS", 4)
        entries.log(user, W2, "RES_PLASTIC_BAGS", 3)

        // Report W2: real library items in rule order (all SAVES_MONEY; LOW before MEDIUM; then code).
        val report = client.get("/v1/reports/$W2") { bearerAuth("t") }
        assertEquals(HttpStatusCode.OK, report.status)
        val sachets = report.json().hotspot("RES_SACHETS").recommendations()
        assertEquals(
            listOf("RES_SACHETS_REFILL_STATION", "RES_SACHETS_TAKAL_COFFEE_SUGAR", "RES_SACHETS_NEXT_SIZE_UP"),
            sachets.map { it.str("code") },
        )
        assertTrue(sachets.none { it["adopted"]!!.jsonPrimitive.boolean })
        val refill = sachets.first().str("interventionId")

        // Adopt → 200 with the full §8.3 report, adopted flag set.
        val adopted = client.post("/v1/reports/$W2/adoptions") {
            bearerAuth("t"); contentType(ContentType.Application.Json); setBody("""{"interventionIds":["$refill"]}""")
        }
        assertEquals(HttpStatusCode.OK, adopted.status)
        val body = adopted.json()
        assertEquals(W2.toString(), body.str("weekStart"))
        assertEquals(true, body["isLatest"]!!.jsonPrimitive.boolean)
        assertEquals(
            listOf(true, false, false),
            body.hotspot("RES_SACHETS").recommendations().map { it["adopted"]!!.jsonPrimitive.boolean },
        )
        val list = Json.parseToJsonElement(client.get("/v1/reports") { bearerAuth("t") }.bodyAsText())
        assertEquals(1, list.jsonArray.single { it.jsonObject.str("weekStart") == W2.toString() }.jsonObject["adoptedCount"]!!.jsonPrimitive.int)

        // Next week: sachets went down from 10 to 4.
        clock.setOverride(NOW_IN_W4) // latest report = W3
        entries.log(user, W3, "RES_SACHETS", 4)
        val next = client.get("/v1/reports/$W3") { bearerAuth("t") }.json()
        val impact = next["impacts"]!!.jsonArray.single().jsonObject
        assertEquals(refill, impact.str("interventionId"))
        assertEquals("RES_SACHETS", impact.str("targetSubcategory"))
        assertEquals(10, impact["baselineQuantity"]!!.jsonPrimitive.int)
        assertEquals(4, impact["followupQuantity"]!!.jsonPrimitive.int)
        assertEquals("DECREASED", impact.str("result"))

        // What worked is pinned first as "Keep it up".
        val pinned = next.hotspot("RES_SACHETS").recommendations().first()
        assertEquals(refill, pinned.str("interventionId"))
        assertEquals(true, pinned["continued"]!!.jsonPrimitive.boolean)

        // W2 is no longer the latest report.
        val closed = client.post("/v1/reports/$W2/adoptions") {
            bearerAuth("t"); contentType(ContentType.Application.Json); setBody("""{"interventionIds":["$refill"]}""")
        }
        assertEquals(HttpStatusCode.Conflict, closed.status)
        assertEquals("ADOPTION_WINDOW_CLOSED", closed.errorCode())
    }
}
