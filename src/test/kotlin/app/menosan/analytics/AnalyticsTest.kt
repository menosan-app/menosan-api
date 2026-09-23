package app.menosan.analytics

import app.menosan.analytics.HotspotCriterion.AVOIDABLE
import app.menosan.analytics.HotspotCriterion.HIGHEST_QUANTITY
import app.menosan.analytics.HotspotCriterion.MOST_FREQUENT
import app.menosan.reports.analyticsTaxonomy
import app.menosan.taxonomy.Taxonomy
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Hand-computed expectations for plan §5.1–§5.4. The shared vectors are checked in [AnalyticsVectorsTest]. */
class AnalyticsTest {
    private val taxonomy = Taxonomy.loadDefault().analyticsTaxonomy()

    private fun e(code: String, quantity: Int = 1) = EntryInput(code, quantity)
    private fun stats(vararg entries: EntryInput) = aggregate(entries.toList(), taxonomy)
    private fun hotspots(vararg entries: EntryInput) = findHotspots(stats(*entries), taxonomy)

    // ---- §5.1 ----

    @Test
    fun `aggregate sums per subcategory and category and keeps special apart`() {
        val s = stats(e("RES_SACHETS", 3), e("RES_SACHETS", 2), e("BIO_FOOD_LEFTOVERS", 1), e("SPC_BATTERIES", 4))
        assertEquals(Totals(3, 6), s.analyzedTotals)
        assertEquals(Totals(1, 4), s.special)
        assertEquals(
            listOf(
                CategoryStats("BIODEGRADABLE", 1, 1, 16.7),
                CategoryStats("RECYCLABLE", 0, 0, 0.0),
                CategoryStats("RESIDUAL", 2, 5, 83.3),
            ),
            s.categories,
        )
        assertEquals(
            listOf(SubcategoryStats("RES_SACHETS", "RESIDUAL", 2, 5), SubcategoryStats("BIO_FOOD_LEFTOVERS", "BIODEGRADABLE", 1, 1)),
            s.subcategories,
        )
    }

    @Test
    fun `share rounds half away from zero to one decimal`() {
        val s = stats(e("BIO_PEELS_SCRAPS", 1), e("RES_SACHETS", 15)) // 6.25% and 93.75%
        assertEquals(listOf(6.3, 0.0, 93.8), s.categories.map { it.sharePct })
    }

    @Test
    fun `subcategories are ordered by quantity, frequency, then code`() {
        val s = stats(e("REC_GLASS", 2), e("BIO_OTHER", 1), e("BIO_OTHER", 1), e("RES_TISSUE", 2), e("REC_PET_BOTTLES", 5))
        assertEquals(listOf("REC_PET_BOTTLES", "BIO_OTHER", "REC_GLASS", "RES_TISSUE"), s.subcategories.map { it.code })
    }

    @Test
    fun `only special entries give zero analyzed totals`() {
        val s = stats(e("SPC_BATTERIES", 2), e("SPC_MEDICAL"))
        assertEquals(Totals(0, 0), s.analyzedTotals)
        assertEquals(Totals(2, 3), s.special)
        assertTrue(s.subcategories.isEmpty())
        assertEquals(listOf(0.0, 0.0, 0.0), s.categories.map { it.sharePct })
        assertTrue(findHotspots(s, taxonomy).isEmpty())
    }

    @Test
    fun `unknown subcategory is rejected`() {
        assertFailsWith<IllegalArgumentException> { stats(e("NOPE")) }
    }

    @Test
    fun `input order does not change the result`() {
        val entries = listOf(e("RES_SACHETS", 3), e("REC_PET_BOTTLES", 3), e("BIO_PEELS_SCRAPS", 2), e("RES_SACHETS"), e("SPC_BULBS"))
        val a = aggregate(entries, taxonomy)
        val b = aggregate(entries.reversed(), taxonomy)
        assertEquals(a, b)
        assertEquals(findHotspots(a, taxonomy), findHotspots(b, taxonomy))
    }

    // ---- §5.2 ----

    @Test
    fun `a single entry is every criterion it can be`() {
        assertEquals(listOf(Hotspot(1, "RES_SACHETS", listOf(MOST_FREQUENT, HIGHEST_QUANTITY, AVOIDABLE), 1, 5, 1.0)), hotspots(e("RES_SACHETS", 5)))
        assertEquals(listOf(Hotspot(1, "BIO_PEELS_SCRAPS", listOf(MOST_FREQUENT, HIGHEST_QUANTITY), 1, 3, 1.0)), hotspots(e("BIO_PEELS_SCRAPS", 3)))
    }

