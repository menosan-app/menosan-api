package app.menosan.account

import app.menosan.ALICE_EMAIL
import app.menosan.ALICE_TOKEN
import app.menosan.BOB_TOKEN
import app.menosan.InMemoryUserRepository
import app.menosan.errorCode
import app.menosan.json
import app.menosan.menosanTest
import app.menosan.testDeps
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class AccountRoutesTest {
    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.createAccount(token: String, body: String = """{"consent":true}""") =
        client.post("/v1/account") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    @Test
    fun `missing token returns 401 UNAUTHENTICATED`() = menosanTest {
        val response = client.get("/v1/me")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals("UNAUTHENTICATED", response.errorCode())
    }

    @Test
    fun `malformed authorization header returns 401`() = menosanTest {
        for (value in listOf("Basic abc", "Bearer", "Bearer    ", ALICE_TOKEN)) {
            val response = client.get("/v1/me") { header(HttpHeaders.Authorization, value) }
            assertEquals(HttpStatusCode.Unauthorized, response.status, value)
        }
    }

    @Test
    fun `invalid token returns 401 on every private route`() = menosanTest {
        for (path in listOf("/v1/me", "/v1/weeks/current")) {
            val response = client.get(path) { bearerAuth("forged") }
            assertEquals(HttpStatusCode.Unauthorized, response.status, path)
            assertEquals("UNAUTHENTICATED", response.errorCode())
        }
        assertEquals(HttpStatusCode.Unauthorized, createAccount("forged").status)
    }

    @Test
    fun `valid token without account returns 404 ACCOUNT_NOT_FOUND`() = menosanTest {
        for (path in listOf("/v1/me", "/v1/weeks/current")) {
            val response = client.get(path) { bearerAuth(ALICE_TOKEN) }
            assertEquals(HttpStatusCode.NotFound, response.status, path)
            assertEquals("ACCOUNT_NOT_FOUND", response.errorCode())
        }
    }

    @Test
    fun `create account is idempotent - 201 then 200`() = menosanTest {
        val first = createAccount(ALICE_TOKEN)
        assertEquals(HttpStatusCode.Created, first.status)
        val second = createAccount(ALICE_TOKEN)
        assertEquals(HttpStatusCode.OK, second.status)
        assertEquals(first.json()["id"], second.json()["id"])

        val me = client.get("/v1/me") { bearerAuth(ALICE_TOKEN) }
        assertEquals(HttpStatusCode.OK, me.status)
        val body = me.json()
        assertEquals(first.json()["id"], body["id"])
        assertEquals(ALICE_EMAIL, body["email"]!!.jsonPrimitive.content)
        assertEquals("Alice", body["displayName"]!!.jsonPrimitive.content)
        assertEquals("2026-09-30T04:00:00Z", body["createdAt"]!!.jsonPrimitive.content)
    }

    @Test
    fun `consent is required`() = menosanTest {
        for (body in listOf("""{"consent":false}""", "{}", "")) {
            val response = createAccount(ALICE_TOKEN, body)
            assertEquals(HttpStatusCode.BadRequest, response.status, body)
            assertEquals("VALIDATION_FAILED", response.errorCode())
        }
        assertEquals(HttpStatusCode.NotFound, client.get("/v1/me") { bearerAuth(ALICE_TOKEN) }.status)
    }

    @Test
    fun `malformed json returns VALIDATION_FAILED`() = menosanTest {
        val response = createAccount(ALICE_TOKEN, """{"consent":""")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("VALIDATION_FAILED", response.errorCode())
    }

    @Test
    fun `each user only sees their own account`() {
        val users = InMemoryUserRepository()
        menosanTest(testDeps(users = users)) {
            createAccount(ALICE_TOKEN)
            createAccount(BOB_TOKEN)
            val alice = client.get("/v1/me") { bearerAuth(ALICE_TOKEN) }.json()
            val bob = client.get("/v1/me") { bearerAuth(BOB_TOKEN) }.json()
            assertEquals(ALICE_EMAIL, alice["email"]!!.jsonPrimitive.content)
            assertEquals("bob@example.com", bob["email"]!!.jsonPrimitive.content)
            assertEquals(2, users.users.size)
        }
    }
}
