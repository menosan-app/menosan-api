package app.menosan.e2e

import app.menosan.common.WeekCalc
import app.menosan.jobs.JOB_KEY_HEADER
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.UUID

class ApiResponse(val status: Int, val body: String) {
    fun obj(): JsonObject = Json.parseToJsonElement(body).jsonObject
    fun array(): JsonArray = Json.parseToJsonElement(body).jsonArray
    fun errorCode(): String? = obj()["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content
}

/** How the scenario talks to the API: the in-process test client, or real HTTP to a deployed server. */
fun interface ApiDriver {
    suspend fun send(method: String, path: String, body: String?, token: String?, jobKey: String?): ApiResponse
}

class KtorTestDriver(private val client: HttpClient) : ApiDriver {
    override suspend fun send(method: String, path: String, body: String?, token: String?, jobKey: String?): ApiResponse {
        val response = client.request(path) {
            this.method = HttpMethod.parse(method)
            token?.let { bearerAuth(it) }
            jobKey?.let { header(JOB_KEY_HEADER, it) }
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
        return ApiResponse(response.status.value, response.bodyAsText())
    }
}

/** Plain JDK HTTP client, so the staging run needs no extra dependency. */
class JdkHttpDriver(baseUrl: String) : ApiDriver {
    private val base = baseUrl.trimEnd('/')
    private val http = java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()

    override suspend fun send(method: String, path: String, body: String?, token: String?, jobKey: String?): ApiResponse =
        withContext(Dispatchers.IO) {
            // Generous timeout: a cold start plus report generation (Gemini) on the first read.
            val request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(120))
            token?.let { request.header("Authorization", "Bearer $it") }
            jobKey?.let { request.header(JOB_KEY_HEADER, it) }
            if (body != null) {
                request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body))
            } else {
                request.method(method, HttpRequest.BodyPublishers.noBody())
            }
            val response = http.send(request.build(), HttpResponse.BodyHandlers.ofString())
            ApiResponse(response.statusCode(), response.body())
        }
}

/**
 * The backend's core loop end to end (plan §9 BE-5): create the account → log entries in week A → roll the
 * clock → report A with hotspots and library recommendations → adopt one → log less of it in week B → roll
 * the clock → report B shows the comparison and the adoption's impact → report A is locked.
 *
 * It moves the **server-wide** clock with `/internal/dev/clock` and starts and ends with
 * `/internal/dev/reset`, which deletes every entry and report of the token's account. Use a throwaway
 * account, and don't run it on staging while people are testing there.
 */
