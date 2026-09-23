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

/**
 * The real [GeminiClient], on the Google Gen AI Java SDK (Gemini Developer API, key auth).
 * Shared by photo analysis (BE-2) and intervention selection (BE-4).
 *
 * Privacy: prompts and images go only to Gemini. Nothing here logs them, and exception messages carry
 * only the error type and HTTP status, never the request or response body.
 */
class GenAiGeminiClient(
    apiKey: String,
    private val model: String,
    /** Gemini 3 `thinkingLevel` (e.g. `minimal`, `low`). Null leaves the model's default. See [defaultThinkingLevel]. */
    private val thinkingLevel: String? = defaultThinkingLevel(model),
) : GeminiClient, AutoCloseable {
    private val client: Client = Client.builder()
        .apiKey(apiKey)
        // No SDK retries: callers have tight deadlines (8 s, 15 s) and their own fallbacks.
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
            // await() cancels the HTTP call when the caller's timeout cancels the coroutine.
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
        /** Builds the request config. Public for tests: it proves our schema JSON parses into the SDK's [Schema]. */
        fun buildConfig(request: GeminiRequest, thinkingLevel: String? = null): GenerateContentConfig {
            val builder = GenerateContentConfig.builder()
                .responseMimeType("application/json")
                .responseSchema(Schema.fromJson(request.responseSchemaJson))
                .httpOptions(HttpOptions.builder().timeout(request.timeout.inWholeMilliseconds.toInt()).build())
            request.systemInstruction?.let { builder.systemInstruction(Content.fromParts(Part.fromText(it))) }
            thinkingLevel?.let { builder.thinkingConfig(ThinkingConfig.builder().thinkingLevel(it).build()) }
            return builder.build()
        }

        /**
         * Gemini 3 models think by default, which alone can exceed the 8 s selection timeout. Both of our tasks
         * are classification-like, so keep thinking short: `minimal` on the lite models (with `low`,
         * gemini-3.1-flash-lite timed out on selection, 2026-09-24), `low` on the others. Older models reject
         * `thinkingLevel`: leave them alone.
         */
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
