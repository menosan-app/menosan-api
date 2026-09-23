package app.menosan.reports

import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.util.UUID

/**
 * Weekly report generation and reads (plan §5.5–§5.6). **Owned by BE-3**; the real implementation is
 * [DefaultReportService].
 */
interface ReportService {
    /**
     * Idempotently builds the report for a closed week (unique on user_id, week_start).
     * Returns the report id, or null if the week is not closed or has no entries.
     */
    suspend fun ensureReport(userId: UUID, weekStart: LocalDate): UUID?

    /** Lazy catch-up: generate every missing report for closed weeks that have entries. */
    suspend fun catchUp(userId: UUID)

    /** Called by BE-1 when an entry is created in an already closed week (§5.6). */
    suspend fun onLateEntry(userId: UUID, weekStart: LocalDate)

    /** Weekly job: generate missing reports for [weekStart] (default: the week that just closed). Returns how many were created. */
    suspend fun generateMissing(weekStart: LocalDate?): Int

    /** `GET /v1/reports`: runs [catchUp], then lists the user's reports, newest first. */
    suspend fun listReports(userId: UUID): List<ReportSummary>

    /**
     * `GET /v1/reports/{weekStart}` payload (§8.3), generating the report first if it is missing.
     * Null when the week is still open or the user logged nothing in it. Always scoped to [userId].
     * BE-4 returns this after adoption changes.
     */
    suspend fun getReport(userId: UUID, weekStart: LocalDate): ReportResponse?
}

/** No-op stub, kept for tests that don't need reports. */
object StubReportService : ReportService {
    private val log = LoggerFactory.getLogger(StubReportService::class.java)

    override suspend fun ensureReport(userId: UUID, weekStart: LocalDate): UUID? = null
    override suspend fun catchUp(userId: UUID) = Unit
    override suspend fun onLateEntry(userId: UUID, weekStart: LocalDate) {
        log.info("onLateEntry ignored by stub (weekStart={})", weekStart)
    }
    override suspend fun generateMissing(weekStart: LocalDate?): Int = 0
    override suspend fun listReports(userId: UUID): List<ReportSummary> = emptyList()
    override suspend fun getReport(userId: UUID, weekStart: LocalDate): ReportResponse? = null
}