    @Test
    fun `no analyzed entries means no hotspots`() {
        assertTrue(hotspots().isEmpty())
        assertTrue(hotspots(e("SPC_ELECTRONICS")).isEmpty())
    }

    @Test
    fun `ties on frequency and quantity are all included and ordered by code`() {
        val result = hotspots(e("RES_PLASTIC_BAGS", 2), e("BIO_PEELS_SCRAPS", 2), e("REC_METAL_CANS", 1))
        assertEquals(
            listOf(
                Hotspot(1, "BIO_PEELS_SCRAPS", listOf(MOST_FREQUENT, HIGHEST_QUANTITY), 1, 2, 1.0),
                Hotspot(2, "RES_PLASTIC_BAGS", listOf(MOST_FREQUENT, HIGHEST_QUANTITY, AVOIDABLE), 1, 2, 1.0),
                Hotspot(3, "REC_METAL_CANS", listOf(MOST_FREQUENT), 1, 1, 0.75),
            ),
            result,
        )
    }

    @Test
    fun `hotspots are capped at three`() {
        val result = hotspots(e("RES_SACHETS"), e("REC_PET_BOTTLES"), e("BIO_PEELS_SCRAPS"), e("REC_METAL_CANS"))
        assertEquals(listOf("BIO_PEELS_SCRAPS", "REC_METAL_CANS", "REC_PET_BOTTLES"), result.map { it.subcategory })
        assertEquals(listOf(1, 2, 3), result.map { it.rank })
        assertEquals(listOf(MOST_FREQUENT, HIGHEST_QUANTITY, AVOIDABLE), result[2].criteria)
    }

    @Test
    fun `the top avoidable subcategory is added even without a max`() {
        val result = hotspots(
            e("BIO_PEELS_SCRAPS"), e("BIO_PEELS_SCRAPS"), e("BIO_PEELS_SCRAPS"), // f3 q3
            e("REC_METAL_CANS", 10), // f1 q10
            e("RES_SACHETS"), e("RES_SACHETS"), // f2 q2
            e("BIO_YARD_WASTE"), // f1 q1
        )
        assertEquals(
            listOf(
                Hotspot(1, "REC_METAL_CANS", listOf(HIGHEST_QUANTITY), 1, 10, 0.6667),
                Hotspot(2, "BIO_PEELS_SCRAPS", listOf(MOST_FREQUENT), 3, 3, 0.65),
                Hotspot(3, "RES_SACHETS", listOf(AVOIDABLE), 2, 2, 0.4333),
            ),
            result,
        )
    }

    @Test
    fun `no extra hotspot when the top avoidable is already a hotspot`() {
        val result = hotspots(
            e("RES_SACHETS", 2), e("RES_SACHETS", 2), e("RES_SACHETS", 2), // f3 q6
            e("BIO_PEELS_SCRAPS", 10), // f1 q10
            e("REC_PET_BOTTLES", 1), // f1 q1, avoidable but lower score
        )
        assertEquals(
            listOf(
                Hotspot(1, "RES_SACHETS", listOf(MOST_FREQUENT, AVOIDABLE), 3, 6, 0.8),
                Hotspot(2, "BIO_PEELS_SCRAPS", listOf(HIGHEST_QUANTITY), 1, 10, 0.6667),
            ),
            result,
        )
    }

    @Test
    fun `score rounds half up at the fourth decimal`() {
        // maxF 2, maxQ 16: RES_SACHETS score = (1·16 + 1·2) / 64 = 0.28125
        val result = hotspots(e("BIO_PEELS_SCRAPS", 8), e("BIO_PEELS_SCRAPS", 8), e("RES_SACHETS", 1))
        assertEquals(listOf(1.0, 0.2813), result.map { it.score })
        assertEquals(listOf(AVOIDABLE), result[1].criteria)
    }

    // ---- §5.3 ----

    @Test
    fun `comparison is null without analyzed data in the previous week`() {
        val current = stats(e("RES_SACHETS"))
        assertNull(compare(current, null, "2026-09-20"))
        assertNull(compare(current, stats(), "2026-09-20"))
        assertNull(compare(current, stats(e("SPC_BATTERIES", 3)), "2026-09-20"))
    }

