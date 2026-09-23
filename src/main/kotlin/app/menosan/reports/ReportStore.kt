package app.menosan.reports

import app.menosan.analytics.ALGORITHM_VERSION
import app.menosan.analytics.Comparison
import app.menosan.analytics.EntryInput
import app.menosan.analytics.Hotspot
import app.menosan.analytics.HotspotCriterion
import app.menosan.analytics.Impact
import app.menosan.analytics.Trend
import app.menosan.analytics.WeeklyStats
import app.menosan.common.WeekCalc
import app.menosan.db.AdoptedInterventions
import app.menosan.db.Db
import app.menosan.db.Hotspots
import app.menosan.db.InterventionImpacts
import app.menosan.db.Interventions
import app.menosan.db.ReportRecommendations
import app.menosan.db.WasteEntries
import app.menosan.db.WeeklyReports
import app.menosan.interventions.RecommendationPick
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** An adoption made on a report (`adopted_interventions`, written by BE-4). */
data class AdoptionRow(
    val id: UUID,
    val interventionId: UUID,
    val targetSubcategory: String,
    val baselineWeekStart: LocalDate,
    val baselineQuantity: Int,
)

/** Everything §5.5 steps 1–4 produce for one week. */
data class ComputedReport(
    val weekStart: LocalDate,
    val stats: WeeklyStats,
    val hotspots: List<Hotspot>,
    val comparison: Comparison?,
    /** Adoptions made on report W−1, measured in this week. */
    val previousAdoptions: List<AdoptionRow>,
    val impacts: List<Impact>,
)

/** SQL for reports. Every query is scoped by user id, directly or through a report id owned by that user. */
class ReportStore(private val db: Db) {

    suspend fun entries(userId: UUID, weekStart: LocalDate): List<EntryInput> = db.tx {
        WasteEntries.select(WasteEntries.subcategoryCode, WasteEntries.quantity)
            .where { (WasteEntries.userId eq userId) and (WasteEntries.weekStart eq weekStart) }
            .map { EntryInput(it[WasteEntries.subcategoryCode], it[WasteEntries.quantity]) }
    }

    /** Weeks before [before] in which the user logged anything and that have no report yet, oldest first. */
    suspend fun weeksMissingReports(userId: UUID, before: LocalDate): List<LocalDate> = db.tx {
        val withEntries = WasteEntries.select(WasteEntries.weekStart)
            .where { (WasteEntries.userId eq userId) and (WasteEntries.weekStart less before) }
            .withDistinct()
            .map { it[WasteEntries.weekStart] }
        val withReports = WeeklyReports.select(WeeklyReports.weekStart)
            .where { WeeklyReports.userId eq userId }
            .map { it[WeeklyReports.weekStart] }
            .toSet()
        withEntries.filterNot { it in withReports }.sorted()
    }

    /** Users who logged anything in [weekStart] and have no report for it yet. */
    suspend fun usersMissingReport(weekStart: LocalDate): List<UUID> = db.tx {
        val withEntries = WasteEntries.select(WasteEntries.userId)
            .where { WasteEntries.weekStart eq weekStart }
            .withDistinct()
            .map { it[WasteEntries.userId] }
        val withReports = WeeklyReports.select(WeeklyReports.userId)
            .where { WeeklyReports.weekStart eq weekStart }
            .map { it[WeeklyReports.userId] }
            .toSet()
        withEntries.filterNot { it in withReports }.sorted()
    }

    suspend fun findReportId(userId: UUID, weekStart: LocalDate): UUID? = db.tx { reportId(userId, weekStart) }

    suspend fun adoptions(reportId: UUID): List<AdoptionRow> = db.tx {
        AdoptedInterventions.selectAll()
            .where { AdoptedInterventions.reportId eq reportId }
            .map {
                AdoptionRow(
                    id = it[AdoptedInterventions.id],
                    interventionId = it[AdoptedInterventions.interventionId],
                    targetSubcategory = it[AdoptedInterventions.targetSubcategory],
                    baselineWeekStart = it[AdoptedInterventions.baselineWeekStart],
                    baselineQuantity = it[AdoptedInterventions.baselineQuantity],
                )
            }
    }

