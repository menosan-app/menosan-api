package app.menosan.export

import app.menosan.common.ApiException
import app.menosan.common.ErrorCode
import app.menosan.common.WeekCalc
import app.menosan.common.notImplemented
import app.menosan.db.AdoptedInterventions
import app.menosan.db.Db
import app.menosan.db.Hotspots
import app.menosan.db.InterventionImpacts
import app.menosan.db.Interventions
import app.menosan.db.ReportRecommendations
import app.menosan.db.Users
import app.menosan.db.WasteEntries
import app.menosan.db.WeeklyReports
import app.menosan.plugins.toApiString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.Clock
import java.util.UUID

/** The `GET /v1/export` document (NFR16, plan §2.2 I9). Everything Menosan stores about the account. */
@Serializable
data class ExportDocument(
    val format: String,
    val exportVersion: Int,
    val exportedAt: String,
    val timezone: String,
    val profile: ExportProfile,
    val entries: List<ExportEntry>,
    val reports: List<ExportReport>,
)

@Serializable
data class ExportProfile(val id: String, val email: String, val displayName: String?, val createdAt: String, val consentedAt: String)

@Serializable
data class ExportEntry(
    val id: String,
    val name: String,
    val category: String,
    val subcategory: String,
    val quantity: Int,
    val source: String,
    val createdAt: String,
    val weekStart: String,
    val updatedAt: String,
    val receivedAt: String,
)

@Serializable
data class ExportReport(
    val weekStart: String,
    val weekEnd: String,
    val revision: Int,
    val algorithmVersion: Int,
    val generatedAt: String,
    val regeneratedAt: String?,
    val stats: JsonElement,
    val comparison: JsonElement?,
    val hotspots: List<ExportHotspot>,
    val adoptions: List<ExportAdoption>,
)

@Serializable
data class ExportHotspot(
    val rank: Int,
    val subcategory: String,
    val criteria: List<String>,
    val frequency: Int,
    val quantity: Int,
    val score: Double,
    val recommendations: List<ExportRecommendation>,
)

@Serializable
data class ExportRecommendation(
    val interventionId: String,
    val code: String,
    val title: String,
    val rank: Int,
    val note: String?,
    val continued: Boolean,
    val source: String,
)

/** An intervention adopted on the report it's listed under (the baseline week), with its measured impact if any. */
@Serializable
data class ExportAdoption(
    val interventionId: String,
    val code: String,
    val title: String,
    val targetSubcategory: String,
    val baselineWeekStart: String,
    val baselineQuantity: Int,
    val adoptedAt: String,
    val impact: ExportImpact?,
)

@Serializable
data class ExportImpact(val followupWeekStart: String, val baselineQuantity: Int, val followupQuantity: Int, val result: String)

const val EXPORT_FORMAT = "menosan-export"
const val EXPORT_VERSION = 1

fun interface DataExporter {
    suspend fun export(userId: UUID): ExportDocument
}

object StubDataExporter : DataExporter {
    override suspend fun export(userId: UUID) = notImplemented("Export")
}

