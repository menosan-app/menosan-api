package app.menosan.reports

import app.menosan.analytics.AdoptionInput
import app.menosan.analytics.Hotspot
import app.menosan.analytics.aggregate
import app.menosan.analytics.compare
import app.menosan.analytics.findHotspots
import app.menosan.analytics.measureImpact
import app.menosan.common.WeekCalc
import app.menosan.interventions.HotspotInput
import app.menosan.interventions.InterventionEngine
import app.menosan.interventions.PreviousAdoption
import app.menosan.interventions.RecommendationInput
import app.menosan.interventions.RecommendationPick
import app.menosan.taxonomy.Taxonomy
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

private val log = LoggerFactory.getLogger("app.menosan.reports")

/**
 * Report generation per plan §5.5 (pure analytics, then recommendations outside the DB transaction, then one
 * transaction to persist) and regeneration after a late sync (§5.6).
 */
class DefaultReportService(
    private val store: ReportStore,
    private val clock: Clock,
    taxonomy: Taxonomy,
    private val interventions: InterventionEngine,
) : ReportService {
    private val taxonomy = taxonomy.analyticsTaxonomy()

    override suspend fun ensureReport(userId: UUID, weekStart: LocalDate): UUID? {
        if (!isClosedWeek(weekStart)) return null
        store.findReportId(userId, weekStart)?.let { return it }
        val report = compute(userId, weekStart) ?: return null
        val picks = recommend(report, report.hotspots)
        return store.insertReport(userId, report, picks, clock.instant())
    }

    override suspend fun catchUp(userId: UUID) {
        for (week in store.weeksMissingReports(userId, before = WeekCalc.currentWeekStart(clock))) {
            ensureReport(userId, week)
        }
    }

    override suspend fun onLateEntry(userId: UUID, weekStart: LocalDate) {
        if (!isClosedWeek(weekStart)) return
        regenerateOrCreate(userId, weekStart)
        // The next week's comparison (and anything measured against this week) changes too.
        val next = weekStart.plusDays(7)
        if (store.findReportId(userId, next) != null) regenerateOrCreate(userId, next)
    }

    override suspend fun generateMissing(weekStart: LocalDate?): Int {
        val week = weekStart ?: WeekCalc.currentWeekStart(clock).minusDays(7)
        if (!isClosedWeek(week)) return 0
        var created = 0
        for (userId in store.usersMissingReport(week)) {
            try {
                if (ensureReport(userId, week) != null) created++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // One user's failure must not stop the job. Log ids only, never entry contents.
                log.error("Report generation failed (userId={}, weekStart={}): {}", userId, week, e.javaClass.name)
            }
        }
        log.info("Weekly reports for {}: {} created", week, created)
        return created
    }

    override suspend fun listReports(userId: UUID): List<ReportSummary> {
        catchUp(userId)
        return store.list(userId, latestWeekStart())
    }

    override suspend fun getReport(userId: UUID, weekStart: LocalDate): ReportResponse? {
        ensureReport(userId, weekStart) // lazy catch-up for this week (§5.5 trigger 3)
        return store.detail(userId, weekStart, latestWeekStart())
    }

    /** The report users can still adopt on (plan I7): the week before the current one. */
    private fun latestWeekStart(): LocalDate = WeekCalc.currentWeekStart(clock).minusDays(7)

    private fun isClosedWeek(weekStart: LocalDate) = WeekCalc.isWeekStart(weekStart) && WeekCalc.isClosed(weekStart, clock)

    private suspend fun regenerateOrCreate(userId: UUID, weekStart: LocalDate) {
        val reportId = store.findReportId(userId, weekStart)
        if (reportId == null) {
            ensureReport(userId, weekStart)
            return
        }
        // Entries in a closed week can't be deleted, so a week with a report always still has entries.
        val report = compute(userId, weekStart) ?: return
        val existing = store.hotspotSubcategories(reportId)
        val picks = recommend(report, report.hotspots.filter { it.subcategory !in existing })
        store.updateReport(reportId, report, picks, clock.instant())
    }

    /** §5.5 steps 1–4. Null when nothing was logged in the week (no report). */
    private suspend fun compute(userId: UUID, weekStart: LocalDate): ComputedReport? {
        val entries = store.entries(userId, weekStart)
        if (entries.isEmpty()) return null
        val stats = aggregate(entries, taxonomy)

        val previousWeek = weekStart.minusDays(7)
        val previousEntries = store.entries(userId, previousWeek)
        val previousStats = if (previousEntries.isEmpty()) null else aggregate(previousEntries, taxonomy)

        val previousReportId = store.findReportId(userId, previousWeek)
        val adoptions = previousReportId?.let { store.adoptions(it) }.orEmpty()
        val impacts = measureImpact(
            adoptions.map { AdoptionInput(it.interventionId.toString(), it.targetSubcategory, it.baselineQuantity) },
            stats,
        )
        return ComputedReport(
            weekStart = weekStart,
            stats = stats,
            hotspots = findHotspots(stats, taxonomy),
            comparison = compare(stats, previousStats, previousWeek.toString()),
            previousAdoptions = adoptions,
            impacts = impacts,
        )
    }

    /**
     * §5.5 step 5, outside any transaction (it may call Gemini). The engine falls back to rules by itself (§6.2);
     * if it still fails, the hotspot is stored without recommendations rather than losing the report.
     */
    private suspend fun recommend(report: ComputedReport, hotspots: List<Hotspot>): Map<String, List<RecommendationPick>> {
        val resultByIntervention = report.impacts.associate { it.interventionId to it.result.name }
        val previous = report.previousAdoptions.map {
            PreviousAdoption(it.interventionId, it.targetSubcategory, resultByIntervention[it.interventionId.toString()])
        }
        return hotspots.associate { h ->
            val input = RecommendationInput(
                hotspot = HotspotInput(h.subcategory, h.criteria.map { it.name }, h.frequency, h.quantity),
                analyzedTotalQuantity = report.stats.analyzedTotals.quantity,
                previousAdoptions = previous,
            )
            val picks = try {
                interventions.recommend(input)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Intervention engine failed for {}: {}", h.subcategory, e.javaClass.name)
                emptyList()
            }
            h.subcategory to picks.distinctBy { it.interventionId }.sortedBy { it.rank }.take(MAX_RECOMMENDATIONS)
        }
    }

    private companion object {
        /** §6.2: 1–3 picks per hotspot. */
        const val MAX_RECOMMENDATIONS = 3
    }
}
