package app.menosan

import app.menosan.account.AccountDeletionService
import app.menosan.account.ExposedUserRepository
import app.menosan.account.FirebaseUserAdmin
import app.menosan.db.AdoptedInterventions
import app.menosan.db.DbHealthCheck
import app.menosan.db.Hotspots
import app.menosan.db.InterventionImpacts
import app.menosan.db.Interventions
import app.menosan.db.PostgresTestDb
import app.menosan.db.ReportRecommendations
import app.menosan.db.WeeklyReports
import app.menosan.entries.ExposedEntryRepository
import app.menosan.export.ExposedDataExporter
import app.menosan.plugins.VerifiedToken
import app.menosan.reports.ReportService
import app.menosan.reports.StubReportService
import app.menosan.taxonomy.Taxonomy
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Wednesday 2026-09-30 12:00 PHT: the current week is 2026-09-27, and 2026-09-20 is closed. */
const val DB_TEST_NOW = "2026-09-30T04:00:00Z"
val CURRENT_WEEK: LocalDate = LocalDate.parse("2026-09-27")
val PREVIOUS_WEEK: LocalDate = LocalDate.parse("2026-09-20")

/** Records `onLateEntry` and `catchUp` calls. Delegates everything else to the stub. */
class RecordingReportService : ReportService by StubReportService {
    val lateEntries = CopyOnWriteArrayList<Pair<UUID, LocalDate>>()
    val catchUps = CopyOnWriteArrayList<UUID>()
    var failLateEntry = false

    override suspend fun onLateEntry(userId: UUID, weekStart: LocalDate) {
        lateEntries += userId to weekStart
        if (failLateEntry) error("report refresh failed")
    }

    override suspend fun catchUp(userId: UUID) {
        catchUps += userId
    }
}

class FakeFirebaseUsers : FirebaseUserAdmin {
    val deleted = CopyOnWriteArrayList<String>()
    var failNext = false

    override suspend fun deleteUser(firebaseUid: String) {
        if (failNext) {
            failNext = false
            error("Firebase is unreachable")
        }
        deleted += firebaseUid
    }
}

/**
 * Route tests against the shared embedded PostgreSQL. Every env gets fresh Firebase uids and tokens,
 * so tests never see each other's rows.
 */
class DbTestEnv(val clock: Clock = fixedClock(DB_TEST_NOW)) {
    private val suffix = UUID.randomUUID().toString()
    val aliceToken = "alice-$suffix"
    val bobToken = "bob-$suffix"
    val noAccountToken = "nobody-$suffix"
    val aliceUid = "uid-alice-$suffix"
    val bobUid = "uid-bob-$suffix"
    val reports = RecordingReportService()
    val firebase = FakeFirebaseUsers()
    val db = PostgresTestDb.db

    val deps = AppDeps(
        config = testConfig,
        clock = clock,
        taxonomy = Taxonomy.loadDefault(),
        dbHealth = DbHealthCheck { true },
        tokenVerifier = FakeTokenVerifier(
            mapOf(
                aliceToken to VerifiedToken(aliceUid, "alice-$suffix@example.com", "Alice"),
                bobToken to VerifiedToken(bobUid, "bob-$suffix@example.com", null),
                noAccountToken to VerifiedToken("uid-nobody-$suffix", "nobody@example.com", null),
            ),
        ),
        users = ExposedUserRepository(db),
        entries = ExposedEntryRepository(db),
        reports = reports,
        accountDeletion = AccountDeletionService(db, firebase),
        exporter = ExposedDataExporter(db, clock),
    )
}

fun dbTest(env: DbTestEnv = DbTestEnv(), block: suspend ApplicationTestBuilder.(DbTestEnv) -> Unit) = testApplication {
    application { module(env.deps) }
    block(env)
}

/** Creates the account and returns its user id. */
suspend fun ApplicationTestBuilder.createAccount(token: String): UUID {
    val response = client.post("/v1/account") {
        bearerAuth(token)
        contentType(ContentType.Application.Json)
        setBody("""{"consent":true}""")
    }
    return UUID.fromString(response.json()["id"]!!.jsonPrimitive.content)
}

fun entryBody(
    name: String = "Coffee 3-in-1 sachet",
    subcategory: String = "RES_SACHETS",
    quantity: Int = 3,
    source: String = "MANUAL",
    createdAt: String = "2026-09-29T01:00:00Z",
    id: UUID? = null,
): JsonObject = buildJsonObject {
    id?.let { put("id", it.toString()) }
    put("name", name)
    put("subcategory", subcategory)
    put("quantity", quantity)
    put("source", source)
    put("createdAt", createdAt)
}

suspend fun ApplicationTestBuilder.putEntry(token: String, id: UUID, body: JsonObject): HttpResponse =
    putEntryRaw(token, id.toString(), body.toString())

