package app.menosan.common

import kotlin.time.Duration

/** Image bytes for a Gemini call. Never stored or logged; `toString` shows only the size. */
class GeminiImage(val bytes: ByteArray, val mimeType: String) {
    override fun toString() = "GeminiImage(${bytes.size} bytes, $mimeType)"
}

data class GeminiRequest(
    val systemInstruction: String?,
    val prompt: String,
    /** JSON Schema (OpenAPI subset) for structured output, sent as `responseSchema`. */
    val responseSchemaJson: String,
    val image: GeminiImage? = null,
    val timeout: Duration,
)

class GeminiException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * A log-safe summary of a failed Gemini call. [GeminiException] messages carry only the error type, HTTP code,
 * status, and finish reason (e.g. "Gemini call failed: ApiException 429 RESOURCE_EXHAUSTED"), never content.
 * Any other exception is logged by class name only.
 */
fun geminiErrorSummary(e: Throwable): String =
    if (e is GeminiException) e.message ?: e.javaClass.simpleName else e.javaClass.simpleName

/**
 * Thin, fakeable wrapper around the Google Gen AI SDK (`com.google.genai:google-genai`), called only from
 * the backend. BE-2 (photo) implements the real client; BE-4 (interventions) reuses it.
 */
interface GeminiClient {
    /**
     * Sends [request] with `responseMimeType=application/json` and returns the raw JSON text.
     * Throws [GeminiException] on any error or timeout, and callers fall back (§6.2) or map to ANALYSIS_FAILED.
     */
    suspend fun generateJson(request: GeminiRequest): String
}

object StubGeminiClient : GeminiClient {
    override suspend fun generateJson(request: GeminiRequest): String =
        throw GeminiException("Gemini client is not implemented yet")
}
