package app.menosan.reports

import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.util.UUID

/**
 * Weekly report generation (plan §5.5–§5.6). **Owned by BE-3**, which replaces [StubReportService]
 * and adds the read methods needed by `GET /v1/reports`.
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
}

/** No-op stub so other workstreams can call it before BE-3 lands. */
object StubReportService : ReportService {
    private val log = LoggerFactory.getLogger(StubReportService::class.java)

    override suspend fun ensureReport(userId: UUID, weekStart: LocalDate): UUID? = null
    override suspend fun catchUp(userId: UUID) = Unit
    override suspend fun onLateEntry(userId: UUID, weekStart: LocalDate) {
        log.info("onLateEntry ignored by stub (weekStart={})", weekStart)
    }
    override suspend fun generateMissing(weekStart: LocalDate?): Int = 0
}
