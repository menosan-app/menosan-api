package app.menosan.reports

import app.menosan.AppDeps
import app.menosan.FakeTokenVerifier
import app.menosan.account.ExposedUserRepository
import app.menosan.config.AppConfig
import app.menosan.db.DbHealthCheck
import app.menosan.errorCode
import app.menosan.json
import app.menosan.module
import app.menosan.plugins.VerifiedToken
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Builds the real app on the embedded Postgres, with two signed-in users who already have accounts. */
class ReportApp(val f: ReportFixtures = ReportFixtures(), jobKey: String? = null) {
    private val aliceUid = "uid-" + UUID.randomUUID()
    private val bobUid = "uid-" + UUID.randomUUID()
    val aliceToken = "token-" + UUID.randomUUID()
    val bobToken = "token-" + UUID.randomUUID()
    val alice: UUID = runBlocking { f.newUser(aliceUid) }
    val bob: UUID = runBlocking { f.newUser(bobUid) }

    val deps = AppDeps(
        config = AppConfig.from(
            buildMap {
                put("DATABASE_URL", "jdbc:postgresql://localhost/test")
                put("DATABASE_URL_DIRECT", "jdbc:postgresql://localhost/test")
                put("FIREBASE_PROJECT_ID", "menosan-test")
                put("FIREBASE_SERVICE_ACCOUNT_JSON_B64", "e30=")
                jobKey?.let { put("JOB_KEY", it) }
            },
        ),
        clock = f.clock,
        taxonomy = f.taxonomy,
        dbHealth = DbHealthCheck { true },
        tokenVerifier = FakeTokenVerifier(
            mapOf(aliceToken to VerifiedToken(aliceUid, null, null), bobToken to VerifiedToken(bobUid, null, null)),
        ),
        users = ExposedUserRepository(f.db),
        reports = f.service,
        interventions = f.engine,
    )

    fun test(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { module(deps) }
        block()
    }
}

class ReportRoutesTest {

    @Test
    fun `list and detail follow the contract shape`() {
        val app = ReportApp()
        runBlocking {
            app.f.recommendable("RES_SACHETS", count = 1)
            app.f.log(app.alice, W1, "RES_SACHETS", 4)
            app.f.log(app.alice, W2, "RES_SACHETS", 3)
            app.f.log(app.alice, W2, "SPC_BATTERIES", 1)
        }
        app.test {
            val list = client.get("/v1/reports") { bearerAuth(app.aliceToken) }
            assertEquals(HttpStatusCode.OK, list.status)
            val rows = Json.parseToJsonElement(list.bodyAsText()).jsonArray
            assertEquals(listOf("2026-09-20", "2026-09-13"), rows.map { it.jsonObject["weekStart"]!!.jsonPrimitive.content })
            assertEquals(
                setOf("weekStart", "weekEnd", "analyzedQuantity", "hotspotCount", "adoptedCount", "isLatest"),
                rows[0].jsonObject.keys,
            )

            val detail = client.get("/v1/reports/2026-09-20") { bearerAuth(app.aliceToken) }
            assertEquals(HttpStatusCode.OK, detail.status)
            val body = detail.json()
            assertEquals(
                setOf("weekStart", "weekEnd", "revision", "isLatest", "stats", "hotspots", "comparison", "impacts"),
                body.keys,
            )
            assertEquals(
                setOf("analyzedTotals", "categories", "subcategories", "special"),
                body["stats"]!!.jsonObject.keys,
            )
            val hotspot = body["hotspots"]!!.jsonArray.single().jsonObject
            assertEquals(
                setOf("rank", "subcategory", "criteria", "frequency", "quantity", "score", "recommendations"),
                hotspot.keys,
            )
            assertEquals(
                setOf(
                    "interventionId", "code", "type", "title", "description", "howTo",
                    "costLevel", "effort", "note", "continued", "adopted",
                ),
                hotspot["recommendations"]!!.jsonArray.single().jsonObject.keys,
            )
            val comparison = body["comparison"]!!.jsonObject
            assertEquals(setOf("previousWeekStart", "total", "categories", "subcategories"), comparison.keys)
            assertEquals(
                setOf("previous", "current", "delta", "deltaPct", "trend"),
                comparison["total"]!!.jsonObject.keys,
            )
        }
    }

    @Test
    fun `another user's report is not found and their list is empty`() {
        val app = ReportApp()
        runBlocking { app.f.log(app.alice, W2, "RES_SACHETS", 3) }
        app.test {
            assertEquals(HttpStatusCode.OK, client.get("/v1/reports/2026-09-20") { bearerAuth(app.aliceToken) }.status)
            val other = client.get("/v1/reports/2026-09-20") { bearerAuth(app.bobToken) }
            assertEquals(HttpStatusCode.NotFound, other.status)
            assertEquals("NOT_FOUND", other.errorCode())
            assertEquals("[]", client.get("/v1/reports") { bearerAuth(app.bobToken) }.bodyAsText())
        }
    }

    @Test
    fun `week parameter is validated and open weeks have no report`() {
        val app = ReportApp()
        runBlocking { app.f.log(app.alice, W3, "RES_SACHETS", 3) }
        app.test {
            for (bad in listOf("2026-09-21", "2026-13-01", "latest")) {
                val response = client.get("/v1/reports/$bad") { bearerAuth(app.aliceToken) }
                assertEquals(HttpStatusCode.BadRequest, response.status, bad)
                assertEquals("VALIDATION_FAILED", response.errorCode())
            }
            val open = client.get("/v1/reports/2026-09-27") { bearerAuth(app.aliceToken) }
            assertEquals(HttpStatusCode.NotFound, open.status)
            assertEquals("NOT_FOUND", open.errorCode())
        }
    }

    @Test
    fun `reports need authentication`() {
        val app = ReportApp()
        app.test {
            for (path in listOf("/v1/reports", "/v1/reports/2026-09-20")) {
                val response = client.get(path)
                assertEquals(HttpStatusCode.Unauthorized, response.status, path)
                assertTrue(response.errorCode() == "UNAUTHENTICATED")
            }
        }
    }
}
