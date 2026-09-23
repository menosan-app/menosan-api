package app.menosan.photo

import app.menosan.common.ApiException
import app.menosan.common.ErrorCode
import app.menosan.common.GeminiClient
import app.menosan.common.GeminiImage
import app.menosan.common.GeminiRequest
import app.menosan.common.geminiErrorSummary
import app.menosan.entries.NAME_MAX_LENGTH
import app.menosan.entries.QUANTITY_MAX
import app.menosan.entries.QUANTITY_MIN
import app.menosan.taxonomy.Taxonomy
import app.menosan.taxonomy.WasteCategory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import java.time.Clock
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Photo → suggested entry via Gemini (SFR7–8). The image goes to Gemini once, in memory, and is never
 * stored or logged (SFR8.5). The answer is validated strictly (SFR8.3): anything off → 422 ANALYSIS_FAILED.
 */
class GeminiPhotoAnalyzer(
    private val gemini: GeminiClient,
    private val taxonomy: Taxonomy,
    clock: Clock,
    private val timeout: Duration = GEMINI_TIMEOUT,
    private val limiter: DailyRateLimiter = DailyRateLimiter(DAILY_LIMIT, clock),
) : PhotoAnalyzer {

    private val systemInstruction: String = PhotoPrompt.systemInstruction(taxonomy)
    private val responseSchema: String = PhotoPrompt.responseSchema(taxonomy)

    override suspend fun analyze(userId: UUID, image: ByteArray, mimeType: String): PhotoSuggestion {
        if (!limiter.tryAcquire(userId)) {
            throw ApiException(
                ErrorCode.RATE_LIMITED,
                "You've reached today's limit of ${limiter.limit} photo analyses. You can still log manually.",
                buildJsonObject {
                    put("limit", limiter.limit)
                    put("resetsAt", limiter.resetsAt().toString())
                },
            )
        }

        val request = GeminiRequest(
            systemInstruction = systemInstruction,
            prompt = PhotoPrompt.USER_PROMPT,
            responseSchemaJson = responseSchema,
            image = GeminiImage(image, mimeType),
            timeout = timeout,
        )
        val raw = try {
            withTimeoutOrNull(timeout) { gemini.generateJson(request) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Photo analysis: Gemini call failed ({})", geminiErrorSummary(e))
            throw analysisFailed()
        } ?: run {
            log.warn("Photo analysis: Gemini timed out after {}", timeout)
            throw analysisFailed()
        }

        return when (val result = parse(raw, taxonomy)) {
            is PhotoResult.Suggestion -> result.suggestion
            PhotoResult.NotWaste -> throw ApiException(
                ErrorCode.NOT_WASTE,
                "This photo doesn't look like household waste. Try another photo, or log it manually.",
            )
            is PhotoResult.Invalid -> {
                // Only the reason is logged, never the model's text (it describes the user's photo).
                log.warn("Photo analysis: Gemini answer rejected ({})", result.reason)
                throw analysisFailed()
            }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(GeminiPhotoAnalyzer::class.java)
        val GEMINI_TIMEOUT: Duration = 15.seconds
        const val DAILY_LIMIT = 30

        private val json = Json { ignoreUnknownKeys = true }
        private val whitespace = Regex("\\s+")

        private fun analysisFailed() = ApiException(
            ErrorCode.ANALYSIS_FAILED,
            "We couldn't read this photo. Try a clearer photo, or log it manually.",
        )

        /** Strict validation of Gemini's JSON (SFR8.3). Pure, so it is tested directly. */
        internal fun parse(raw: String, taxonomy: Taxonomy): PhotoResult {
            val obj = try {
                json.parseToJsonElement(raw).jsonObject
            } catch (e: Exception) {
                return PhotoResult.Invalid("malformed JSON")
            }
            val isWaste = obj.primitive("isWaste")?.takeIf { !it.isString }?.booleanOrNull
                ?: return PhotoResult.Invalid("missing isWaste")
            if (!isWaste) return PhotoResult.NotWaste

            val subcategoryCode = obj.string("subcategory") ?: return PhotoResult.Invalid("missing subcategory")
            val subcategory = taxonomy.subcategory(subcategoryCode) ?: return PhotoResult.Invalid("unknown subcategory")
            val category = obj.string("category") ?: return PhotoResult.Invalid("missing category")
            if (category != subcategory.category) return PhotoResult.Invalid("category does not match subcategory")

            val name = obj.string("name")?.replace(whitespace, " ")?.trim() ?: return PhotoResult.Invalid("missing name")
            if (name.codePointCount(0, name.length) !in 1..NAME_MAX_LENGTH) return PhotoResult.Invalid("name length")

            // Whole numbers only; a model may write 3 as 3.0.
            val quantityValue = obj.primitive("quantity")?.takeIf { !it.isString }?.doubleOrNull
                ?: return PhotoResult.Invalid("missing quantity")
            if (quantityValue % 1.0 != 0.0) return PhotoResult.Invalid("quantity is not a whole number")
            if (quantityValue !in QUANTITY_MIN.toDouble()..QUANTITY_MAX.toDouble()) return PhotoResult.Invalid("quantity out of range")

            val confidence = obj.primitive("confidence")?.takeIf { !it.isString }?.doubleOrNull
                ?: return PhotoResult.Invalid("missing confidence")
            if (!confidence.isFinite() || confidence !in 0.0..1.0) return PhotoResult.Invalid("confidence out of range")

            return PhotoResult.Suggestion(
                PhotoSuggestion(
                    name = name,
                    category = subcategory.category,
                    subcategory = subcategory.code,
                    quantity = quantityValue.toInt(),
                    confidence = confidence,
                ),
            )
        }

        private fun JsonObject.primitive(key: String): JsonPrimitive? = this[key] as? JsonPrimitive

        private fun JsonObject.string(key: String): String? = primitive(key)?.takeIf { it.isString }?.content
    }
}

internal sealed interface PhotoResult {
    data class Suggestion(val suggestion: PhotoSuggestion) : PhotoResult
    data object NotWaste : PhotoResult
    data class Invalid(val reason: String) : PhotoResult
}

/** The photo prompt (`resources/prompts/photo_analysis.txt`) and response schema, both built from the taxonomy. */
internal object PhotoPrompt {
    const val USER_PROMPT = "Suggest one waste entry for this photo."
    private const val TAXONOMY_PLACEHOLDER = "{{TAXONOMY}}"

    private val template: String by lazy {
        PhotoPrompt::class.java.getResource("/prompts/photo_analysis.txt")?.readText()
            ?: error("prompts/photo_analysis.txt is missing from the classpath")
    }

    fun systemInstruction(taxonomy: Taxonomy): String {
        check(TAXONOMY_PLACEHOLDER in template) { "photo_analysis.txt must contain $TAXONOMY_PLACEHOLDER" }
        val lines = taxonomy.subcategories.sortedWith(compareBy({ WasteCategory.valueOf(it.category).ordinal }, { it.sortOrder }))
            .joinToString("\n") { "${it.code} | ${it.category} | ${it.label} | ${it.examples.joinToString(", ")}" }
        return template.replace(TAXONOMY_PLACEHOLDER, lines)
    }

    /** OpenAPI-subset schema for `responseSchema`. `subcategory` is an enum of the taxonomy codes (plan §9 BE-2). */
    fun responseSchema(taxonomy: Taxonomy): String = buildJsonObject {
        put("type", "OBJECT")
        putJsonObject("properties") {
            putJsonObject("isWaste") { put("type", "BOOLEAN") }
            putJsonObject("name") {
                put("type", "STRING")
                put("maxLength", NAME_MAX_LENGTH)
            }
            putJsonObject("category") {
                put("type", "STRING")
                put("enum", strings(taxonomy.categories.map { it.code }))
            }
            putJsonObject("subcategory") {
                put("type", "STRING")
                put("enum", strings(taxonomy.subcategories.map { it.code }))
            }
            putJsonObject("quantity") {
                put("type", "INTEGER")
                put("minimum", QUANTITY_MIN)
                put("maximum", QUANTITY_MAX)
            }
            putJsonObject("confidence") {
                put("type", "NUMBER")
                put("minimum", 0)
                put("maximum", 1)
            }
        }
        val fields = listOf("isWaste", "subcategory", "category", "name", "quantity", "confidence")
        put("required", strings(fields))
        put("propertyOrdering", strings(fields))
    }.toString()

    private fun strings(values: List<String>): JsonArray = buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }
}
