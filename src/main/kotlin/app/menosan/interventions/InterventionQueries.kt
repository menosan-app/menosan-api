package app.menosan.interventions

import app.menosan.db.AdoptedInterventions
import app.menosan.db.Hotspots
import app.menosan.db.Interventions
import app.menosan.db.ReportRecommendations
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.LocalDate
import java.util.UUID

/*
 * Queries BE-3's ReportService uses to persist recommendations and build the §8.3 payload.
 * Every function here must run inside an Exposed transaction (e.g. `db.tx { … }`). They take a report id
 * that the caller has already resolved for the authenticated user.
 */

/** One entry of `hotspots[].recommendations` in the report payload (contract §3). */
@Serializable
data class RecommendationView(
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

/** An adoption made on a report: the baseline for measuring impact next week (§5.4, SFR16.2). */
data class AdoptionRecord(
    val id: UUID,
    val interventionId: UUID,
    val interventionTitle: String,
    val targetSubcategory: String,
    val baselineWeekStart: LocalDate,
    val baselineQuantity: Int,
)

/** Persists the engine's picks for one hotspot (§6.2 step 5). */
fun insertRecommendations(hotspotId: UUID, picks: List<RecommendationPick>) {
    for (pick in picks) {
        ReportRecommendations.insert {
            it[ReportRecommendations.hotspotId] = hotspotId
            it[interventionId] = pick.interventionId
            it[rank] = pick.rank
            it[note] = pick.note
            it[continued] = pick.continued
            it[recommendationSource] = pick.source.name
        }
    }
}

fun adoptedInterventionIds(reportId: UUID): Set<UUID> =
    AdoptedInterventions.select(AdoptedInterventions.interventionId)
        .where { AdoptedInterventions.reportId eq reportId }
        .map { it[AdoptedInterventions.interventionId] }
        .toSet()

/** Stored recommendations of a report, grouped by hotspot id, each list in rank order, with the `adopted` flag. */
fun recommendationViews(reportId: UUID): Map<UUID, List<RecommendationView>> {
    val adopted = adoptedInterventionIds(reportId)
    return ReportRecommendations
        .join(Hotspots, JoinType.INNER, ReportRecommendations.hotspotId, Hotspots.id)
        .join(Interventions, JoinType.INNER, ReportRecommendations.interventionId, Interventions.id)
        .selectAll()
        .where { Hotspots.reportId eq reportId }
        .orderBy(ReportRecommendations.hotspotId to SortOrder.ASC, ReportRecommendations.rank to SortOrder.ASC)
        .groupBy({ it[ReportRecommendations.hotspotId] }) { row ->
            val intervention = row.toIntervention()
            RecommendationView(
                interventionId = intervention.id.toString(),
                code = intervention.code,
                type = intervention.type.name,
                title = intervention.title,
                description = intervention.description,
                howTo = intervention.howTo,
                costLevel = intervention.costLevel.name,
                effort = intervention.effort.name,
                note = row[ReportRecommendations.note],
                continued = row[ReportRecommendations.continued],
                adopted = intervention.id in adopted,
            )
        }
}

/** Adoptions made on a report, oldest first. Report W−1's adoptions feed W's impact and engine input. */
fun adoptionsForReport(reportId: UUID): List<AdoptionRecord> =
    AdoptedInterventions
        .join(Interventions, JoinType.INNER, AdoptedInterventions.interventionId, Interventions.id)
        .selectAll()
        .where { AdoptedInterventions.reportId eq reportId }
        .orderBy(AdoptedInterventions.adoptedAt to SortOrder.ASC, Interventions.code to SortOrder.ASC)
        .map {
            AdoptionRecord(
                id = it[AdoptedInterventions.id],
                interventionId = it[AdoptedInterventions.interventionId],
                interventionTitle = it[Interventions.title],
                targetSubcategory = it[AdoptedInterventions.targetSubcategory],
                baselineWeekStart = it[AdoptedInterventions.baselineWeekStart],
                baselineQuantity = it[AdoptedInterventions.baselineQuantity],
            )
        }
