package app.menosan.reports

import app.menosan.account.ExposedUserRepository
import app.menosan.common.OverridableClock
import app.menosan.common.WeekCalc
import app.menosan.db.AdoptedInterventions
import app.menosan.db.Interventions
import app.menosan.db.PostgresTestDb
import app.menosan.db.WasteEntries
import app.menosan.interventions.InterventionEngine
import app.menosan.interventions.PreviousAdoption
import app.menosan.interventions.RecommendationInput
import app.menosan.interventions.RecommendationPick
import app.menosan.interventions.RecommendationSource
import app.menosan.taxonomy.Taxonomy
import org.jetbrains.exposed.v1.jdbc.insert
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Weeks used by the report tests. "Now" defaults to Wed 2026-09-30 (current week 09-27; 09-13 and 09-20 are closed). */
val W1: LocalDate = LocalDate.parse("2026-09-13")
val W2: LocalDate = LocalDate.parse("2026-09-20")
val W3: LocalDate = LocalDate.parse("2026-09-27")
val W4: LocalDate = LocalDate.parse("2026-10-04")
val NOW_IN_W3: Instant = Instant.parse("2026-09-30T04:00:00Z")
val NOW_IN_W4: Instant = Instant.parse("2026-10-07T04:00:00Z")

/** Recommends the given interventions for each subcategory and records every call. */
class FakeInterventionEngine : InterventionEngine {
    /** Subcategory code → intervention ids to return, in rank order. */
    val bySubcategory = ConcurrentHashMap<String, List<UUID>>()
    val calls = CopyOnWriteArrayList<RecommendationInput>()
    var failWith: Exception? = null

    override suspend fun recommend(input: RecommendationInput): List<RecommendationPick> {
        calls += input
        failWith?.let { throw it }
        return bySubcategory[input.hotspot.subcategoryCode].orEmpty().mapIndexed { i, id ->
            RecommendationPick(id, rank = i + 1, note = null, continued = false, source = RecommendationSource.RULES)
        }
    }

    fun calledFor(): List<String> = calls.map { it.hotspot.subcategoryCode }
    fun previousAdoptionsSeen(): List<PreviousAdoption> = calls.flatMap { it.previousAdoptions }.distinct()
}

/** Writes rows straight into the shared embedded Postgres. Every call creates fresh ids, so tests don't collide. */
class ReportFixtures(
    now: Instant = NOW_IN_W3,
    val engine: FakeInterventionEngine = FakeInterventionEngine(),
) {
    val db = PostgresTestDb.db
    val taxonomy: Taxonomy = Taxonomy.loadDefault()
    val clock = OverridableClock().apply { setOverride(now) }
    val store = ReportStore(db)
    val service = DefaultReportService(store, clock, taxonomy, engine)
    private val users = ExposedUserRepository(db)

    suspend fun newUser(uid: String = "uid-" + UUID.randomUUID()): UUID =
        users.createIfAbsent(uid, "$uid@example.com", null, NOW_IN_W3).first.id

    /** Logs one entry in [week] (Monday 09:00 PHT). */
    suspend fun log(userId: UUID, week: LocalDate, subcategory: String, quantity: Int = 1) {
        val createdAt = WeekCalc.startInstant(week).plusSeconds(33 * 3600)
        db.tx {
            WasteEntries.insert {
                it[id] = UUID.randomUUID()
                it[WasteEntries.userId] = userId
                it[name] = "Test item"
                it[category] = taxonomy.categoryOf(subcategory)!!.name
                it[subcategoryCode] = subcategory
                it[WasteEntries.quantity] = quantity
                it[entrySource] = "MANUAL"
                it[WasteEntries.createdAt] = createdAt.atOffset(ZoneOffset.UTC)
                it[weekStart] = week
            }
        }
    }

    /** A curated library item (BE-4 seeds the real ones in V3). */
    /** Creates [count] library items for [subcategory] and makes the fake engine recommend them. */
    suspend fun recommendable(subcategory: String, count: Int = 2): List<UUID> =
        List(count) { intervention(subcategory, "Try this ${it + 1}") }.also { engine.bySubcategory[subcategory] = it }

    suspend fun intervention(subcategory: String, title: String = "Try this"): UUID = db.tx {
        val id = UUID.randomUUID()
        Interventions.insert {
            it[Interventions.id] = id
            it[code] = "TEST_" + id.toString().replace("-", "").uppercase()
            it[subcategoryCode] = subcategory
            it[type] = "PREVENT"
            it[Interventions.title] = title
            it[description] = "A test intervention."
            it[howTo] = listOf("Step one", "Step two")
            it[costLevel] = "FREE"
            it[effort] = "LOW"
        }
        id
    }

    /** What BE-4's adoption endpoint writes. */
    suspend fun adopt(userId: UUID, reportId: UUID, interventionId: UUID, target: String, baselineWeek: LocalDate, baseline: Int) {
        db.tx {
            AdoptedInterventions.insert {
                it[AdoptedInterventions.userId] = userId
                it[AdoptedInterventions.reportId] = reportId
                it[AdoptedInterventions.interventionId] = interventionId
                it[targetSubcategory] = target
                it[baselineWeekStart] = baselineWeek
                it[baselineQuantity] = baseline
            }
        }
    }
}
