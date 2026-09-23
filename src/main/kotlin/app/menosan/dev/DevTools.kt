package app.menosan.dev

import app.menosan.common.ApiException
import app.menosan.common.ErrorCode
import app.menosan.common.OverridableClock
import app.menosan.common.WeekCalc
import app.menosan.db.AdoptedInterventions
import app.menosan.db.Db
import app.menosan.db.Users
import app.menosan.db.WasteEntries
import app.menosan.db.WeeklyReports
import app.menosan.entries.EntryRepository
import app.menosan.entries.EntrySource
import app.menosan.entries.WasteEntry
import app.menosan.plugins.toApiString
import app.menosan.reports.ReportResponse
import app.menosan.reports.ReportService
import app.menosan.taxonomy.Taxonomy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.random.Random

private val log = LoggerFactory.getLogger("app.menosan.dev")

@Serializable
data class DevClockResponse(val serverNow: String, val overridden: Boolean, val currentWeekStart: String)

@Serializable
data class SeededWeek(
    val weekStart: String,
    val entries: Int,
    /** Null when no report was generated (it always is for seeded weeks). */
    val analyzedQuantity: Int?,
    val hotspots: List<String>,
    /** Codes of the interventions the seed adopted on this week's report. */
    val adopted: List<String>,
    /** Impact rows on this week's report (interventions adopted the week before). */
    val impacts: Int,
)

@Serializable
data class SeedHistoryResponse(val userId: String, val weeks: List<SeededWeek>)

@Serializable
data class ResetResponse(val userId: String, val reportsDeleted: Int, val entriesDeleted: Int)

/**
 * Staging-only tools behind `/internal/dev/...` (plan §8.2, BE-5): move the server clock, give a demo account
 * a few past weeks of history with reports, wipe a demo account's data, and (re)generate one report.
 * Only built when DEV_TOOLS_ENABLED=true, which [app.menosan.config.AppConfig] never allows in prod.
 */
