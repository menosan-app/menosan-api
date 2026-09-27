package app.menosan.common

import com.google.genai.Client
import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.HttpOptions
import com.google.genai.types.HttpRetryOptions
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.ThinkingConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.future.await

class GenAiGeminiClient(
    apiKey: String,
    private val model: String,
    private val thinkingLevel: String? = defaultThinkingLevel(model),
) : GeminiClient, AutoCloseable {
    private val client: Client = Client.builder()
        .apiKey(apiKey)
        .httpOptions(HttpOptions.builder().retryOptions(HttpRetryOptions.builder().attempts(1)).build())
        .build()

    override suspend fun generateJson(request: GeminiRequest): String {
        val config = buildConfig(request, thinkingLevel)
        val parts = buildList {
            request.image?.let { add(Part.fromBytes(it.bytes, it.mimeType)) }
            add(Part.fromText(request.prompt))
        }
        val content = Content.builder().role("user").parts(parts).build()

        val response = try {
            client.async.models.generateContent(model, content, config).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw GeminiException("Gemini call failed: ${describe(e)}", e)
        }
        val text = try {
            response.text()
        } catch (e: Exception) {
            null
        }
        if (text.isNullOrBlank()) {
            throw GeminiException("Gemini returned no text (finishReason=${runCatching { response.finishReason() }.getOrNull()})")
        }
        return text
    }

    override fun close() = client.close()

    companion object {
        fun buildConfig(request: GeminiRequest, thinkingLevel: String? = null): GenerateContentConfig {
            val builder = GenerateContentConfig.builder()
                .responseMimeType("application/json")
                .responseSchema(Schema.fromJson(request.responseSchemaJson))
                .httpOptions(HttpOptions.builder().timeout(request.timeout.inWholeMilliseconds.toInt()).build())
            request.systemInstruction?.let { builder.systemInstruction(Content.fromParts(Part.fromText(it))) }
            thinkingLevel?.let { builder.thinkingConfig(ThinkingConfig.builder().thinkingLevel(it).build()) }
            return builder.build()
        }

        fun defaultThinkingLevel(model: String): String? = when {
            !model.startsWith("gemini-3") -> null
            model.contains("-lite") -> "minimal"
            else -> "low"
        }

        private fun describe(e: Throwable): String {
            val root = generateSequence(e) { it.cause }.firstOrNull { it is com.google.genai.errors.ApiException } ?: e
            return if (root is com.google.genai.errors.ApiException) {
                "${root.javaClass.simpleName} ${root.code()} ${root.status()}"
            } else {
                generateSequence(e) { it.cause }.last().javaClass.simpleName
            }
        }
    }
}
