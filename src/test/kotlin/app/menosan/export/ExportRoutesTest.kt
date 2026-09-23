package app.menosan.export

import app.menosan.PREVIOUS_WEEK
import app.menosan.createAccount
import app.menosan.dbTest
import app.menosan.deleteTestIntervention
import app.menosan.entryBody
import app.menosan.errorCode
import app.menosan.insertTestIntervention
import app.menosan.json
import app.menosan.putEntry
import app.menosan.seedReportGraph
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class ExportRoutesTest {
    private fun JsonObject.str(key: String) = this[key]!!.jsonPrimitive.content
    private fun JsonObject.obj(key: String) = this[key]!!.jsonObject
    private fun JsonObject.list(key: String) = this[key]!!.jsonArray.map { it.jsonObject }

    @Test
    fun `export contains the profile, all entries, and full reports - and nothing of other users`() {
        val interventionId = runBlocking { insertTestIntervention() }
        try {
            dbTest { env ->
                val aliceId = createAccount(env.aliceToken)
                val bobId = createAccount(env.bobToken)
                val older = UUID.randomUUID()
                val newer = UUID.randomUUID()
                putEntry(env.aliceToken, newer, entryBody(name = "Shampoo sachet"))
                putEntry(env.aliceToken, older, entryBody(name = "Sando bag", subcategory = "RES_PLASTIC_BAGS", createdAt = "2026-09-22T02:00:00Z"))
                putEntry(env.bobToken, UUID.randomUUID(), entryBody(name = "Bob's bottle"))
                seedReportGraph(aliceId, interventionId, PREVIOUS_WEEK)
                seedReportGraph(bobId, interventionId, PREVIOUS_WEEK.minusDays(7))

                val response = client.get("/v1/export") { bearerAuth(env.aliceToken) }
                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals("attachment; filename=menosan-export-2026-09-30.json", response.headers[HttpHeaders.ContentDisposition])
                assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
                assertEquals(listOf(aliceId), env.reports.catchUps.toList())

                val doc = response.json()
                assertEquals("menosan-export", doc.str("format"))
                assertEquals(1, doc["exportVersion"]!!.jsonPrimitive.int)
                assertEquals("2026-09-30T04:00:00Z", doc.str("exportedAt"))
                assertEquals("Asia/Manila", doc.str("timezone"))

                val profile = doc.obj("profile")
                assertEquals(aliceId.toString(), profile.str("id"))
                assertEquals("Alice", profile.str("displayName"))
                assertEquals("2026-09-30T04:00:00Z", profile.str("consentedAt"))

                val entries = doc.list("entries")
                assertEquals(listOf(older, newer).map { it.toString() }, entries.map { it.str("id") }) // oldest first
                assertEquals(listOf("Sando bag", "Shampoo sachet"), entries.map { it.str("name") })
                assertEquals(PREVIOUS_WEEK.toString(), entries[0].str("weekStart"))

                val report = doc.list("reports").single()
                assertEquals(PREVIOUS_WEEK.toString(), report.str("weekStart"))
                assertEquals("2026-09-26", report.str("weekEnd"))
                assertEquals(1, report["revision"]!!.jsonPrimitive.int)
                assertEquals(3, report.obj("stats").obj("analyzedTotals")["quantity"]!!.jsonPrimitive.int)
                assertEquals(JsonNull, report["comparison"])

                val hotspot = report.list("hotspots").single()
                assertEquals("RES_SACHETS", hotspot.str("subcategory"))
                assertEquals(listOf("MOST_FREQUENT", "HIGHEST_QUANTITY", "AVOIDABLE"), hotspot["criteria"]!!.jsonArray.map { it.jsonPrimitive.content })
                assertEquals(1.0, hotspot["score"]!!.jsonPrimitive.double)
                val recommendation = hotspot.list("recommendations").single()
                assertEquals(interventionId.toString(), recommendation.str("interventionId"))
                assertEquals("Test refill", recommendation.str("title"))
                assertEquals("RULES", recommendation.str("source"))

                val adoption = report.list("adoptions").single()
                assertEquals("RES_SACHETS", adoption.str("targetSubcategory"))
                assertEquals(3, adoption["baselineQuantity"]!!.jsonPrimitive.int)
                val impact = adoption.obj("impact")
                assertEquals("2026-09-27", impact.str("followupWeekStart"))
                assertEquals("DECREASED", impact.str("result"))

                val text = response.json().toString()
                assertEquals(false, text.contains("Bob's bottle"))
                assertEquals(false, text.contains(bobId.toString()))

                client.delete("/v1/account") { bearerAuth(env.aliceToken) }
                client.delete("/v1/account") { bearerAuth(env.bobToken) }
            }
        } finally {
            runBlocking { deleteTestIntervention(interventionId) }
        }
    }

    @Test
    fun `a new account exports empty lists`() = dbTest { env ->
        createAccount(env.aliceToken)
        val doc = client.get("/v1/export") { bearerAuth(env.aliceToken) }.json()
        assertEquals(emptyList(), doc.list("entries"))
        assertEquals(emptyList(), doc.list("reports"))
    }

    @Test
    fun `export requires a token and an account`() = dbTest { env ->
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/export").status)
        assertEquals("ACCOUNT_NOT_FOUND", client.get("/v1/export") { bearerAuth(env.noAccountToken) }.errorCode())
    }
}
