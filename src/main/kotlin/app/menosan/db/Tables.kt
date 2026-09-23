package app.menosan.db

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import org.jetbrains.exposed.v1.json.jsonb
import java.util.UUID

/*
 * Exposed mappings for every table in V1__init.sql (plan §7). The schema itself is owned by Flyway
 * migrations; these objects never create or alter tables.
 *
 * Ids with a DB default use a client-side UUID default so inserts can return the id without a round trip.
 * Timestamp columns with a DB default keep that default, but code should normally set them from the
 * injected Clock (plan §4).
 */

private val dbJson = Json { encodeDefaults = true; explicitNulls = true }

object Users : Table("users") {
    val id = javaUUID("id").clientDefault { UUID.randomUUID() }
    val firebaseUid = text("firebase_uid").uniqueIndex()
    val email = text("email")
    val displayName = text("display_name").nullable()
    val consentedAt = timestampWithTimeZone("consented_at")
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestampWithTimeZone)
    override val primaryKey = PrimaryKey(id)
}

object WasteSubcategories : Table("waste_subcategories") {
    val code = text("code")
    val category = text("category")
    val label = text("label")
    val examples = array("examples", TextColumnType())
    val avoidable = bool("avoidable")
    val sortOrder = integer("sort_order")
    override val primaryKey = PrimaryKey(code)
}

object WasteEntries : Table("waste_entries") {
    val id = javaUUID("id") // client-generated (NFR8)
    val userId = javaUUID("user_id").references(Users.id, onDelete = ReferenceOption.CASCADE)
    val name = text("name")
    val category = text("category")
    val subcategoryCode = text("subcategory_code").references(WasteSubcategories.code)
    val quantity = integer("quantity")
    val entrySource = text("source")
    val createdAt = timestampWithTimeZone("created_at")
    val weekStart = date("week_start")
    val updatedAt = timestampWithTimeZone("updated_at").defaultExpression(CurrentTimestampWithTimeZone)
    val receivedAt = timestampWithTimeZone("received_at").defaultExpression(CurrentTimestampWithTimeZone)
    override val primaryKey = PrimaryKey(id)
}

object Interventions : Table("interventions") {
    val id = javaUUID("id").clientDefault { UUID.randomUUID() }
    val code = text("code").uniqueIndex()
    val subcategoryCode = text("subcategory_code").references(WasteSubcategories.code)
    val type = text("type")
    val title = text("title")
    val description = text("description")
    val howTo = array("how_to", TextColumnType())
    val costLevel = text("cost_level")
    val effort = text("effort")
    val active = bool("active").default(true)
    override val primaryKey = PrimaryKey(id)
}

object WeeklyReports : Table("weekly_reports") {
    val id = javaUUID("id").clientDefault { UUID.randomUUID() }
    val userId = javaUUID("user_id").references(Users.id, onDelete = ReferenceOption.CASCADE)
    val weekStart = date("week_start")
    val weekEnd = date("week_end")
    val stats = jsonb("stats", dbJson, JsonElement.serializer())
    val comparison = jsonb("comparison", dbJson, JsonElement.serializer()).nullable()
    val algorithmVersion = integer("algorithm_version")
    val revision = integer("revision").default(1)
    val generatedAt = timestampWithTimeZone("generated_at").defaultExpression(CurrentTimestampWithTimeZone)
    val regeneratedAt = timestampWithTimeZone("regenerated_at").nullable()
    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(userId, weekStart)
    }
}

object Hotspots : Table("hotspots") {
    val id = javaUUID("id").clientDefault { UUID.randomUUID() }
    val reportId = javaUUID("report_id").references(WeeklyReports.id, onDelete = ReferenceOption.CASCADE)
    val subcategoryCode = text("subcategory_code").references(WasteSubcategories.code)
    val rank = integer("rank")
    val criteria = array("criteria", TextColumnType())
    val frequency = integer("frequency")
    val quantity = integer("quantity")
    val score = decimal("score", 6, 4)
    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(reportId, subcategoryCode)
    }
}

object ReportRecommendations : Table("report_recommendations") {
    val id = javaUUID("id").clientDefault { UUID.randomUUID() }
    val hotspotId = javaUUID("hotspot_id").references(Hotspots.id, onDelete = ReferenceOption.CASCADE)
    val interventionId = javaUUID("intervention_id").references(Interventions.id)
    val rank = integer("rank")
    val note = text("note").nullable()
    val continued = bool("continued").default(false)
    val recommendationSource = text("source")
    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(hotspotId, interventionId)
    }
}

object AdoptedInterventions : Table("adopted_interventions") {
    val id = javaUUID("id").clientDefault { UUID.randomUUID() }
    val userId = javaUUID("user_id").references(Users.id, onDelete = ReferenceOption.CASCADE)
    val reportId = javaUUID("report_id").references(WeeklyReports.id, onDelete = ReferenceOption.CASCADE)
    val interventionId = javaUUID("intervention_id").references(Interventions.id)
    val targetSubcategory = text("target_subcategory").references(WasteSubcategories.code)
    val baselineWeekStart = date("baseline_week_start")
    val baselineQuantity = integer("baseline_quantity")
    val adoptedAt = timestampWithTimeZone("adopted_at").defaultExpression(CurrentTimestampWithTimeZone)
    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(reportId, interventionId)
    }
}

object InterventionImpacts : Table("intervention_impacts") {
    val id = javaUUID("id").clientDefault { UUID.randomUUID() }
    val adoptionId = javaUUID("adoption_id").references(AdoptedInterventions.id, onDelete = ReferenceOption.CASCADE)
        .uniqueIndex()
    val followupReportId = javaUUID("followup_report_id")
        .references(WeeklyReports.id, onDelete = ReferenceOption.CASCADE).nullable()
    val followupWeekStart = date("followup_week_start")
    val baselineQuantity = integer("baseline_quantity")
    val followupQuantity = integer("followup_quantity")
    val result = text("result")
    override val primaryKey = PrimaryKey(id)
}

val ALL_TABLES: List<Table> = listOf(
    Users, WasteSubcategories, WasteEntries, Interventions, WeeklyReports,
    Hotspots, ReportRecommendations, AdoptedInterventions, InterventionImpacts,
)
