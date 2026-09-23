package app.menosan.photo

import app.menosan.ALICE_TOKEN
import app.menosan.BOB_TOKEN
import app.menosan.common.ApiException
import app.menosan.common.ErrorCode
import app.menosan.errorCode
import app.menosan.fixedClock
import app.menosan.interventions.FakeGemini
import app.menosan.json
import app.menosan.menosanTest
import app.menosan.taxonomy.Taxonomy
import app.menosan.testDeps
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhotoRoutesTest {
    /** Records what reached the analyzer and answers with [answer]. */
    private class RecordingAnalyzer(private val answer: () -> PhotoSuggestion = { SUGGESTION }) : PhotoAnalyzer {
        val calls = mutableListOf<Triple<UUID, ByteArray, String>>()
        override suspend fun analyze(userId: UUID, image: ByteArray, mimeType: String): PhotoSuggestion {
            calls += Triple(userId, image, mimeType)
            return answer()
        }
    }

    private companion object {
        val SUGGESTION = PhotoSuggestion("Coffee 3-in-1 sachet", "RESIDUAL", "RES_SACHETS", 5, 0.82)
        val JPEG_HEADER = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())

        fun jpeg(size: Int = 1024, marker: String = ""): ByteArray =
            (JPEG_HEADER + marker.toByteArray()).copyOf(maxOf(size, JPEG_HEADER.size + marker.length))
    }

    private suspend fun ApplicationTestBuilder.createAccount(token: String = ALICE_TOKEN) {
        client.post("/v1/account") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"consent":true}""")
        }
    }

    private suspend fun ApplicationTestBuilder.upload(
        bytes: ByteArray?,
        field: String = "image",
        token: String? = ALICE_TOKEN,
    ): HttpResponse = client.post("/v1/photo-analysis") {
        token?.let { bearerAuth(it) }
        setBody(
            MultiPartFormDataContent(
                formData {
                    append("note", "ignored text field")
                    if (bytes != null) {
                        append(
                            field, bytes,
                            Headers.build {
                                append(HttpHeaders.ContentType, "image/jpeg")
                                append(HttpHeaders.ContentDisposition, "filename=\"photo.jpg\"")
                            },
                        )
                    }
                },
            ),
        )
    }

    @Test
    fun `a JPEG upload returns the suggestion and the warning`() {
        val analyzer = RecordingAnalyzer()
        menosanTest(testDeps(photoAnalyzer = analyzer)) {
            createAccount()
            val image = jpeg(marker = "pixels")
            val response = upload(image)
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.json()
            val suggestion = body["suggestion"]!!.jsonObject
            assertEquals("Coffee 3-in-1 sachet", suggestion["name"]!!.jsonPrimitive.content)
            assertEquals("RESIDUAL", suggestion["category"]!!.jsonPrimitive.content)
            assertEquals("RES_SACHETS", suggestion["subcategory"]!!.jsonPrimitive.content)
            assertEquals("5", suggestion["quantity"]!!.jsonPrimitive.content)
            assertEquals("0.82", suggestion["confidence"]!!.jsonPrimitive.content)
            assertEquals(PHOTO_WARNING, body["warning"]!!.jsonPrimitive.content)

            val (_, bytes, mime) = analyzer.calls.single()
            assertContentEquals(image, bytes)
            assertEquals("image/jpeg", mime)
        }
    }

    @Test
    fun `auth is required, and an account`() = menosanTest(testDeps(photoAnalyzer = RecordingAnalyzer())) {
        assertEquals(HttpStatusCode.Unauthorized, upload(jpeg(), token = null).status)
        assertEquals(HttpStatusCode.Unauthorized, upload(jpeg(), token = "forged").status)
        val noAccount = upload(jpeg(), token = BOB_TOKEN)
        assertEquals(HttpStatusCode.NotFound, noAccount.status)
        assertEquals("ACCOUNT_NOT_FOUND", noAccount.errorCode())
    }

    @Test
    fun `size limit - exactly 2 MB is accepted, one byte more is 413`() {
        val analyzer = RecordingAnalyzer()
        menosanTest(testDeps(photoAnalyzer = analyzer)) {
            createAccount()
            assertEquals(HttpStatusCode.OK, upload(jpeg(MAX_IMAGE_BYTES)).status)
            val tooLarge = upload(jpeg(MAX_IMAGE_BYTES + 1))
            assertEquals(HttpStatusCode.PayloadTooLarge, tooLarge.status)
            assertEquals("IMAGE_TOO_LARGE", tooLarge.errorCode())
            val wayTooLarge = upload(jpeg(3 * 1024 * 1024)) // beyond the body cap, rejected before parsing
            assertEquals(HttpStatusCode.PayloadTooLarge, wayTooLarge.status)
            assertEquals("IMAGE_TOO_LARGE", wayTooLarge.errorCode())
            assertEquals(1, analyzer.calls.size, "a rejected upload never reaches the analyzer")
        }
    }

    @Test
    fun `bad uploads return 400 VALIDATION_FAILED with field image`() {
        val analyzer = RecordingAnalyzer()
        menosanTest(testDeps(photoAnalyzer = analyzer)) {
            createAccount()
            val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0, 0)
            for ((label, response) in listOf(
                "missing image" to upload(null),
                "wrong field name" to upload(jpeg(), field = "photo"),
                "empty image" to upload(ByteArray(0)),
                "not a JPEG" to upload(png),
            )) {
                assertEquals(HttpStatusCode.BadRequest, response.status, label)
                assertEquals("VALIDATION_FAILED", response.errorCode(), label)
                assertEquals("image", response.json()["error"]!!.jsonObject["details"]!!.jsonObject["field"]!!.jsonPrimitive.content, label)
            }

            val json = client.post("/v1/photo-analysis") {
                bearerAuth(ALICE_TOKEN)
                contentType(ContentType.Application.Json)
                setBody("""{"image":"base64..."}""")
            }
            assertEquals(HttpStatusCode.UnsupportedMediaType, json.status)
            assertEquals("VALIDATION_FAILED", json.errorCode())

            val malformed = client.post("/v1/photo-analysis") {
                bearerAuth(ALICE_TOKEN)
                setBody(
                    io.ktor.http.content.TextContent(
                        "--xyz\r\nContent-Disposition: form-data; name=\"image\"; filename=\"a.jpg\"\r\n\r\nno closing boundary",
                        ContentType.MultiPart.FormData.withParameter("boundary", "xyz"),
                    ),
                )
            }
            assertEquals(HttpStatusCode.BadRequest, malformed.status)
            assertEquals("VALIDATION_FAILED", malformed.errorCode())
            assertTrue(analyzer.calls.isEmpty())
        }
    }

    @Test
    fun `analyzer errors keep their codes`() {
        for ((code, status) in listOf(
            ErrorCode.NOT_WASTE to HttpStatusCode.UnprocessableEntity,
            ErrorCode.ANALYSIS_FAILED to HttpStatusCode.UnprocessableEntity,
            ErrorCode.RATE_LIMITED to HttpStatusCode.TooManyRequests,
        )) {
            val analyzer = RecordingAnalyzer { throw ApiException(code, "message") }
            menosanTest(testDeps(photoAnalyzer = analyzer)) {
                createAccount()
                val response = upload(jpeg())
                assertEquals(status, response.status, code.name)
                assertEquals(code.name, response.errorCode())
            }
        }
    }

    @Test
    fun `end to end with the Gemini analyzer, and nothing private reaches the logs`() {
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        root.addAppender(appender)
        val clock = fixedClock("2026-09-30T04:00:00Z")
        val taxonomy = Taxonomy.loadDefault()
        var answer = """{"isWaste":true,"name":"Sando bag","category":"RESIDUAL","subcategory":"RES_PLASTIC_BAGS","quantity":3,"confidence":0.9}"""
        val gemini = FakeGemini { answer }
        try {
            menosanTest(testDeps(clock = clock, photoAnalyzer = GeminiPhotoAnalyzer(gemini, taxonomy, clock))) {
                createAccount()
                val ok = upload(jpeg(marker = "IMAGE-MARKER-7f3a"))
                assertEquals(HttpStatusCode.OK, ok.status)
                assertEquals("RES_PLASTIC_BAGS", ok.json()["suggestion"]!!.jsonObject["subcategory"]!!.jsonPrimitive.content)
                assertTrue(String(gemini.requests.single().image!!.bytes).contains("IMAGE-MARKER-7f3a"))

                // A rejected answer is logged by reason only, never by content.
                answer = """{"isWaste":true,"name":"ANSWER-MARKER-91c2","category":"RESIDUAL","subcategory":"NOPE","quantity":3,"confidence":0.9}"""
                val rejected = upload(jpeg(marker = "IMAGE-MARKER-7f3a"))
                assertEquals("ANALYSIS_FAILED", rejected.errorCode())
            }
        } finally {
            root.detachAppender(appender)
        }
        val lines = appender.list.map { it.formattedMessage + " " + it.mdcPropertyMap }
        assertTrue(lines.any { it.contains("POST /v1/photo-analysis -> 200") }, "call logging should record the request: $lines")
        assertTrue(lines.any { it.contains("unknown subcategory") }, "the rejection reason should be logged: $lines")
        for (secret in listOf("IMAGE-MARKER-7f3a", "ANSWER-MARKER-91c2", ALICE_TOKEN, "Sando bag")) {
            assertFalse(lines.any { it.contains(secret) }, "log leaked '$secret': $lines")
        }
    }
}
