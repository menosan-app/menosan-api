package app.menosan.reports

import app.menosan.analytics.HotspotCriterion.AVOIDABLE
import app.menosan.analytics.HotspotCriterion.HIGHEST_QUANTITY
import app.menosan.analytics.HotspotCriterion.MOST_FREQUENT
import app.menosan.analytics.Trend
import app.menosan.db.AdoptedInterventions
import app.menosan.db.ReportRecommendations
import app.menosan.db.WeeklyReports
import app.menosan.interventions.PreviousAdoption
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ReportService against a real PostgreSQL 17 (plan §5.5–§5.6, BE-3 DoD). */
class ReportServiceTest {

    @Test
    fun `generates stats, hotspots, recommendations, and comparison for a closed week`() = runBlocking {
        val f = ReportFixtures()
        val user = f.newUser()
        val sachetPicks = f.recommendable("RES_SACHETS")
        f.log(user, W1, "RES_SACHETS", 10)
        f.log(user, W2, "RES_SACHETS", 3)
        f.log(user, W2, "RES_SACHETS", 2)
        f.log(user, W2, "BIO_PEELS_SCRAPS", 1)
        f.log(user, W2, "SPC_BATTERIES", 4)

        val report = assertNotNull(f.service.getReport(user, W2))
        assertEquals("2026-09-20", report.weekStart)
        assertEquals("2026-09-26", report.weekEnd)
        assertEquals(1, report.revision)
        assertTrue(report.isLatest)
        assertEquals(6, report.stats.analyzedTotals.quantity)
        assertEquals(4, report.stats.special.quantity)

        val hotspot = report.hotspots.single()
        assertEquals("RES_SACHETS", hotspot.subcategory)
        assertEquals(listOf(MOST_FREQUENT, HIGHEST_QUANTITY, AVOIDABLE), hotspot.criteria)
        assertEquals(sachetPicks.map { it.toString() }, hotspot.recommendations.map { it.interventionId })
        assertEquals(listOf("Try this 1", "Try this 2"), hotspot.recommendations.map { it.title })
        assertTrue(hotspot.recommendations.none { it.adopted })

        val comparison = assertNotNull(report.comparison)
        assertEquals("2026-09-13", comparison.previousWeekStart)
        assertEquals(10, comparison.total.previous)
        assertEquals(5 + 1, comparison.total.current)
        assertEquals(Trend.DECREASED, comparison.total.trend)
        assertTrue(report.impacts.isEmpty())
        assertEquals(listOf("RES_SACHETS"), f.engine.calledFor())
    }

    @Test
    fun `no report for an open week, a week without entries, or a non-Sunday`() = runBlocking {
        val f = ReportFixtures()
        val user = f.newUser()
        f.log(user, W3, "RES_SACHETS") // current week, still open
        assertNull(f.service.ensureReport(user, W3))
        assertNull(f.service.ensureReport(user, W2))
        assertNull(f.service.ensureReport(user, W2.plusDays(1)))
        assertNull(f.service.getReport(user, W2))
        assertTrue(f.service.listReports(user).isEmpty())
    }

    @Test
    fun `a week with only special waste gets a report without hotspots`() = runBlocking {
        val f = ReportFixtures()
        val user = f.newUser()
        f.log(user, W2, "SPC_BATTERIES", 2)
        val report = assertNotNull(f.service.getReport(user, W2))
        assertTrue(report.hotspots.isEmpty())
        assertEquals(2, report.stats.special.quantity)
        assertNull(report.comparison)
        assertTrue(f.engine.calls.isEmpty())
    }

    @Test
    fun `generation is idempotent, also when called concurrently`() = runBlocking {
        val f = ReportFixtures()
        val user = f.newUser()
        f.recommendable("RES_SACHETS")
        f.log(user, W2, "RES_SACHETS", 3)

        val ids = (1..6).map { async { f.service.ensureReport(user, W2) } }.awaitAll()
        assertEquals(1, ids.toSet().size)
        assertEquals(ids.first(), f.service.ensureReport(user, W2))
        val rows = f.db.tx {
            WeeklyReports.selectAll().where { (WeeklyReports.userId eq user) and (WeeklyReports.weekStart eq W2) }.count()
        }
        assertEquals(1, rows)
        assertEquals(2, f.service.getReport(user, W2)!!.hotspots.single().recommendations.size)
    }

