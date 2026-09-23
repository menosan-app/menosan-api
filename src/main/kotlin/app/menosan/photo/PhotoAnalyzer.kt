package app.menosan.photo

import app.menosan.common.notImplemented
import kotlinx.serialization.Serializable
import java.util.UUID

/** Suggested entry fields from a photo (§8.2 `POST /v1/photo-analysis`). */
@Serializable
data class PhotoSuggestion(
    val name: String,
    val category: String,
    val subcategory: String,
    val quantity: Int,
    val confidence: Double,
)

/** `200` body of `POST /v1/photo-analysis`. [warning] is always present (SFR9.5). */
@Serializable
data class PhotoAnalysisResponse(val suggestion: PhotoSuggestion, val warning: String)

/** Shown with every suggestion (SFR9.5). The app also marks the AI-filled fields (NFR10). */
const val PHOTO_WARNING =
    "This is an AI suggestion and it can be wrong. Please check the name, category, subcategory, and quantity before saving."

/**
 * Photo → suggestion via Gemini. The real implementation is [GeminiPhotoAnalyzer].
 * The image bytes must never be stored or logged (SFR8.5). Throws `ApiException` with
 * ANALYSIS_FAILED / NOT_WASTE / RATE_LIMITED as appropriate.
 */
interface PhotoAnalyzer {
    suspend fun analyze(userId: UUID, image: ByteArray, mimeType: String): PhotoSuggestion
}

object StubPhotoAnalyzer : PhotoAnalyzer {
    override suspend fun analyze(userId: UUID, image: ByteArray, mimeType: String) = notImplemented("Photo analysis")
}
