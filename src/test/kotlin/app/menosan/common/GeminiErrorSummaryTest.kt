package app.menosan.common

import kotlin.test.Test
import kotlin.test.assertEquals

/** Gemini failures are logged with their status (e.g. 429 vs 503), but never with anything but our own message. */
class GeminiErrorSummaryTest {
    @Test
    fun `a GeminiException is summarized by its message, which holds the HTTP code and status`() {
        val e = GeminiException("Gemini call failed: ApiException 429 RESOURCE_EXHAUSTED", RuntimeException("body text"))
        assertEquals("Gemini call failed: ApiException 429 RESOURCE_EXHAUSTED", geminiErrorSummary(e))
    }

    @Test
    fun `any other exception is summarized by its class name only`() {
        assertEquals("IllegalStateException", geminiErrorSummary(IllegalStateException("might hold content")))
    }
}