    @Test
    fun `same entries produce byte-identical stats, hotspots, and comparison`() = runBlocking {
        val f = ReportFixtures()
        val entries = listOf(
            W1 to ("RES_PLASTIC_BAGS" to 7), W1 to ("REC_PET_BOTTLES" to 2),
            W2 to ("RES_SACHETS" to 3), W2 to ("RES_PLASTIC_BAGS" to 4), W2 to ("BIO_FOOD_LEFTOVERS" to 2),
            W2 to ("RES_SACHETS" to 1), W2 to ("REC_GLASS" to 4), W2 to ("SPC_BULBS" to 1), W2 to ("RES_TISSUE" to 1),
        )
        val alice = f.newUser()
        val bob = f.newUser()
        for ((week, e) in entries) f.log(alice, week, e.first, e.second)
        for ((week, e) in entries.reversed()) f.log(bob, week, e.first, e.second)

        fun ReportResponse.analyticsJson() = Json.encodeToString(
            ReportResponse.serializer(),
            copy(hotspots = hotspots.map { it.copy(recommendations = emptyList()) }),
        )
        val a = f.service.getReport(alice, W2)!!.analyticsJson()
        assertEquals(a, f.service.getReport(bob, W2)!!.analyticsJson())

        // Regenerating from scratch gives the same bytes again.
        f.db.tx { WeeklyReports.deleteWhere { WeeklyReports.userId eq alice } }
        assertEquals(a, f.service.getReport(alice, W2)!!.analyticsJson())
    }

    @Test
    fun `impact of last week's adoptions is measured and passed to the engine`() = runBlocking {
        val f = ReportFixtures()
        val user = f.newUser()
        val (bagsA, bagsB) = f.recommendable("RES_PLASTIC_BAGS")
        f.log(user, W1, "RES_PLASTIC_BAGS", 15)
        val w1Report = assertNotNull(f.service.ensureReport(user, W1))
        f.adopt(user, w1Report, bagsA, "RES_PLASTIC_BAGS", W1, 15)
        f.adopt(user, w1Report, bagsB, "RES_PLASTIC_BAGS", W1, 15)
        f.log(user, W2, "RES_PLASTIC_BAGS", 9)

        val report = assertNotNull(f.service.getReport(user, W2))
        assertEquals(
            setOf(bagsA.toString(), bagsB.toString()),
            report.impacts.map { it.interventionId }.toSet(),
        )
        for (impact in report.impacts) {
            assertEquals("RES_PLASTIC_BAGS", impact.targetSubcategory)
            assertEquals("2026-09-13", impact.baselineWeekStart)
            assertEquals(15, impact.baselineQuantity)
            assertEquals(9, impact.followupQuantity)
            assertEquals(Trend.DECREASED, impact.result)
        }
        assertEquals(
            setOf(PreviousAdoption(bagsA, "RES_PLASTIC_BAGS", "DECREASED"), PreviousAdoption(bagsB, "RES_PLASTIC_BAGS", "DECREASED")),
            f.engine.previousAdoptionsSeen().toSet(),
        )

        // The W1 report now shows the adoptions; it is no longer the latest.
        val w1 = f.service.getReport(user, W1)!!
        assertFalse(w1.isLatest)
        assertTrue(w1.hotspots.single().recommendations.all { it.adopted })
    }

    @Test
    fun `late sync regenerates the week and the next one, keeping adoptions and existing recommendations`() = runBlocking {
        val f = ReportFixtures(now = NOW_IN_W4) // W2 and W3 are both closed
        val user = f.newUser()
        val sachetPicks = f.recommendable("RES_SACHETS")
        val bagPicks = f.recommendable("RES_PLASTIC_BAGS")
        val peelPicks = f.recommendable("BIO_PEELS_SCRAPS", count = 1)

        // W2: SACHETS f1 q5 and BAGS f1 q1 are both "most frequent".
        f.log(user, W2, "RES_SACHETS", 5)
        f.log(user, W2, "RES_PLASTIC_BAGS", 1)
        val w2Id = assertNotNull(f.service.ensureReport(user, W2))
        assertEquals(listOf("RES_SACHETS", "RES_PLASTIC_BAGS"), f.service.getReport(user, W2)!!.hotspots.map { it.subcategory })
        f.adopt(user, w2Id, sachetPicks[0], "RES_SACHETS", W2, 5)
        f.adopt(user, w2Id, bagPicks[0], "RES_PLASTIC_BAGS", W2, 1)

        f.log(user, W3, "RES_SACHETS", 2)
        f.service.ensureReport(user, W3)
        assertEquals(6, f.service.getReport(user, W3)!!.comparison!!.total.previous)
        val sachetRecIds = recommendationRowIds(f, sachetPicks) // W2's and W3's SACHETS hotspots
        f.engine.calls.clear()

        // Late offline entries land in W2: PEELS becomes the top hotspot, BAGS drops out.
        f.log(user, W2, "BIO_PEELS_SCRAPS", 20)
        f.log(user, W2, "BIO_PEELS_SCRAPS", 1)
        f.service.onLateEntry(user, W2)

        val w2 = f.service.getReport(user, W2)!!
        assertEquals(2, w2.revision)
        assertEquals(listOf("BIO_PEELS_SCRAPS", "RES_SACHETS"), w2.hotspots.map { it.subcategory })
        assertEquals(listOf(MOST_FREQUENT, HIGHEST_QUANTITY), w2.hotspots[0].criteria)
        assertEquals(listOf(AVOIDABLE), w2.hotspots[1].criteria)
        assertEquals(peelPicks.map { it.toString() }, w2.hotspots[0].recommendations.map { it.interventionId })
        assertEquals(sachetRecIds, recommendationRowIds(f, sachetPicks), "existing recommendations are kept, not recreated")
        assertEquals(listOf(true, false), w2.hotspots[1].recommendations.map { it.adopted })
        assertEquals(listOf("BIO_PEELS_SCRAPS"), f.engine.calledFor(), "only new hotspots get new recommendations")

        val adoptions = f.db.tx { AdoptedInterventions.selectAll().where { AdoptedInterventions.reportId eq w2Id }.count() }
        assertEquals(2, adoptions, "adoptions are never removed by regeneration")
        assertNotNull(f.db.tx { WeeklyReports.selectAll().where { WeeklyReports.id eq w2Id }.single()[WeeklyReports.regeneratedAt] })

        val w3 = f.service.getReport(user, W3)!!
        assertEquals(2, w3.revision)
        assertEquals(27, w3.comparison!!.total.previous)
        assertEquals(
            listOf("RES_PLASTIC_BAGS" to Trend.DECREASED, "RES_SACHETS" to Trend.DECREASED),
            w3.impacts.map { it.targetSubcategory to it.result },
        )
    }