    suspend fun hotspotSubcategories(reportId: UUID): Set<String> = db.tx {
        Hotspots.select(Hotspots.subcategoryCode).where { Hotspots.reportId eq reportId }
            .map { it[Hotspots.subcategoryCode] }.toSet()
    }

    /**
     * Inserts a new report with its hotspots, recommendations, and impacts in one transaction.
     * If another caller created it first (unique user_id, week_start), nothing is written and that report's id is returned.
     */
    suspend fun insertReport(
        userId: UUID,
        report: ComputedReport,
        picks: Map<String, List<RecommendationPick>>,
        now: Instant,
    ): UUID = db.tx {
        val reportId = UUID.randomUUID()
        val inserted = WeeklyReports.insertIgnore {
            it[id] = reportId
            it[WeeklyReports.userId] = userId
            it[weekStart] = report.weekStart
            it[weekEnd] = WeekCalc.weekEnd(report.weekStart)
            it[stats] = ReportJson.encodeToJsonElement(WeeklyStats.serializer(), report.stats)
            it[comparison] = report.comparison?.let { c -> ReportJson.encodeToJsonElement(Comparison.serializer(), c) }
            it[algorithmVersion] = ALGORITHM_VERSION
            it[revision] = 1
            it[generatedAt] = now.atOffset(ZoneOffset.UTC)
        }.insertedCount
        if (inserted == 0) return@tx reportId(userId, report.weekStart)!!

        for (h in report.hotspots) insertHotspot(reportId, h, picks[h.subcategory].orEmpty())
        upsertImpacts(reportId, report)
        reportId
    }

    /**
     * Regeneration after a late sync (§5.6): replaces stats, comparison, hotspots, and impacts, keeps the
     * recommendations of hotspots that still exist, adds [newPicks] for new hotspots, and never touches adoptions.
     */
    suspend fun updateReport(
        reportId: UUID,
        report: ComputedReport,
        newPicks: Map<String, List<RecommendationPick>>,
        now: Instant,
    ) = db.tx {
        // Row lock: concurrent regenerations of the same report run one after another.
        val revision = WeeklyReports.select(WeeklyReports.revision)
            .where { WeeklyReports.id eq reportId }
            .forUpdate()
            .single()[WeeklyReports.revision]

        val existing = Hotspots.select(Hotspots.id, Hotspots.subcategoryCode)
            .where { Hotspots.reportId eq reportId }
            .associate { it[Hotspots.subcategoryCode] to it[Hotspots.id] }
        val keep = report.hotspots.map { it.subcategory }.toSet()
        val gone = existing.filterKeys { it !in keep }.values.toList()
        if (gone.isNotEmpty()) Hotspots.deleteWhere { Hotspots.id inList gone }

        for (h in report.hotspots) {
            val hotspotId = existing[h.subcategory]
            if (hotspotId == null) {
                insertHotspot(reportId, h, newPicks[h.subcategory].orEmpty())
            } else {
                Hotspots.update({ Hotspots.id eq hotspotId }) {
                    it[rank] = h.rank
                    it[criteria] = h.criteria.map(HotspotCriterion::name)
                    it[frequency] = h.frequency
                    it[quantity] = h.quantity
                    it[score] = h.score.toScoreDecimal()
                }
            }
        }

        WeeklyReports.update({ WeeklyReports.id eq reportId }) {
            it[stats] = ReportJson.encodeToJsonElement(WeeklyStats.serializer(), report.stats)
            it[comparison] = report.comparison?.let { c -> ReportJson.encodeToJsonElement(Comparison.serializer(), c) }
            it[algorithmVersion] = ALGORITHM_VERSION
            it[WeeklyReports.revision] = revision + 1
            it[regeneratedAt] = now.atOffset(ZoneOffset.UTC)
        }
        upsertImpacts(reportId, report)
    }

