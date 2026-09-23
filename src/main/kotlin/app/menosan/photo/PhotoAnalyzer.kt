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

/**
 * Photo → suggestion via Gemini. **Owned by BE-2**, which replaces [StubPhotoAnalyzer].
 * The image bytes must never be stored or logged (SFR8.5). Throw `ApiException` with
 * ANALYSIS_FAILED / NOT_WASTE / RATE_LIMITED as appropriate.
 */
interface PhotoAnalyzer {
    suspend fun analyze(userId: UUID, image: ByteArray, mimeType: String): PhotoSuggestion
}

object StubPhotoAnalyzer : PhotoAnalyzer {
    override suspend fun analyze(userId: UUID, image: ByteArray, mimeType: String) = notImplemented("Photo analysis")
}
