package app.menosan.reports

import java.time.LocalDate
import java.util.UUID

interface ReportService {
    suspend fun ensureReport(userId: UUID, weekStart: LocalDate): UUID?

    suspend fun catchUp(userId: UUID)

    suspend fun onLateEntry(userId: UUID, weekStart: LocalDate)

    suspend fun generateMissing(weekStart: LocalDate?): Int

    suspend fun listReports(userId: UUID): List<ReportSummary>

    suspend fun getReport(userId: UUID, weekStart: LocalDate): ReportResponse?
}