    suspend fun list(userId: UUID, latestWeekStart: LocalDate): List<ReportSummary> = db.tx {
        val rows = WeeklyReports.select(WeeklyReports.id, WeeklyReports.weekStart, WeeklyReports.weekEnd, WeeklyReports.stats)
            .where { WeeklyReports.userId eq userId }
            .orderBy(WeeklyReports.weekStart, SortOrder.DESC)
            .toList()
        val ids = rows.map { it[WeeklyReports.id] }
        if (ids.isEmpty()) return@tx emptyList()

        val hotspotCount = Hotspots.id.count()
        val hotspots = Hotspots.select(Hotspots.reportId, hotspotCount)
            .where { Hotspots.reportId inList ids }
            .groupBy(Hotspots.reportId)
            .associate { it[Hotspots.reportId] to it[hotspotCount].toInt() }
        val adoptedCount = AdoptedInterventions.id.count()
        val adopted = AdoptedInterventions.select(AdoptedInterventions.reportId, adoptedCount)
            .where { (AdoptedInterventions.reportId inList ids) and (AdoptedInterventions.userId eq userId) }
            .groupBy(AdoptedInterventions.reportId)
            .associate { it[AdoptedInterventions.reportId] to it[adoptedCount].toInt() }

        rows.map { row ->
            val id = row[WeeklyReports.id]
            val weekStart = row[WeeklyReports.weekStart]
            ReportSummary(
                weekStart = weekStart.toString(),
                weekEnd = row[WeeklyReports.weekEnd].toString(),
                analyzedQuantity = row.stats().analyzedTotals.quantity,
                hotspotCount = hotspots[id] ?: 0,
                adoptedCount = adopted[id] ?: 0,
                isLatest = weekStart == latestWeekStart,
            )
        }
    }

    suspend fun detail(userId: UUID, weekStart: LocalDate, latestWeekStart: LocalDate): ReportResponse? = db.tx {
        val row = WeeklyReports.selectAll()
            .where { (WeeklyReports.userId eq userId) and (WeeklyReports.weekStart eq weekStart) }
            .singleOrNull() ?: return@tx null
        val reportId = row[WeeklyReports.id]

        val adoptedIds = AdoptedInterventions.select(AdoptedInterventions.interventionId)
            .where { AdoptedInterventions.reportId eq reportId }
            .map { it[AdoptedInterventions.interventionId] }
            .toSet()

        val hotspotRows = Hotspots.selectAll()
            .where { Hotspots.reportId eq reportId }
            .orderBy(Hotspots.rank)
            .toList()
        val recommendations = ReportRecommendations
            .join(Interventions, JoinType.INNER, ReportRecommendations.interventionId, Interventions.id)
            .selectAll()
            .where { ReportRecommendations.hotspotId inList hotspotRows.map { it[Hotspots.id] } }
            .orderBy(ReportRecommendations.rank)
            .groupBy({ it[ReportRecommendations.hotspotId] }) { it.toRecommendation(adoptedIds) }

        val impacts = InterventionImpacts
            .join(AdoptedInterventions, JoinType.INNER, InterventionImpacts.adoptionId, AdoptedInterventions.id)
            .join(Interventions, JoinType.INNER, AdoptedInterventions.interventionId, Interventions.id)
            .selectAll()
            .where { InterventionImpacts.followupReportId eq reportId }
            .orderBy(AdoptedInterventions.targetSubcategory to SortOrder.ASC, Interventions.code to SortOrder.ASC)
            .map {
                ImpactResponse(
                    interventionId = it[AdoptedInterventions.interventionId].toString(),
                    title = it[Interventions.title],
                    targetSubcategory = it[AdoptedInterventions.targetSubcategory],
                    baselineWeekStart = it[AdoptedInterventions.baselineWeekStart].toString(),
                    baselineQuantity = it[InterventionImpacts.baselineQuantity],
                    followupQuantity = it[InterventionImpacts.followupQuantity],
                    result = Trend.valueOf(it[InterventionImpacts.result]),
                )
            }

        ReportResponse(
            weekStart = weekStart.toString(),
            weekEnd = row[WeeklyReports.weekEnd].toString(),
            revision = row[WeeklyReports.revision],
            isLatest = weekStart == latestWeekStart,
            stats = row.stats(),
            hotspots = hotspotRows.map { h ->
                HotspotResponse(
                    rank = h[Hotspots.rank],
                    subcategory = h[Hotspots.subcategoryCode],
                    criteria = h[Hotspots.criteria].map(HotspotCriterion::valueOf),
                    frequency = h[Hotspots.frequency],
                    quantity = h[Hotspots.quantity],
                    score = h[Hotspots.score].toDouble(),
                    recommendations = recommendations[h[Hotspots.id]].orEmpty(),
                )
            },
            comparison = row[WeeklyReports.comparison]?.let { ReportJson.decodeFromJsonElement(Comparison.serializer(), it) },
            impacts = impacts,
        )
    }

