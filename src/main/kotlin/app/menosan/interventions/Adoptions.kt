package app.menosan.interventions

import app.menosan.common.ApiException
import app.menosan.common.ErrorCode
import app.menosan.common.WeekCalc
import app.menosan.db.AdoptedInterventions
import app.menosan.db.Db
import app.menosan.db.Hotspots
import app.menosan.db.ReportRecommendations
import app.menosan.db.WeeklyReports
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

object AdoptionWindow {
    fun isLatest(reportWeekStart: LocalDate, clock: Clock): Boolean =
        reportWeekStart == WeekCalc.currentWeekStart(clock).minusDays(7)
}

interface AdoptionService {
    suspend fun adopt(userId: UUID, weekStart: LocalDate, interventionIds: Set<UUID>)

    suspend fun unadopt(userId: UUID, weekStart: LocalDate, interventionId: UUID)
}

class ExposedAdoptionService(private val db: Db, private val clock: Clock) : AdoptionService {
    override suspend fun adopt(userId: UUID, weekStart: LocalDate, interventionIds: Set<UUID>) = db.tx {
        val reportId = openReportId(userId, weekStart)
        val recommended = ReportRecommendations
            .join(Hotspots, JoinType.INNER, ReportRecommendations.hotspotId, Hotspots.id)
            .selectAll()
            .where { (Hotspots.reportId eq reportId) and (ReportRecommendations.interventionId inList interventionIds) }
            .associate { it[ReportRecommendations.interventionId] to (it[Hotspots.subcategoryCode] to it[Hotspots.quantity]) }
        if (!recommended.keys.containsAll(interventionIds)) {
            throw ApiException(
                ErrorCode.VALIDATION_FAILED,
                "You can only adopt suggestions shown on this report.",
                buildJsonObject { put("field", JsonPrimitive("interventionIds")) },
            )
        }
        val now = clock.instant().atOffset(ZoneOffset.UTC)
        for ((interventionId, target) in recommended) {
            val (subcategory, quantity) = target
            AdoptedInterventions.insertIgnore {
                it[AdoptedInterventions.userId] = userId
                it[AdoptedInterventions.reportId] = reportId
                it[AdoptedInterventions.interventionId] = interventionId
                it[targetSubcategory] = subcategory
                it[baselineWeekStart] = weekStart
                it[baselineQuantity] = quantity
                it[adoptedAt] = now
            }
        }
    }

    override suspend fun unadopt(userId: UUID, weekStart: LocalDate, interventionId: UUID) = db.tx {
        val reportId = openReportId(userId, weekStart)
        AdoptedInterventions.deleteWhere {
            (AdoptedInterventions.reportId eq reportId) and
                (AdoptedInterventions.userId eq userId) and
                (AdoptedInterventions.interventionId eq interventionId)
        }
        Unit
    }

    private fun openReportId(userId: UUID, weekStart: LocalDate): UUID {
        val id = reportId(userId, weekStart)
            ?: throw ApiException(ErrorCode.NOT_FOUND, "Report not found.")
        if (!AdoptionWindow.isLatest(weekStart, clock)) {
            throw ApiException(
                ErrorCode.ADOPTION_WINDOW_CLOSED,
                "You can only change what you're trying on your latest report.",
            )
        }
        return id
    }

    private fun reportId(userId: UUID, weekStart: LocalDate): UUID? =
        WeeklyReports.select(WeeklyReports.id)
            .where { (WeeklyReports.userId eq userId) and (WeeklyReports.weekStart eq weekStart) }
            .singleOrNull()?.get(WeeklyReports.id)
}