class DevTools(
    private val db: Db,
    val clock: OverridableClock,
    private val taxonomy: Taxonomy,
    private val entries: EntryRepository,
    private val reports: ReportService,
) {
    fun clockState() = DevClockResponse(
        serverNow = clock.instant().toApiString(),
        overridden = clock.isOverridden,
        currentWeekStart = WeekCalc.currentWeekStart(clock).toString(),
    )

    /** Sets "now" for the whole server (every user), or restores real time when [now] is null. */
    fun setClock(now: Instant?): DevClockResponse {
        clock.setOverride(now)
        log.warn("Dev clock {}: now = {}", if (now == null) "cleared" else "set", clock.instant())
        return clockState()
    }

    /** The account with this email (case-insensitive). 404 if none, 409 if several share it. */
    suspend fun userIdByEmail(email: String): UUID {
        val ids = db.tx {
            Users.select(Users.id).where { Users.email.lowerCase() eq email.trim().lowercase() }.map { it[Users.id] }
        }
        return when (ids.size) {
            0 -> throw ApiException(ErrorCode.NOT_FOUND, "No account with that email.")
            1 -> ids.single()
            else -> throw ApiException(ErrorCode.CONFLICT, "Several accounts share that email.")
        }
    }

    /**
     * Logs synthetic household entries in the last [weeks] closed weeks and generates their reports, oldest
     * first. With [adopt], every report except the latest gets its top recommendation adopted, and the next
     * week logs less of that subcategory, so the demo shows impact results. The latest report is left for
     * the tester to adopt from the app. Same [seed] → same entries.
     */
    suspend fun seedHistory(userId: UUID, weeks: Int, adopt: Boolean, seed: Long): SeedHistoryResponse {
        val current = WeekCalc.currentWeekStart(clock)
        val targetWeeks = (weeks downTo 1).map { current.minusDays(7L * it) }
        for (week in targetWeeks) {
            if (entries.listForWeek(userId, week).isNotEmpty()) {
                throw ApiException(
                    ErrorCode.CONFLICT,
                    "This account already has entries in a seeded week. Reset it first.",
                    buildJsonObject { put("weekStart", JsonPrimitive(week.toString())) },
                )
            }
        }

        val random = Random(seed)
        val seeded = mutableListOf<SeededWeek>()
        var adoptedTargets = emptyMap<String, Int>() // subcategory → baseline quantity, from last week's adoptions
        for (week in targetWeeks) {
            val logged = syntheticWeek(week, adoptedTargets, random)
            for (entry in logged) entries.upsert(userId, entry.toWasteEntry(userId, week))
            reports.ensureReport(userId, week)
            val report = reports.getReport(userId, week)

            val adoptedCodes = mutableListOf<String>()
            adoptedTargets = emptyMap()
            if (adopt && week != targetWeeks.last() && report != null) {
                val hotspot = report.hotspots.firstOrNull()
                val pick = hotspot?.recommendations?.firstOrNull()
                if (hotspot != null && pick != null) {
                    adoptOnReport(userId, week, UUID.fromString(pick.interventionId), hotspot.subcategory, hotspot.quantity)
                    adoptedCodes += pick.code
                    adoptedTargets = mapOf(hotspot.subcategory to hotspot.quantity)
                }
            }
            seeded += report.toSeededWeek(week, logged.size, adoptedCodes)
        }
        log.info("Seeded {} weeks of history for userId={}", weeks, userId)
        return SeedHistoryResponse(userId.toString(), seeded)
    }

    /** Deletes the account's entries and reports (with their hotspots, recommendations, adoptions, and impacts). Keeps the account. */
    suspend fun reset(userId: UUID): ResetResponse {
        val (reportsDeleted, entriesDeleted) = db.tx {
            WeeklyReports.deleteWhere { WeeklyReports.userId eq userId } to
                WasteEntries.deleteWhere { WasteEntries.userId eq userId }
        }
        log.info("Reset dev data for userId={}", userId)
        return ResetResponse(userId.toString(), reportsDeleted, entriesDeleted)
    }

    /**
     * Generates the report for a closed [weekStart] if it is missing. With [regenerate], an existing report is
     * rebuilt as after a late sync (§5.6, also refreshes the next week's report). Null when nothing was logged.
     */
    suspend fun generateReport(userId: UUID, weekStart: LocalDate, regenerate: Boolean): ReportResponse? {
        if (!WeekCalc.isClosed(weekStart, clock)) {
            throw ApiException(ErrorCode.VALIDATION_FAILED, "That week hasn't closed yet.")
        }
        if (regenerate) reports.onLateEntry(userId, weekStart) else reports.ensureReport(userId, weekStart)
        return reports.getReport(userId, weekStart)
    }

    /** What the adoption endpoint writes, without its window check (seeded reports are in the past). */
    private suspend fun adoptOnReport(userId: UUID, week: LocalDate, interventionId: UUID, target: String, baseline: Int) {
        db.tx {
            val reportId = WeeklyReports.select(WeeklyReports.id)
                .where { (WeeklyReports.userId eq userId) and (WeeklyReports.weekStart eq week) }
                .single()[WeeklyReports.id]
            AdoptedInterventions.insertIgnore {
                it[AdoptedInterventions.userId] = userId
                it[AdoptedInterventions.reportId] = reportId
                it[AdoptedInterventions.interventionId] = interventionId
                it[targetSubcategory] = target
                it[baselineWeekStart] = week
                it[baselineQuantity] = baseline
                // Sunday 09:00 PHT right after the week closed, when the tester would have opened the report.
                it[adoptedAt] = WeekCalc.endExclusive(week).plusSeconds(9 * 3600).atOffset(ZoneOffset.UTC)
            }
        }
    }

    private fun ReportResponse?.toSeededWeek(week: LocalDate, entryCount: Int, adopted: List<String>) = SeededWeek(
        weekStart = week.toString(),
        entries = entryCount,
        analyzedQuantity = this?.stats?.analyzedTotals?.quantity,
        hotspots = this?.hotspots?.map { it.subcategory }.orEmpty(),
        adopted = adopted,
        impacts = this?.impacts?.size ?: 0,
    )

    private class SyntheticEntry(val name: String, val subcategory: String, val quantity: Int, val createdAt: Instant)

    private fun SyntheticEntry.toWasteEntry(userId: UUID, week: LocalDate) = WasteEntry(
        id = UUID.randomUUID(),
        userId = userId,
        name = name,
        category = taxonomy.categoryOf(subcategory)!!,
        subcategoryCode = subcategory,
        quantity = quantity,
        source = EntrySource.MANUAL,
        createdAt = createdAt,
        weekStart = week,
        updatedAt = createdAt,
    )

    /** One week of a typical Dumaguete household's logging. A subcategory in [reduced] is logged at under half its baseline. */
    private fun syntheticWeek(week: LocalDate, reduced: Map<String, Int>, random: Random): List<SyntheticEntry> {
        fun at(): Instant = WeekCalc.startInstant(week)
            .plusSeconds(random.nextLong(0, 7) * 86_400 + random.nextLong(7, 22) * 3_600 + random.nextLong(0, 3_600))

        return HOUSEHOLD.flatMap { item ->
            val baseline = reduced[item.subcategory]
            if (baseline != null) {
                // One piece per entry, baseline / 2 entries: the follow-up always shows DECREASED.
                List(baseline / 2) { SyntheticEntry(item.names.random(random), item.subcategory, 1, at()) }
            } else {
                List(random.nextInt(item.entriesPerWeek.first, item.entriesPerWeek.last + 1)) {
                    val pieces = random.nextInt(item.piecesPerEntry.first, item.piecesPerEntry.last + 1)
                    SyntheticEntry(item.names.random(random), item.subcategory, pieces, at())
                }
            }
        }
    }

    private class SeedItem(
        val subcategory: String,
        val names: List<String>,
        val entriesPerWeek: IntRange,
        val piecesPerEntry: IntRange,
    )

    private companion object {
        val HOUSEHOLD = listOf(
            SeedItem("RES_SACHETS", listOf("Coffee 3-in-1 sachet", "Shampoo sachet", "Condiment packet"), 4..7, 1..3),
            SeedItem("RES_PLASTIC_BAGS", listOf("Sando bag", "Labo bag", "Ice bag"), 3..5, 1..3),
            SeedItem("BIO_FOOD_LEFTOVERS", listOf("Leftover rice", "Leftover ulam"), 2..4, 1..2),
            SeedItem("BIO_PEELS_SCRAPS", listOf("Banana peels", "Vegetable scraps", "Eggshells"), 2..4, 1..4),
            SeedItem("REC_PET_BOTTLES", listOf("Softdrink bottle", "Water bottle"), 1..3, 1..2),
            SeedItem("RES_SNACK_WRAPPERS", listOf("Chichirya pack", "Biscuit wrapper"), 1..3, 1..3),
            SeedItem("RES_STYROFOAM", listOf("Takeout styro box"), 0..2, 1..2),
            SeedItem("REC_PAPER_CARDBOARD", listOf("Receipt", "Carton box"), 0..2, 1..2),
            SeedItem("SPC_BATTERIES", listOf("AA battery"), 0..1, 1..2),
        )
    }
}
