package app.menosan.interventions

import app.menosan.db.PostgresTestDb
import app.menosan.fixedClock
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/** The helpers BE-3 uses to persist recommendations and render `hotspots[].recommendations` (contract §3). */
class InterventionQueriesTest {
    private val db = PostgresTestDb.db
    private val fixtures = ReportFixtures(db)
    private val latest = LocalDate.parse("2026-09-20")
    private val adoptions = ExposedAdoptionService(db, fixedClock("2026-09-30T04:00:00Z"))

    @Test
    fun `recommendation views are grouped by hotspot, in rank order, with the adopted flag`() = runBlocking {
        val user = fixtures.user()
        val (reportId, hotspots) = fixtures.report(
            user, latest,
            hotspots = mapOf("RES_SACHETS" to 40, "RES_PLASTIC_BAGS" to 15),
            recommendations = mapOf(
                "RES_SACHETS" to listOf("RES_SACHETS_TAKAL_COFFEE_SUGAR", "RES_SACHETS_REFILL_STATION"),
                "RES_PLASTIC_BAGS" to listOf("RES_PLASTIC_BAGS_BRING_BAG"),
            ),
        )
        val refill = fixtures.interventionId("RES_SACHETS_REFILL_STATION")
        adoptions.adopt(user, latest, setOf(refill))

        val views = db.tx { recommendationViews(reportId) }
        val sachets = views.getValue(hotspots.getValue("RES_SACHETS"))
        assertEquals(listOf("RES_SACHETS_TAKAL_COFFEE_SUGAR", "RES_SACHETS_REFILL_STATION"), sachets.map { it.code })
        assertEquals(listOf(false, true), sachets.map { it.adopted })
        assertEquals("Note for RES_SACHETS_TAKAL_COFFEE_SUGAR", sachets[0].note)
        assertEquals(null, sachets[1].note)
        assertEquals("REDUCE", sachets[1].type)
        assertEquals("SAVES_MONEY", sachets[1].costLevel)
        assertEquals(3, sachets[1].howTo.size)
        assertEquals(listOf("RES_PLASTIC_BAGS_BRING_BAG"), views.getValue(hotspots.getValue("RES_PLASTIC_BAGS")).map { it.code })
    }

    @Test
    fun `adoptions for a report carry the baseline used for impact`() = runBlocking {
        val user = fixtures.user()
        val (reportId, _) = fixtures.report(
            user, latest,
            hotspots = mapOf("RES_PLASTIC_BAGS" to 15),
            recommendations = mapOf("RES_PLASTIC_BAGS" to listOf("RES_PLASTIC_BAGS_BRING_BAG")),
        )
        val bag = fixtures.interventionId("RES_PLASTIC_BAGS_BRING_BAG")
        adoptions.adopt(user, latest, setOf(bag))

        val records = db.tx { adoptionsForReport(reportId) }
        assertEquals(1, records.size)
        with(records.single()) {
            assertEquals(bag, interventionId)
            assertEquals("RES_PLASTIC_BAGS", targetSubcategory)
            assertEquals(latest, baselineWeekStart)
            assertEquals(15, baselineQuantity)
            assertEquals("Bring a bayong, eco bag, or old sando bag", interventionTitle)
        }
        assertEquals(setOf(bag), db.tx { adoptedInterventionIds(reportId) })
    }
}
