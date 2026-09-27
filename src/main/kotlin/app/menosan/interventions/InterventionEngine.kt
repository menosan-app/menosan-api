package app.menosan.interventions

import app.menosan.analytics.QuantityUnit
import java.util.UUID

data class HotspotInput(
    val subcategoryCode: String,
    val criteria: List<String>,
    val frequency: Int,
    val quantity: Int,
    val unit: QuantityUnit = QuantityUnit.PIECES,
)

data class PreviousAdoption(
    val interventionId: UUID,
    val targetSubcategory: String,
    val result: String?,
)

data class RecommendationInput(
    val hotspot: HotspotInput,
    val analyzedEntries: Int,
    val previousAdoptions: List<PreviousAdoption>,
)

enum class RecommendationSource { GEMINI, RULES }

data class RecommendationPick(
    val interventionId: UUID,
    val rank: Int,
    val note: String?,
    val continued: Boolean,
    val source: RecommendationSource,
)

interface InterventionEngine {
    suspend fun recommend(input: RecommendationInput): List<RecommendationPick>
}