    @Test
    fun `late sync into a week without a report creates it`() = runBlocking {
        val f = ReportFixtures(now = NOW_IN_W4)
        val user = f.newUser()
        f.log(user, W3, "RES_SACHETS", 2)
        f.service.ensureReport(user, W3)
        assertNull(f.service.getReport(user, W3)!!.comparison)

        f.log(user, W2, "RES_SACHETS", 4)
        f.service.onLateEntry(user, W2)
        assertEquals(1, f.service.getReport(user, W2)!!.revision)
        assertEquals(4, f.service.getReport(user, W3)!!.comparison!!.total.previous)
    }

    @Test
    fun `list runs catch-up and returns reports newest first with counts`() = runBlocking {
        val f = ReportFixtures(now = NOW_IN_W4)
        val user = f.newUser()
        val picks = f.recommendable("RES_SACHETS")
        f.log(user, W1, "RES_SACHETS", 2)
        f.log(user, W3, "RES_SACHETS", 3)
        f.log(user, W3, "SPC_BATTERIES", 1)
        f.log(user, W4, "RES_SACHETS", 1) // open week: no report

        val list = f.service.listReports(user)
        assertEquals(listOf("2026-09-27", "2026-09-13"), list.map { it.weekStart })
        assertEquals(ReportSummary("2026-09-27", "2026-10-03", 3, 1, 0, true), list[0])
        assertFalse(list[1].isLatest)

        val w3Id = f.store.findReportId(user, W3)!!
        f.adopt(user, w3Id, picks[0], "RES_SACHETS", W3, 3)
        assertEquals(1, f.service.listReports(user)[0].adoptedCount)
    }

    @Test
    fun `reports are private to their owner`() = runBlocking {
        val f = ReportFixtures()
        val alice = f.newUser()
        val bob = f.newUser()
        f.log(alice, W2, "RES_SACHETS", 3)
        assertNotNull(f.service.getReport(alice, W2))
        assertNull(f.service.getReport(bob, W2))
        assertTrue(f.service.listReports(bob).isEmpty())
    }

    @Test
    fun `weekly job generates missing reports once`() = runBlocking {
        // Weeks no other test uses, because the job looks at every user in the shared database.
        val closed = LocalDate.parse("2026-08-02")
        val open = LocalDate.parse("2026-08-09")
        val f = ReportFixtures(now = Instant.parse("2026-08-12T04:00:00Z"))
        val users = List(3) { f.newUser() }
        users.forEach { f.log(it, closed, "REC_PET_BOTTLES", 2) }
        f.log(users[0], open, "REC_PET_BOTTLES", 1)
        f.service.ensureReport(users[0], closed)

        assertEquals(2, f.service.generateMissing(null)) // default: the week that just closed
        assertEquals(0, f.service.generateMissing(closed))
        users.forEach { assertNotNull(f.store.findReportId(it, closed)) }
        assertEquals(0, f.service.generateMissing(open), "open weeks are never generated")
        assertNull(f.store.findReportId(users[0], open))
    }

    @Test
    fun `an engine failure still stores the report, without recommendations`() = runBlocking {
        val f = ReportFixtures()
        f.engine.failWith = IllegalStateException("boom")
        val user = f.newUser()
        f.log(user, W2, "RES_SACHETS", 3)
        val report = assertNotNull(f.service.getReport(user, W2))
        assertTrue(report.hotspots.single().recommendations.isEmpty())
    }

    private suspend fun recommendationRowIds(f: ReportFixtures, interventions: List<UUID>): Set<UUID> = f.db.tx {
        ReportRecommendations.selectAll()
            .where { ReportRecommendations.interventionId eq interventions[0] }
            .map { it[ReportRecommendations.id] }
            .toSet()
    }
}