/** Reads the whole account in one transaction, so the export is a consistent snapshot. All queries are scoped by `userId`. */
class ExposedDataExporter(private val db: Db, private val clock: Clock) : DataExporter {
    override suspend fun export(userId: UUID): ExportDocument = db.tx {
        val user = Users.selectAll().where { Users.id eq userId }.singleOrNull()
            ?: throw ApiException(ErrorCode.ACCOUNT_NOT_FOUND, "No Menosan account exists for this sign-in yet.")

        val entries = WasteEntries.selectAll().where { WasteEntries.userId eq userId }
            .orderBy(WasteEntries.createdAt to SortOrder.ASC, WasteEntries.id to SortOrder.ASC)
            .map {
                ExportEntry(
                    id = it[WasteEntries.id].toString(),
                    name = it[WasteEntries.name],
                    category = it[WasteEntries.category],
                    subcategory = it[WasteEntries.subcategoryCode],
                    quantity = it[WasteEntries.quantity],
                    source = it[WasteEntries.entrySource],
                    createdAt = it[WasteEntries.createdAt].toInstant().toApiString(),
                    weekStart = it[WasteEntries.weekStart].toString(),
                    updatedAt = it[WasteEntries.updatedAt].toInstant().toApiString(),
                    receivedAt = it[WasteEntries.receivedAt].toInstant().toApiString(),
                )
            }

        val reportRows = WeeklyReports.selectAll().where { WeeklyReports.userId eq userId }
            .orderBy(WeeklyReports.weekStart to SortOrder.ASC)
            .toList()
        val reportIds = reportRows.map { it[WeeklyReports.id] }

        val hotspotRows = if (reportIds.isEmpty()) emptyList() else {
            Hotspots.selectAll().where { Hotspots.reportId inList reportIds }
                .orderBy(Hotspots.reportId to SortOrder.ASC, Hotspots.rank to SortOrder.ASC)
                .toList()
        }
        val hotspotIds = hotspotRows.map { it[Hotspots.id] }

        val recommendationsByHotspot = if (hotspotIds.isEmpty()) emptyMap() else {
            ReportRecommendations
                .join(Interventions, JoinType.INNER, ReportRecommendations.interventionId, Interventions.id)
                .selectAll().where { ReportRecommendations.hotspotId inList hotspotIds }
                .orderBy(ReportRecommendations.rank to SortOrder.ASC, Interventions.code to SortOrder.ASC)
                .groupBy({ it[ReportRecommendations.hotspotId] }) {
                    ExportRecommendation(
                        interventionId = it[Interventions.id].toString(),
                        code = it[Interventions.code],
                        title = it[Interventions.title],
                        rank = it[ReportRecommendations.rank],
                        note = it[ReportRecommendations.note],
                        continued = it[ReportRecommendations.continued],
                        source = it[ReportRecommendations.recommendationSource],
                    )
                }
        }

        val adoptionRows = AdoptedInterventions
            .join(Interventions, JoinType.INNER, AdoptedInterventions.interventionId, Interventions.id)
            .selectAll().where { AdoptedInterventions.userId eq userId }
            .orderBy(AdoptedInterventions.adoptedAt to SortOrder.ASC, Interventions.code to SortOrder.ASC)
            .toList()
        val adoptionIds = adoptionRows.map { it[AdoptedInterventions.id] }
        val impactsByAdoption = if (adoptionIds.isEmpty()) emptyMap() else {
            InterventionImpacts.selectAll().where { InterventionImpacts.adoptionId inList adoptionIds }
                .associate {
                    it[InterventionImpacts.adoptionId] to ExportImpact(
                        followupWeekStart = it[InterventionImpacts.followupWeekStart].toString(),
                        baselineQuantity = it[InterventionImpacts.baselineQuantity],
                        followupQuantity = it[InterventionImpacts.followupQuantity],
                        result = it[InterventionImpacts.result],
                    )
                }
        }
        val adoptionsByReport = adoptionRows.groupBy({ it[AdoptedInterventions.reportId] }) {
            ExportAdoption(
                interventionId = it[Interventions.id].toString(),
                code = it[Interventions.code],
                title = it[Interventions.title],
                targetSubcategory = it[AdoptedInterventions.targetSubcategory],
                baselineWeekStart = it[AdoptedInterventions.baselineWeekStart].toString(),
                baselineQuantity = it[AdoptedInterventions.baselineQuantity],
                adoptedAt = it[AdoptedInterventions.adoptedAt].toInstant().toApiString(),
                impact = impactsByAdoption[it[AdoptedInterventions.id]],
            )
        }

        val hotspotsByReport = hotspotRows.groupBy({ it[Hotspots.reportId] }) {
            ExportHotspot(
                rank = it[Hotspots.rank],
                subcategory = it[Hotspots.subcategoryCode],
                criteria = it[Hotspots.criteria],
                frequency = it[Hotspots.frequency],
                quantity = it[Hotspots.quantity],
                score = it[Hotspots.score].toDouble(),
                recommendations = recommendationsByHotspot[it[Hotspots.id]].orEmpty(),
            )
        }

        val reports = reportRows.map {
            val reportId = it[WeeklyReports.id]
            ExportReport(
                weekStart = it[WeeklyReports.weekStart].toString(),
                weekEnd = it[WeeklyReports.weekEnd].toString(),
                revision = it[WeeklyReports.revision],
                algorithmVersion = it[WeeklyReports.algorithmVersion],
                generatedAt = it[WeeklyReports.generatedAt].toInstant().toApiString(),
                regeneratedAt = it[WeeklyReports.regeneratedAt]?.toInstant()?.toApiString(),
                stats = it[WeeklyReports.stats],
                comparison = it[WeeklyReports.comparison],
                hotspots = hotspotsByReport[reportId].orEmpty(),
                adoptions = adoptionsByReport[reportId].orEmpty(),
            )
        }

        ExportDocument(
            format = EXPORT_FORMAT,
            exportVersion = EXPORT_VERSION,
            exportedAt = clock.instant().toApiString(),
            timezone = WeekCalc.TIMEZONE_ID,
            profile = ExportProfile(
                id = user[Users.id].toString(),
                email = user[Users.email],
                displayName = user[Users.displayName],
                createdAt = user[Users.createdAt].toInstant().toApiString(),
                consentedAt = user[Users.consentedAt].toInstant().toApiString(),
            ),
            entries = entries,
            reports = reports,
        )
    }
}
