package app.menosan.interventions

import java.util.UUID

/** One hotspot to recommend for (§5.2 output). */
data class HotspotInput(
    val subcategoryCode: String,
    val criteria: List<String>, // MOST_FREQUENT, HIGHEST_QUANTITY, AVOIDABLE
    val frequency: Int,
    val quantity: Int,
)

/** An intervention adopted on last week's report and how it went (§6.2, I8). */
data class PreviousAdoption(
    val interventionId: UUID,
    val targetSubcategory: String,
    /** DECREASED | SAME | INCREASED, or null when not measured. */
    val result: String?,
)

data class RecommendationInput(
    val hotspot: HotspotInput,
    val analyzedTotalQuantity: Int,
    val previousAdoptions: List<PreviousAdoption>,
)

enum class RecommendationSource { GEMINI, RULES }

/** One stored pick (`report_recommendations`). Always references a curated library item (NFR12). */
data class RecommendationPick(
    val interventionId: UUID,
    val rank: Int,
    val note: String?,
    val continued: Boolean,
    val source: RecommendationSource,
)

/**
 * Picks 1–3 curated interventions for a hotspot (§6.2). **Owned by BE-4**, which replaces
 * [StubInterventionEngine]. Gemini may rank and annotate, never invent.
 */
interface InterventionEngine {
    suspend fun recommend(input: RecommendationInput): List<RecommendationPick>
}

/** Returns no recommendations, so BE-3 can build reports before BE-4 lands. */
object StubInterventionEngine : InterventionEngine {
    override suspend fun recommend(input: RecommendationInput): List<RecommendationPick> = emptyList()
}