    @Test
    fun `comparison covers total, every category, and subcategories in either week`() {
        val previous = stats(e("RES_SACHETS", 10), e("BIO_FOOD_LEFTOVERS", 5))
        val current = stats(e("RES_SACHETS", 8), e("REC_PET_BOTTLES", 3), e("SPC_BATTERIES", 9))
        val c = compare(current, previous, "2026-09-20")!!
        assertEquals("2026-09-20", c.previousWeekStart)
        assertEquals(ComparisonRow(15, 11, -4, -26.7, Trend.DECREASED), c.total)
        assertEquals(
            listOf(
                CategoryComparison("BIODEGRADABLE", 5, 0, -5, -100.0, Trend.DECREASED),
                CategoryComparison("RECYCLABLE", 0, 3, 3, null, Trend.INCREASED),
                CategoryComparison("RESIDUAL", 10, 8, -2, -20.0, Trend.DECREASED),
            ),
            c.categories,
        )
        assertEquals(
            listOf(
                SubcategoryComparison("BIO_FOOD_LEFTOVERS", "BIODEGRADABLE", 5, 0, -5, -100.0, Trend.DECREASED),
                SubcategoryComparison("REC_PET_BOTTLES", "RECYCLABLE", 0, 3, 3, null, Trend.INCREASED),
                SubcategoryComparison("RES_SACHETS", "RESIDUAL", 10, 8, -2, -20.0, Trend.DECREASED),
            ),
            c.subcategories,
        )
    }

    @Test
    fun `unchanged quantities are SAME and negative halves round away from zero`() {
        val same = compare(stats(e("RES_SACHETS", 4)), stats(e("RES_SACHETS", 2), e("RES_SACHETS", 2)), "2026-09-20")!!
        assertEquals(ComparisonRow(4, 4, 0, 0.0, Trend.SAME), same.total)
        val down = compare(stats(e("RES_SACHETS", 15)), stats(e("RES_SACHETS", 16)), "2026-09-20")!!
        assertEquals(-6.3, down.total.deltaPct) // -6.25
    }

    // ---- §5.4 ----

    @Test
    fun `impact compares the stored baseline with the follow-up week`() {
        val followup = stats(e("RES_PLASTIC_BAGS", 9), e("RES_SACHETS", 4), e("REC_PET_BOTTLES", 5), e("BIO_PEELS_SCRAPS"))
        val impacts = measureImpact(
            listOf(
                AdoptionInput("b", "RES_SACHETS", 4),
                AdoptionInput("a", "RES_PLASTIC_BAGS", 15),
                AdoptionInput("c", "REC_PET_BOTTLES", 2),
                AdoptionInput("d", "RES_STYROFOAM", 3),
            ),
            followup,
        )
        assertEquals(
            listOf(
                Impact("c", "REC_PET_BOTTLES", 2, 5, Trend.INCREASED),
                Impact("a", "RES_PLASTIC_BAGS", 15, 9, Trend.DECREASED),
                Impact("b", "RES_SACHETS", 4, 4, Trend.SAME),
                Impact("d", "RES_STYROFOAM", 3, 0, Trend.DECREASED), // nothing logged for the target
            ),
            impacts,
        )
    }

    @Test
    fun `impact is not measured when nothing was logged in the follow-up week`() {
        val adoption = AdoptionInput("a", "RES_SACHETS", 4)
        assertTrue(measureImpact(listOf(adoption), stats()).isEmpty())
        // Only special waste still makes a report, so the target counts as 0.
        assertEquals(listOf(Impact("a", "RES_SACHETS", 4, 0, Trend.DECREASED)), measureImpact(listOf(adoption), stats(e("SPC_BATTERIES"))))
    }

    // ---- portability (plan §5.7) ----

    @Test
    fun `analytics sources import nothing but the Kotlin stdlib and kotlinx serialization`() {
        val dir = File("src/main/kotlin/app/menosan/analytics")
        val imports = dir.listFiles()!!.filter { it.extension == "kt" }.flatMap { f ->
            f.readLines().filter { it.startsWith("import ") }.map { it.removePrefix("import ").trim() }
        }
        val bad = imports.filterNot { it.startsWith("kotlin.") || it.startsWith("kotlinx.serialization.") }
        assertTrue(bad.isEmpty(), "Non-portable imports in analytics: $bad")
    }
}
