package app.menosan.photo

import app.menosan.analytics.QuantityUnit
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class PhotoSuggestion(
    val name: String,
    val category: String,
    val subcategory: String,
    val quantity: Int,
    val unit: QuantityUnit,
    val confidence: Double,
)

@Serializable
data class PhotoAnalysisResponse(val suggestion: PhotoSuggestion, val warning: String)

const val PHOTO_WARNING =
    "This is an AI suggestion and it can be wrong. Please check the name, category, subcategory, and quantity before saving."

interface PhotoAnalyzer {
    suspend fun analyze(userId: UUID, image: ByteArray, mimeType: String): PhotoSuggestion
}
