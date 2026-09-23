package app.menosan.reports

import app.menosan.analytics.Comparison
import app.menosan.analytics.HotspotCriterion
import app.menosan.analytics.SubcategoryInfo
import app.menosan.analytics.Trend
import app.menosan.analytics.WeeklyStats
import app.menosan.taxonomy.Taxonomy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One row of `GET /v1/reports` (§8.2). */
@Serializable
data class ReportSummary(
    val weekStart: String,
    val weekEnd: String,
    val analyzedQuantity: Int,
    val hotspotCount: Int,
    val adoptedCount: Int,
    val isLatest: Boolean,
)

/** `GET /v1/reports/{weekStart}` (§8.3). */
@Serializable
data class ReportResponse(
    val weekStart: String,
    val weekEnd: String,
    val revision: Int,
    val isLatest: Boolean,
    val stats: WeeklyStats,
    val hotspots: List<HotspotResponse>,
    val comparison: Comparison?,
    val impacts: List<ImpactResponse>,
)

@Serializable
data class HotspotResponse(
    val rank: Int,
    val subcategory: String,
    val criteria: List<HotspotCriterion>,
    val frequency: Int,
    val quantity: Int,
    val score: Double,
    val recommendations: List<RecommendationResponse>,
)

@Serializable
data class RecommendationResponse(
    val interventionId: String,
    val code: String,
    val type: String,
    val title: String,
    val description: String,
    val howTo: List<String>,
    val costLevel: String,
    val effort: String,
    val note: String?,
    val continued: Boolean,
    val adopted: Boolean,
)

/** How an intervention adopted on the previous report went (§5.4, "How your changes went"). */
@Serializable
data class ImpactResponse(
    val interventionId: String,
    val title: String,
    val targetSubcategory: String,
    val baselineWeekStart: String,
    val baselineQuantity: Int,
    val followupQuantity: Int,
    val result: Trend,
)

/** Encodes `weekly_reports.stats` / `comparison`. Unknown keys are ignored so older rows stay readable. */
internal val ReportJson = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = true
}

/** The taxonomy reduced to what the pure analytics functions need. */
fun Taxonomy.analyticsTaxonomy(): Map<String, SubcategoryInfo> =
    subcategories.associate { it.code to SubcategoryInfo(it.code, it.category, it.avoidable) }
