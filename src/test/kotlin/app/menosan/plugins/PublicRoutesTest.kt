package app.menosan.plugins

import app.menosan.ALICE_TOKEN
import app.menosan.errorCode
import app.menosan.fixedClock
import app.menosan.json
import app.menosan.menosanTest
import app.menosan.testDeps
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PublicRoutesTest {
    @Test
    fun `health is ok when the db is up`() = menosanTest {
        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("ok", response.json()["db"]!!.jsonPrimitive.content)
    }

    @Test
    fun `health is 503 when the db is down`() = menosanTest(testDeps(dbHealthy = false)) {
        val response = client.get("/health")
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals("down", response.json()["db"]!!.jsonPrimitive.content)
    }

    @Test
    fun `taxonomy is public and complete`() = menosanTest {
        val response = client.get("/v1/taxonomy")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.json()
        assertEquals(1, body["version"]!!.jsonPrimitive.content.toInt())
        assertEquals(4, body["categories"]!!.jsonArray.size)
        val subcategories = body["subcategories"]!!.jsonArray
        assertEquals(25, subcategories.size)
        val sachets = subcategories.first { it.jsonObject["code"]!!.jsonPrimitive.content == "RES_SACHETS" }.jsonObject
        assertEquals("RESIDUAL", sachets["category"]!!.jsonPrimitive.content)
        assertEquals("true", sachets["avoidable"]!!.jsonPrimitive.content)
    }

    @Test
    fun `unknown route returns the JSON NOT_FOUND error`() = menosanTest {
        for (path in listOf("/nope", "/v1/nope", "/v1/entries/123")) {
            val response = client.get(path)
            assertEquals(HttpStatusCode.NotFound, response.status, path)
            assertEquals("NOT_FOUND", response.errorCode(), path)
        }
    }

    @Test
    fun `error body has code, message and details`() = menosanTest {
        val error = client.get("/v1/me").json()["error"]!!.jsonObject
        assertEquals(setOf("code", "message", "details"), error.keys)
    }

    @Test
    fun `request id is generated, or echoed when the client sends a sane one`() = menosanTest {
        val generated = client.get("/health").headers[REQUEST_ID_HEADER]
        assertNotNull(generated)
        assertTrue(generated.isNotBlank())
        val echoed = client.get("/health") { header(REQUEST_ID_HEADER, "android-abc-123") }
        assertEquals("android-abc-123", echoed.headers[REQUEST_ID_HEADER])
        val rejected = client.get("/health") { header(REQUEST_ID_HEADER, "bad id!<script>") }
        assertEquals(HttpStatusCode.OK, rejected.status) // replaced with a generated id, not rejected
        assertTrue(rejected.headers[REQUEST_ID_HEADER] != "bad id!<script>")
    }

    @Test
    fun `current week straddles the Saturday 16-00 UTC boundary`() {
        fun weekAt(iso: String): Map<String, String> {
            var result: Map<String, String> = emptyMap()
            menosanTest(testDeps(clock = fixedClock(iso))) {
                client.post("/v1/account") {
                    bearerAuth(ALICE_TOKEN); contentType(ContentType.Application.Json); setBody("""{"consent":true}""")
                }
                val response = client.get("/v1/weeks/current") { bearerAuth(ALICE_TOKEN) }
                assertEquals(HttpStatusCode.OK, response.status)
                result = response.json().mapValues { it.value.jsonPrimitive.content }
            }
            return result
        }
        run {
            val before = weekAt("2026-10-03T15:59:59Z")
            assertEquals("2026-09-27", before["weekStart"])
            assertEquals("2026-10-03", before["weekEnd"])
            assertEquals("Asia/Manila", before["timezone"])
            assertEquals("2026-10-03T15:59:59Z", before["serverNow"])

            val after = weekAt("2026-10-03T16:00:00Z")
            assertEquals("2026-10-04", after["weekStart"])
            assertEquals("2026-10-10", after["weekEnd"])
        }
    }
}
