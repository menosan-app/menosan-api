package app.menosan.photo

import app.menosan.common.ApiException
import app.menosan.common.GeminiException
import app.menosan.fixedClock
import app.menosan.interventions.FakeGemini
import app.menosan.taxonomy.Taxonomy
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class GeminiPhotoAnalyzerTest {
    private val taxonomy = Taxonomy.loadDefault()
    private val clock = fixedClock("2026-09-30T04:00:00Z")
    private val alice = UUID.fromString("00000000-0000-0000-0000-00000000a11c")
    private val image = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3)

    private fun analyzer(gemini: FakeGemini, limit: Int = GeminiPhotoAnalyzer.DAILY_LIMIT) =
        GeminiPhotoAnalyzer(gemini, taxonomy, clock, timeout = 200.milliseconds, limiter = DailyRateLimiter(limit, clock))

    private fun answer(
        isWaste: Any? = true,
        name: Any? = "\"Coffee 3-in-1 sachet\"",
        category: Any? = "\"RESIDUAL\"",
        subcategory: Any? = "\"RES_SACHETS\"",
        quantity: Any? = 5,
        confidence: Any? = 0.82,
    ): String = listOf(
        "isWaste" to isWaste, "name" to name, "category" to category,
        "subcategory" to subcategory, "quantity" to quantity, "confidence" to confidence,
    ).filter { it.second != null }.joinToString(",", "{", "}") { (k, v) -> "\"$k\":$v" }

    private fun analyze(raw: String): PhotoSuggestion = runBlocking { analyzer(FakeGemini { raw }).analyze(alice, image, "image/jpeg") }

    private fun assertCode(code: String, raw: String) {
        val e = assertFailsWith<ApiException>(raw) { analyze(raw) }
        assertEquals(code, e.code.name, raw)
    }

    @Test
    fun `a valid answer becomes a suggestion`() = runBlocking {
        val gemini = FakeGemini { answer(name = "\"  Coffee   3-in-1\\n sachet \"") }
        val suggestion = analyzer(gemini).analyze(alice, image, "image/jpeg")
        assertEquals(PhotoSuggestion("Coffee 3-in-1 sachet", "RESIDUAL", "RES_SACHETS", 5, 0.82), suggestion)

        val request = gemini.requests.single()
        assertContentEquals(image, request.image!!.bytes)
        assertEquals("image/jpeg", request.image!!.mimeType)
        assertEquals(200.milliseconds, request.timeout)
        assertFalse(alice.toString() in request.prompt + request.systemInstruction, "the user id must not reach Gemini")
    }

    @Test
    fun `the prompt lists the whole taxonomy and the schema constrains subcategory to its codes`() = runBlocking {
        val gemini = FakeGemini { answer() }
        analyzer(gemini).analyze(alice, image, "image/jpeg")
        val request = gemini.requests.single()

        val instruction = request.systemInstruction!!
        assertFalse("{{TAXONOMY}}" in instruction)
        taxonomy.subcategories.forEach { assertTrue("${it.code} | ${it.category} | ${it.label}" in instruction, it.code) }

        val properties = Json.parseToJsonElement(request.responseSchemaJson).jsonObject["properties"]!!.jsonObject
        val codes = properties["subcategory"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(taxonomy.subcategories.map { it.code }, codes)
        val categories = properties["category"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("BIODEGRADABLE", "RECYCLABLE", "RESIDUAL", "SPECIAL"), categories)
    }

    @Test
    fun `boundary values are accepted`() {
        assertEquals(1, analyze(answer(quantity = 1)).quantity)
        assertEquals(999, analyze(answer(quantity = 999)).quantity)
        assertEquals(3, analyze(answer(quantity = "3.0")).quantity) // a whole number written as a decimal
        assertEquals(0.0, analyze(answer(confidence = 0)).confidence)
        assertEquals(1.0, analyze(answer(confidence = 1)).confidence)
        assertEquals("a".repeat(60), analyze(answer(name = "\"${"a".repeat(60)}\"")).name)
        // Special waste can be logged by photo too.
        val special = analyze(answer(category = "\"SPECIAL\"", subcategory = "\"SPC_BATTERIES\"", name = "\"AA battery\""))
        assertEquals("SPC_BATTERIES", special.subcategory)
    }

    @Test
    fun `isWaste false returns NOT_WASTE, whatever the other fields say`() {
        assertCode("NOT_WASTE", answer(isWaste = false))
        assertCode("NOT_WASTE", answer(isWaste = false, subcategory = "\"NOPE\"", quantity = 0))
    }

    @Test
    fun `malformed or incomplete answers return ANALYSIS_FAILED`() {
        for (raw in listOf("", "not json", "[]", "{\"isWaste\":true", "```json\n{}\n```", "{}")) assertCode("ANALYSIS_FAILED", raw)
        for (field in listOf("isWaste", "name", "category", "subcategory", "quantity", "confidence")) {
            val raw = Json.parseToJsonElement(answer()).jsonObject.filterKeys { it != field }.let { kotlinx.serialization.json.JsonObject(it).toString() }
            assertCode("ANALYSIS_FAILED", raw)
        }
        assertCode("ANALYSIS_FAILED", answer(isWaste = "\"true\""))
    }

    @Test
    fun `values outside the taxonomy or the entry rules return ANALYSIS_FAILED`() {
        assertCode("ANALYSIS_FAILED", answer(subcategory = "\"RES_PLASTIC_STRAWS\"")) // unknown code
        assertCode("ANALYSIS_FAILED", answer(category = "\"RECYCLABLE\"")) // category doesn't match RES_SACHETS
        assertCode("ANALYSIS_FAILED", answer(quantity = 0))
        assertCode("ANALYSIS_FAILED", answer(quantity = -2))
        assertCode("ANALYSIS_FAILED", answer(quantity = 1000))
        assertCode("ANALYSIS_FAILED", answer(quantity = 2.5))
        assertCode("ANALYSIS_FAILED", answer(quantity = "\"5\""))
        assertCode("ANALYSIS_FAILED", answer(name = "\"   \""))
        assertCode("ANALYSIS_FAILED", answer(name = "\"${"a".repeat(61)}\""))
        assertCode("ANALYSIS_FAILED", answer(confidence = 1.5))
        assertCode("ANALYSIS_FAILED", answer(confidence = -0.1))
        assertCode("ANALYSIS_FAILED", answer(confidence = "\"high\""))
    }

    @Test
    fun `a Gemini error or timeout returns ANALYSIS_FAILED`() = runBlocking {
        val failing = assertFailsWith<ApiException> { analyzer(FakeGemini.failing()).analyze(alice, image, "image/jpeg") }
        assertEquals("ANALYSIS_FAILED", failing.code.name)

        val crashing = assertFailsWith<ApiException> {
            analyzer(FakeGemini { throw IllegalStateException("socket closed") }).analyze(alice, image, "image/jpeg")
        }
        assertEquals("ANALYSIS_FAILED", crashing.code.name)

        val slow = FakeGemini { delay(5_000); answer() }
        val started = System.nanoTime()
        val timedOut = assertFailsWith<ApiException> { analyzer(slow).analyze(alice, image, "image/jpeg") }
        assertEquals("ANALYSIS_FAILED", timedOut.code.name)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 3_000, "the timeout must cut the call short")
    }

    @Test
    fun `the daily limit returns RATE_LIMITED without calling Gemini`() = runBlocking {
        val gemini = FakeGemini { answer() }
        val analyzer = analyzer(gemini, limit = 3)
        repeat(3) { analyzer.analyze(alice, image, "image/jpeg") }
        val e = assertFailsWith<ApiException> { analyzer.analyze(alice, image, "image/jpeg") }
        assertEquals("RATE_LIMITED", e.code.name)
        assertEquals(429, e.status.value)
        assertEquals("3", e.details["limit"]!!.jsonPrimitive.content)
        assertEquals("2026-09-30T16:00:00Z", e.details["resetsAt"]!!.jsonPrimitive.content) // next Manila midnight
        assertEquals(3, gemini.requests.size)

        // Failed analyses count too: each one used a Gemini call.
        val bob = UUID.randomUUID()
        val failing = analyzer(FakeGemini { throw GeminiException("boom") }, limit = 1)
        assertFailsWith<ApiException> { failing.analyze(bob, image, "image/jpeg") }
        assertEquals("RATE_LIMITED", assertFailsWith<ApiException> { failing.analyze(bob, image, "image/jpeg") }.code.name)
    }
}