suspend fun ApplicationTestBuilder.putEntryRaw(token: String, id: String, body: String): HttpResponse =
    client.put("/v1/entries/$id") {
        bearerAuth(token)
        contentType(ContentType.Application.Json)
        setBody(body)
    }

suspend fun ApplicationTestBuilder.deleteEntry(token: String, id: UUID): HttpResponse =
    client.delete("/v1/entries/$id") { bearerAuth(token) }

suspend fun ApplicationTestBuilder.listEntries(token: String, weekStart: LocalDate? = null): HttpResponse =
    client.get("/v1/entries" + (weekStart?.let { "?weekStart=$it" } ?: "")) { bearerAuth(token) }

suspend fun ApplicationTestBuilder.sync(
    token: String,
    upserts: List<JsonElement> = emptyList(),
    deletes: List<JsonElement> = emptyList(),
): HttpResponse =
    client.post("/v1/entries/sync") {
        bearerAuth(token)
        contentType(ContentType.Application.Json)
        setBody(JsonObject(mapOf("upserts" to JsonArray(upserts), "deletes" to JsonArray(deletes))).toString())
    }

fun jsonId(id: UUID) = JsonPrimitive(id.toString())

/** A test-only intervention. Inactive, so it never counts as library content; delete it with [deleteTestIntervention]. */
suspend fun insertTestIntervention(): UUID = PostgresTestDb.db.tx {
    Interventions.insert {
        it[code] = "TEST_" + UUID.randomUUID().toString().replace("-", "_").uppercase()
        it[subcategoryCode] = "RES_SACHETS"
        it[type] = "REDUCE"
        it[title] = "Test refill"
        it[description] = "Test only."
        it[howTo] = listOf("Step one")
        it[costLevel] = "FREE"
        it[effort] = "LOW"
        it[active] = false
    }[Interventions.id]
}

/** Call after the users that reference it are deleted (recommendations and adoptions don't cascade from interventions). */
suspend fun deleteTestIntervention(id: UUID) = PostgresTestDb.db.tx {
    ReportRecommendations.deleteWhere { ReportRecommendations.interventionId eq id }
    AdoptedInterventions.deleteWhere { AdoptedInterventions.interventionId eq id }
    Interventions.deleteWhere { Interventions.id eq id }
}

class SeededReport(val reportId: UUID, val hotspotId: UUID, val adoptionId: UUID)

/** A report for [weekStart] with one hotspot, one recommendation, one adoption, and its measured impact. */
suspend fun seedReportGraph(userId: UUID, interventionId: UUID, weekStart: LocalDate): SeededReport = PostgresTestDb.db.tx {
    val at = Instant.parse(DB_TEST_NOW).atOffset(ZoneOffset.UTC)
    val reportId = WeeklyReports.insert {
        it[WeeklyReports.userId] = userId
        it[WeeklyReports.weekStart] = weekStart
        it[weekEnd] = weekStart.plusDays(6)
        it[stats] = buildJsonObject { put("analyzedTotals", buildJsonObject { put("frequency", 1); put("quantity", 3) }) }
        it[comparison] = null
        it[algorithmVersion] = 1
        it[generatedAt] = at
    }[WeeklyReports.id]
    val hotspotId = Hotspots.insert {
        it[Hotspots.reportId] = reportId
        it[subcategoryCode] = "RES_SACHETS"
        it[rank] = 1
        it[criteria] = listOf("MOST_FREQUENT", "HIGHEST_QUANTITY", "AVOIDABLE")
        it[frequency] = 1
        it[quantity] = 3
        it[score] = BigDecimal("1.0000")
    }[Hotspots.id]
    ReportRecommendations.insert {
        it[ReportRecommendations.hotspotId] = hotspotId
        it[ReportRecommendations.interventionId] = interventionId
        it[rank] = 1
        it[note] = "Try a refill station."
        it[recommendationSource] = "RULES"
    }
    val adoptionId = AdoptedInterventions.insert {
        it[AdoptedInterventions.userId] = userId
        it[AdoptedInterventions.reportId] = reportId
        it[AdoptedInterventions.interventionId] = interventionId
        it[targetSubcategory] = "RES_SACHETS"
        it[baselineWeekStart] = weekStart
        it[baselineQuantity] = 3
        it[adoptedAt] = at
    }[AdoptedInterventions.id]
    InterventionImpacts.insert {
        it[InterventionImpacts.adoptionId] = adoptionId
        it[followupReportId] = null
        it[followupWeekStart] = weekStart.plusDays(7)
        it[baselineQuantity] = 3
        it[followupQuantity] = 1
        it[result] = "DECREASED"
    }
    SeededReport(reportId, hotspotId, adoptionId)
}
