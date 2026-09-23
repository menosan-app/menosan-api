package app.menosan.interventions

import app.menosan.account.ExposedUserRepository
import app.menosan.db.Db
import app.menosan.db.Hotspots
import app.menosan.db.Interventions
import app.menosan.db.WeeklyReports
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Inserts users, reports, hotspots, and recommendations directly, standing in for BE-3's ReportService. */
class ReportFixtures(private val db: Db) {
    private val users = ExposedUserRepository(db)

    suspend fun user(uid: String = "uid-" + UUID.randomUUID()): UUID =
        users.createIfAbsent(uid, "$uid@example.com", null, Instant.parse("2026-09-01T00:00:00Z")).first.id

    suspend fun interventionId(code: String): UUID = db.tx {
        Interventions.selectAll().where { Interventions.code eq code }.single()[Interventions.id]
    }

    /** A report with one hotspot per entry of [hotspots] (subcategory → quantity) and the given recommendation codes. */
    suspend fun report(
        userId: UUID,
        weekStart: LocalDate,
        hotspots: Map<String, Int>,
        recommendations: Map<String, List<String>>,
    ): Pair<UUID, Map<String, UUID>> {
        val ids = recommendations.values.flatten().associateWith { interventionId(it) }
        return db.tx {
            val reportId = WeeklyReports.insert {
                it[WeeklyReports.userId] = userId
                it[WeeklyReports.weekStart] = weekStart
                it[weekEnd] = weekStart.plusDays(6)
                it[stats] = JsonObject(emptyMap())
                it[algorithmVersion] = 1
            }[WeeklyReports.id]
            val hotspotIds = mutableMapOf<String, UUID>()
            hotspots.entries.forEachIndexed { index, (subcategory, quantity) ->
                val hotspotId = Hotspots.insert {
                    it[Hotspots.reportId] = reportId
                    it[subcategoryCode] = subcategory
                    it[rank] = index + 1
                    it[criteria] = listOf("HIGHEST_QUANTITY")
                    it[frequency] = 1
                    it[Hotspots.quantity] = quantity
                    it[score] = BigDecimal("1.0000")
                }[Hotspots.id]
                hotspotIds[subcategory] = hotspotId
                val picks = recommendations[subcategory].orEmpty().mapIndexed { i, code ->
                    RecommendationPick(ids.getValue(code), i + 1, if (i == 0) "Note for $code" else null, false, RecommendationSource.RULES)
                }
                insertRecommendations(hotspotId, picks)
            }
            reportId to hotspotIds
        }
    }
}