class EndToEndScenario(
    private val api: ApiDriver,
    private val token: String,
    private val jobKey: String,
    private val log: (String) -> Unit = {},
) {
    /** [weekAStart] is any instant in week A. Week A, and the week after it, must be in the past. */
    suspend fun run(weekAStart: Instant) {
        expect(200, call("GET", "/health"), "health")
        val account = call("POST", "/v1/account", """{"consent":true}""", token)
        check(account.status == 200 || account.status == 201) { "create account: ${account.status} ${account.body}" }
        val email = expect(200, call("GET", "/v1/me", token = token), "me").obj().string("email")
        log("Signed in; account ready")

        try {
            dev("/reset", """{"email":"$email"}""")
            setClock(weekAStart)

            // ---- Week A: 10 sachets, 2 PET bottles, 1 leftover ----
            val weekA = currentWeek()
            val sachetId = logEntry("Coffee 3-in-1 sachet", "RES_SACHETS", 5, minutesAgo = 4)
            logEntry("Shampoo sachet", "RES_SACHETS", 5, minutesAgo = 3)
            logEntry("Softdrink bottle", "REC_PET_BOTTLES", 2, minutesAgo = 2)
            logEntry("Leftover rice", "BIO_FOOD_LEFTOVERS", 1, minutesAgo = 1)
            val listed = expect(200, call("GET", "/v1/entries", token = token), "list entries").obj()
            assertThat(listed["entries"]!!.jsonArray.size == 4, "week A lists 4 entries", listed.toString())
            log("Week $weekA: logged 4 entries")

            // ---- Roll to week B: report A is ready and adoptable ----
            setClock(weekAStart.plus(Duration.ofDays(7)))
            val weekB = currentWeek()
            val list = expect(200, call("GET", "/v1/reports", token = token), "list reports").array()
            val latest = list.first().jsonObject
            assertThat(latest.string("weekStart") == weekA && latest["isLatest"]!!.jsonPrimitive.boolean, "report A is the latest", list.toString())

            val reportA = expect(200, call("GET", "/v1/reports/$weekA", token = token), "report A").obj()
            val top = reportA["hotspots"]!!.jsonArray.first().jsonObject
            assertThat(top.string("subcategory") == "RES_SACHETS" && top["quantity"]!!.jsonPrimitive.int == 10, "top hotspot is 10 sachets", top.toString())
            val pick = top["recommendations"]!!.jsonArray.firstOrNull()?.jsonObject
            assertThat(pick != null, "the sachet hotspot has recommendations", top.toString())
            val interventionId = pick!!.string("interventionId")

            val adopted = expect(200, call("POST", "/v1/reports/$weekA/adoptions", """{"interventionIds":["$interventionId"]}""", token), "adopt").obj()
            val adoptedFlag = adopted["hotspots"]!!.jsonArray.flatMap { it.jsonObject["recommendations"]!!.jsonArray }
                .map { it.jsonObject }.single { it.string("interventionId") == interventionId }["adopted"]!!.jsonPrimitive.boolean
            assertThat(adoptedFlag, "the adopted recommendation is flagged", adopted.toString())
            log("Week $weekB: report $weekA has ${reportA["hotspots"]!!.jsonArray.size} hotspot(s); adopted ${pick.string("code")}")

            // Week A is closed now: its entries are read-only.
            val lateEdit = call(
                "PUT", "/v1/entries/$sachetId",
                entryJson("Coffee 3-in-1 sachet", "RES_SACHETS", 1, Instant.parse(listed.entryCreatedAt(sachetId))), token,
            )
            assertThat(lateEdit.status == 409 && lateEdit.errorCode() == "WEEK_CLOSED", "editing a closed week is refused", lateEdit.body)

            // ---- Week B: 4 sachets, 2 PET bottles ----
            logEntry("Coffee 3-in-1 sachet", "RES_SACHETS", 4, minutesAgo = 2)
            logEntry("Water bottle", "REC_PET_BOTTLES", 2, minutesAgo = 1)

            // ---- Roll to week C: report B shows the comparison and the impact ----
            setClock(weekAStart.plus(Duration.ofDays(14)))
            val reportB = expect(200, call("GET", "/v1/reports/$weekB", token = token), "report B").obj()
            val total = reportB["comparison"]!!.jsonObject["total"]!!.jsonObject
            assertThat(
                total["previous"]!!.jsonPrimitive.int == 13 && total["current"]!!.jsonPrimitive.int == 6 && total.string("trend") == "DECREASED",
                "comparison 13 → 6 DECREASED", total.toString(),
            )
            val impact = reportB["impacts"]!!.jsonArray.single().jsonObject
            assertThat(
                impact.string("interventionId") == interventionId && impact.string("targetSubcategory") == "RES_SACHETS" &&
                    impact["baselineQuantity"]!!.jsonPrimitive.int == 10 && impact["followupQuantity"]!!.jsonPrimitive.int == 4 &&
                    impact.string("result") == "DECREASED",
                "impact 10 → 4 DECREASED", impact.toString(),
            )
            val locked = call("POST", "/v1/reports/$weekA/adoptions", """{"interventionIds":["$interventionId"]}""", token)
            assertThat(locked.status == 409 && locked.errorCode() == "ADOPTION_WINDOW_CLOSED", "report A is locked", locked.body)
            log("Week ${currentWeek()}: report $weekB shows 13 → 6 pieces and the sachet change DECREASED (10 → 4)")
        } finally {
            // Leave the account empty and the server on real time, even after a failure.
            runCatching { dev("/reset", """{"email":"$email"}""") }
            runCatching { dev("/clock", "{}") }
            log("Cleaned up: account data reset, server clock restored")
        }
    }

    private suspend fun call(method: String, path: String, body: String? = null, token: String? = null, jobKey: String? = null) =
        api.send(method, path, body, token, jobKey)

    private suspend fun dev(path: String, body: String) = expect(200, call("POST", "/internal/dev$path", body, jobKey = jobKey), "dev $path")

    private suspend fun setClock(now: Instant) = dev("/clock", """{"now":"$now"}""")

    private suspend fun currentWeek(): String =
        expect(200, call("GET", "/v1/weeks/current", token = token), "current week").obj().string("weekStart")

    private suspend fun serverNow(): Instant =
        Instant.parse(expect(200, call("GET", "/v1/weeks/current", token = token), "server now").obj().string("serverNow"))

    /** Logs an entry a few minutes before the server's "now", as the app would. Returns its id. */
    private suspend fun logEntry(name: String, subcategory: String, quantity: Int, minutesAgo: Long): UUID {
        val id = UUID.randomUUID()
        val createdAt = serverNow().minus(Duration.ofMinutes(minutesAgo))
        expect(201, call("PUT", "/v1/entries/$id", entryJson(name, subcategory, quantity, createdAt), token), "log $subcategory")
        return id
    }

    private fun entryJson(name: String, subcategory: String, quantity: Int, createdAt: Instant) =
        """{"name":"$name","subcategory":"$subcategory","quantity":$quantity,"source":"MANUAL","createdAt":"$createdAt"}"""

    private fun JsonObject.entryCreatedAt(id: UUID): String =
        this["entries"]!!.jsonArray.map { it.jsonObject }.single { it.string("id") == id.toString() }.string("createdAt")

    private fun JsonObject.string(key: String): String = this[key]!!.jsonPrimitive.content

    private fun expect(status: Int, response: ApiResponse, what: String): ApiResponse {
        if (response.status != status) throw AssertionError("$what: expected HTTP $status, got ${response.status}: ${response.body}")
        return response
    }

    private fun assertThat(condition: Boolean, what: String, context: String) {
        if (!condition) throw AssertionError("Expected $what. Got: $context")
    }

    companion object {
        /** Wednesday 12:00 PHT, five weeks before [realNow]'s week: in the past, so the run leaves no future-dated data. */
        fun weekAStartFor(realNow: Instant): Instant =
            WeekCalc.startInstant(WeekCalc.weekStart(realNow).minusWeeks(5)).plus(Duration.ofHours(3 * 24 + 12))
    }
}
