package app.menosan.common

import kotlin.time.Duration

class GeminiImage(val bytes: ByteArray, val mimeType: String) {
    override fun toString() = "GeminiImage(${bytes.size} bytes, $mimeType)"
}

data class GeminiRequest(
    val systemInstruction: String?,
    val prompt: String,
    val responseSchemaJson: String,
    val image: GeminiImage? = null,
    val timeout: Duration,
)

class GeminiException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

fun geminiErrorSummary(e: Throwable): String =
    if (e is GeminiException) e.message ?: e.javaClass.simpleName else e.javaClass.simpleName

interface GeminiClient {
    suspend fun generateJson(request: GeminiRequest): String

    val maxQueueWait: Duration get() = Duration.ZERO
}

object DisabledGeminiClient : GeminiClient {
    override suspend fun generateJson(request: GeminiRequest): String =
        throw GeminiException("Gemini is disabled (GEMINI_API_KEY is not set)")
}