    // ---- helpers (run inside a transaction) ----

    private fun JdbcTransaction.reportId(userId: UUID, weekStart: LocalDate): UUID? =
        WeeklyReports.select(WeeklyReports.id)
            .where { (WeeklyReports.userId eq userId) and (WeeklyReports.weekStart eq weekStart) }
            .singleOrNull()?.get(WeeklyReports.id)

    private fun insertHotspot(reportId: UUID, h: Hotspot, picks: List<RecommendationPick>) {
        val hotspotId = UUID.randomUUID()
        Hotspots.insert {
            it[id] = hotspotId
            it[Hotspots.reportId] = reportId
            it[subcategoryCode] = h.subcategory
            it[rank] = h.rank
            it[criteria] = h.criteria.map(HotspotCriterion::name)
            it[frequency] = h.frequency
            it[quantity] = h.quantity
            it[score] = h.score.toScoreDecimal()
        }
        for (p in picks) {
            ReportRecommendations.insertIgnore {
                it[ReportRecommendations.hotspotId] = hotspotId
                it[interventionId] = p.interventionId
                it[rank] = p.rank
                it[note] = p.note
                it[continued] = p.continued
                it[recommendationSource] = p.source.name
            }
        }
    }

    private fun upsertImpacts(reportId: UUID, report: ComputedReport) {
        val adoptionsByIntervention = report.previousAdoptions.associateBy { it.interventionId.toString() }
        for (impact in report.impacts) {
            val adoption = adoptionsByIntervention.getValue(impact.interventionId)
            InterventionImpacts.upsert(InterventionImpacts.adoptionId) {
                it[adoptionId] = adoption.id
                it[followupReportId] = reportId
                it[followupWeekStart] = report.weekStart
                it[baselineQuantity] = impact.baselineQuantity
                it[followupQuantity] = impact.followupQuantity
                it[result] = impact.result.name
            }
        }
    }

    private fun ResultRow.stats(): WeeklyStats = ReportJson.decodeFromJsonElement(WeeklyStats.serializer(), this[WeeklyReports.stats])

    private fun ResultRow.toRecommendation(adoptedIds: Set<UUID>): RecommendationResponse {
        val interventionId = this[ReportRecommendations.interventionId]
        return RecommendationResponse(
            interventionId = interventionId.toString(),
            code = this[Interventions.code],
            type = this[Interventions.type],
            title = this[Interventions.title],
            description = this[Interventions.description],
            howTo = this[Interventions.howTo],
            costLevel = this[Interventions.costLevel],
            effort = this[Interventions.effort],
            note = this[ReportRecommendations.note],
            continued = this[ReportRecommendations.continued],
            adopted = interventionId in adoptedIds,
        )
    }

    private fun Double.toScoreDecimal(): BigDecimal = BigDecimal.valueOf(this).setScale(4, java.math.RoundingMode.HALF_UP)
}
