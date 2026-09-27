package app.menosan.analytics

import app.menosan.analytics.HotspotCriterion.AVOIDABLE
import app.menosan.analytics.HotspotCriterion.HIGHEST_QUANTITY
import app.menosan.analytics.HotspotCriterion.MOST_FREQUENT
import app.menosan.analytics.QuantityUnit.GRAMS
import app.menosan.analytics.QuantityUnit.PIECES
import app.menosan.reports.analyticsTaxonomy
import app.menosan.taxonomy.Taxonomy
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Hand-computed expectations for plan §5.1–§5.4 (ALGORITHM_VERSION 2: food subcategories in grams, the rest in pieces).
 * The shared vectors are checked in [AnalyticsVectorsTest].
 */
class AnalyticsTest {
    private val taxonomy = Taxonomy.loadDefault().analyticsTaxonomy()

    private fun e(code: String, quantity: Int = 1) = EntryInput(code, quantity)
    private fun stats(vararg entries: EntryInput) = aggregate(entries.toList(), taxonomy)
    private fun hotspots(vararg entries: EntryInput) = findHotspots(stats(*entries), taxonomy)

    // ---- taxonomy units ----

    @Test
    fun `food subcategories are in grams and everything else in pieces`() {
        val grams = taxonomy.values.filter { it.unit == GRAMS }.map { it.code }.sorted()
        assertEquals(listOf("BIO_FOOD_LEFTOVERS", "BIO_PEELS_SCRAPS", "BIO_SPOILED_FOOD"), grams)
    }

    // ---- §5.1 ----

    @Test
    fun `aggregate sums per subcategory and category, per unit, and keeps special apart`() {
        val s = stats(e("RES_SACHETS", 3), e("RES_SACHETS", 2), e("BIO_FOOD_LEFTOVERS", 250), e("BIO_OTHER", 1), e("SPC_BATTERIES", 4))
        assertEquals(Totals(frequency = 4, pieces = 6, grams = 250), s.analyzedTotals)
        assertEquals(Totals(1, 4, 0), s.special)
        assertEquals(
            listOf(
                CategoryStats("BIODEGRADABLE", frequency = 2, pieces = 1, grams = 250, sharePct = 50.0),
                CategoryStats("RECYCLABLE", 0, 0, 0, 0.0),
                CategoryStats("RESIDUAL", 2, 5, 0, 50.0),
            ),
            s.categories,
        )
        assertEquals(
            listOf(
                SubcategoryStats("RES_SACHETS", "RESIDUAL", PIECES, 2, 5),
                SubcategoryStats("BIO_FOOD_LEFTOVERS", "BIODEGRADABLE", GRAMS, 1, 250),
                SubcategoryStats("BIO_OTHER", "BIODEGRADABLE", PIECES, 1, 1),
            ),
            s.subcategories,
        )
    }

    @Test
    fun `category share is by entries and rounds half away from zero to one decimal`() {
        val s = stats(e("BIO_FOOD_LEFTOVERS", 900), *Array(15) { e("RES_SACHETS") }) // 1 of 16 entries = 6.25%
        assertEquals(listOf(6.3, 0.0, 93.8), s.categories.map { it.sharePct })
    }

    @Test
    fun `subcategories are ordered by frequency, then code`() {
        val s = stats(e("REC_GLASS", 2), e("BIO_OTHER", 1), e("BIO_OTHER", 1), e("RES_TISSUE", 2), e("REC_PET_BOTTLES", 5))
        assertEquals(listOf("BIO_OTHER", "REC_GLASS", "REC_PET_BOTTLES", "RES_TISSUE"), s.subcategories.map { it.code })
    }

    @Test
    fun `only special entries give zero analyzed totals`() {
        val s = stats(e("SPC_BATTERIES", 2), e("SPC_MEDICAL"))
        assertEquals(Totals(0, 0, 0), s.analyzedTotals)
        assertEquals(Totals(2, 3, 0), s.special)
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
        val entries = listOf(e("RES_SACHETS", 3), e("REC_PET_BOTTLES", 3), e("BIO_PEELS_SCRAPS", 200), e("RES_SACHETS"), e("SPC_BULBS"))
        val a = aggregate(entries, taxonomy)
        val b = aggregate(entries.reversed(), taxonomy)
        assertEquals(a, b)
        assertEquals(findHotspots(a, taxonomy), findHotspots(b, taxonomy))
    }

    // ---- §5.2 ----

    @Test
    fun `a single entry is every criterion it can be`() {
        assertEquals(
            listOf(Hotspot(1, "RES_SACHETS", listOf(MOST_FREQUENT, HIGHEST_QUANTITY, AVOIDABLE), 1, 5, PIECES, 1.0)),
            hotspots(e("RES_SACHETS", 5)),
        )
        assertEquals(
            listOf(Hotspot(1, "BIO_PEELS_SCRAPS", listOf(MOST_FREQUENT, HIGHEST_QUANTITY), 1, 300, GRAMS, 1.0)),
            hotspots(e("BIO_PEELS_SCRAPS", 300)),
        )
    }

    @Test
    fun `no analyzed entries means no hotspots`() {
        assertTrue(hotspots().isEmpty())
        assertTrue(hotspots(e("SPC_ELECTRONICS")).isEmpty())
    }

    @Test
    fun `ties on frequency and quantity are all included and ordered by code`() {
        val result = hotspots(e("RES_PLASTIC_BAGS", 2), e("BIO_OTHER", 2), e("REC_METAL_CANS", 1))
        assertEquals(
            listOf(
                Hotspot(1, "BIO_OTHER", listOf(MOST_FREQUENT, HIGHEST_QUANTITY), 1, 2, PIECES, 1.0),
                Hotspot(2, "RES_PLASTIC_BAGS", listOf(MOST_FREQUENT, HIGHEST_QUANTITY, AVOIDABLE), 1, 2, PIECES, 1.0),
                Hotspot(3, "REC_METAL_CANS", listOf(MOST_FREQUENT), 1, 1, PIECES, 0.75),
            ),
            result,
        )
    }

    @Test
    fun `hotspots are capped at three`() {
        val result = hotspots(e("RES_SACHETS"), e("REC_PET_BOTTLES"), e("BIO_OTHER"), e("REC_METAL_CANS"))
        assertEquals(listOf("BIO_OTHER", "REC_METAL_CANS", "REC_PET_BOTTLES"), result.map { it.subcategory })
        assertEquals(listOf(1, 2, 3), result.map { it.rank })
        assertEquals(listOf(MOST_FREQUENT, HIGHEST_QUANTITY, AVOIDABLE), result[2].criteria)
    }

    @Test
    fun `the top avoidable subcategory is added even without a max`() {
        val result = hotspots(
            e("BIO_OTHER"), e("BIO_OTHER"), e("BIO_OTHER"), // f3 q3
            e("REC_METAL_CANS", 10), // f1 q10
            e("RES_SACHETS"), e("RES_SACHETS"), // f2 q2
            e("BIO_YARD_WASTE"), // f1 q1
        )
        assertEquals(
            listOf(
                Hotspot(1, "REC_METAL_CANS", listOf(HIGHEST_QUANTITY), 1, 10, PIECES, 0.6667),
                Hotspot(2, "BIO_OTHER", listOf(MOST_FREQUENT), 3, 3, PIECES, 0.65),
                Hotspot(3, "RES_SACHETS", listOf(AVOIDABLE), 2, 2, PIECES, 0.4333),
            ),
            result,
        )
    }

    @Test
    fun `no extra hotspot when the top avoidable is already a hotspot`() {
        val result = hotspots(
            e("RES_SACHETS", 2), e("RES_SACHETS", 2), e("RES_SACHETS", 2), // f3 q6
            e("BIO_OTHER", 10), // f1 q10
            e("REC_PET_BOTTLES", 1), // f1 q1, avoidable but lower score
        )
        assertEquals(
            listOf(
                Hotspot(1, "RES_SACHETS", listOf(MOST_FREQUENT, AVOIDABLE), 3, 6, PIECES, 0.8),
                Hotspot(2, "BIO_OTHER", listOf(HIGHEST_QUANTITY), 1, 10, PIECES, 0.6667),
            ),
            result,
        )
    }

    @Test
    fun `score rounds half up at the fourth decimal`() {
        // maxF 2, maxQ 16: RES_SACHETS score = (1·16 + 1·2) / 64 = 0.28125
        val result = hotspots(e("BIO_OTHER", 8), e("BIO_OTHER", 8), e("RES_SACHETS", 1))
        assertEquals(listOf(1.0, 0.2813), result.map { it.score })
        assertEquals(listOf(AVOIDABLE), result[1].criteria)
    }

    @Test
    fun `quantity is scored and maxed per unit`() {
        val result = hotspots(
            e("BIO_FOOD_LEFTOVERS", 400), e("BIO_FOOD_LEFTOVERS", 200), // f2 600 g: the grams maximum
            e("RES_SACHETS"), e("RES_SACHETS"), e("RES_SACHETS"), // f3 3 pcs
            e("REC_PET_BOTTLES", 5), // f1 5 pcs: the pieces maximum
        )
        assertEquals(
            listOf(
                Hotspot(1, "BIO_FOOD_LEFTOVERS", listOf(HIGHEST_QUANTITY, AVOIDABLE), 2, 600, GRAMS, 0.8333), // (2·600 + 600·3) / 3600
                Hotspot(2, "RES_SACHETS", listOf(MOST_FREQUENT, AVOIDABLE), 3, 3, PIECES, 0.8), // (3·5 + 3·3) / 30
                Hotspot(3, "REC_PET_BOTTLES", listOf(HIGHEST_QUANTITY, AVOIDABLE), 1, 5, PIECES, 0.6667), // (1·5 + 5·3) / 30
            ),
            result,
        )
    }

    @Test
    fun `grams never outweigh pieces just because the numbers are bigger`() {
        // 900 g of peels in one entry against 4 sachets in 4 entries: the sachets are the stronger hotspot.
        val result = hotspots(e("BIO_PEELS_SCRAPS", 900), e("RES_SACHETS"), e("RES_SACHETS"), e("RES_SACHETS"), e("RES_SACHETS"))
        assertEquals(listOf("RES_SACHETS", "BIO_PEELS_SCRAPS"), result.map { it.subcategory })
        assertEquals(listOf(1.0, 0.625), result.map { it.score })
        assertEquals(listOf(HIGHEST_QUANTITY), result[1].criteria)
    }

    // ---- §5.3 ----

    @Test
    fun `comparison is null without analyzed data in the previous week`() {
        val current = stats(e("RES_SACHETS"))
        assertNull(compare(current, null, "2026-09-20", taxonomy))
        assertNull(compare(current, stats(), "2026-09-20", taxonomy))
        assertNull(compare(current, stats(e("SPC_BATTERIES", 3)), "2026-09-20", taxonomy))
    }

    @Test
    fun `comparison covers both totals, every category and unit, and subcategories in either week`() {
        val previous = stats(e("RES_SACHETS", 10), e("BIO_FOOD_LEFTOVERS", 500))
        val current = stats(e("RES_SACHETS", 8), e("REC_PET_BOTTLES", 3), e("BIO_OTHER", 2), e("SPC_BATTERIES", 9))
        val c = compare(current, previous, "2026-09-20", taxonomy)!!
        assertEquals("2026-09-20", c.previousWeekStart)
        assertEquals(ComparisonRow(10, 13, 3, 30.0, Trend.INCREASED), c.pieces)
        assertEquals(ComparisonRow(500, 0, -500, -100.0, Trend.DECREASED), c.grams)
        assertEquals(
            listOf(
                CategoryComparison("BIODEGRADABLE", PIECES, 0, 2, 2, null, Trend.INCREASED),
                CategoryComparison("BIODEGRADABLE", GRAMS, 500, 0, -500, -100.0, Trend.DECREASED),
                CategoryComparison("RECYCLABLE", PIECES, 0, 3, 3, null, Trend.INCREASED),
                CategoryComparison("RESIDUAL", PIECES, 10, 8, -2, -20.0, Trend.DECREASED),
            ),
            c.categories,
        )
        assertEquals(
            listOf(
                SubcategoryComparison("BIO_FOOD_LEFTOVERS", "BIODEGRADABLE", GRAMS, 500, 0, -500, -100.0, Trend.DECREASED),
                SubcategoryComparison("BIO_OTHER", "BIODEGRADABLE", PIECES, 0, 2, 2, null, Trend.INCREASED),
                SubcategoryComparison("REC_PET_BOTTLES", "RECYCLABLE", PIECES, 0, 3, 3, null, Trend.INCREASED),
                SubcategoryComparison("RES_SACHETS", "RESIDUAL", PIECES, 10, 8, -2, -20.0, Trend.DECREASED),
            ),
            c.subcategories,
        )
    }

    @Test
    fun `unchanged quantities are SAME and negative halves round away from zero`() {
        val same = compare(stats(e("RES_SACHETS", 4)), stats(e("RES_SACHETS", 2), e("RES_SACHETS", 2)), "2026-09-20", taxonomy)!!
        assertEquals(ComparisonRow(4, 4, 0, 0.0, Trend.SAME), same.pieces)
        assertEquals(ComparisonRow(0, 0, 0, null, Trend.SAME), same.grams)
        val down = compare(stats(e("BIO_SPOILED_FOOD", 150)), stats(e("BIO_SPOILED_FOOD", 160)), "2026-09-20", taxonomy)!!
        assertEquals(-6.3, down.grams.deltaPct) // -6.25
    }

    // ---- §5.4 ----

    @Test
    fun `impact compares the stored baseline with the follow-up week, in the target's unit`() {
        val followup = stats(e("RES_PLASTIC_BAGS", 9), e("RES_SACHETS", 4), e("REC_PET_BOTTLES", 5), e("BIO_FOOD_LEFTOVERS", 500))
        val impacts = measureImpact(
            listOf(
                AdoptionInput("b", "RES_SACHETS", 4),
                AdoptionInput("a", "RES_PLASTIC_BAGS", 15),
                AdoptionInput("c", "REC_PET_BOTTLES", 2),
                AdoptionInput("d", "RES_STYROFOAM", 3),
                AdoptionInput("e", "BIO_FOOD_LEFTOVERS", 800),
            ),
            followup,
            taxonomy,
        )
        assertEquals(
            listOf(
                Impact("e", "BIO_FOOD_LEFTOVERS", GRAMS, 800, 500, Trend.DECREASED),
                Impact("c", "REC_PET_BOTTLES", PIECES, 2, 5, Trend.INCREASED),
                Impact("a", "RES_PLASTIC_BAGS", PIECES, 15, 9, Trend.DECREASED),
                Impact("b", "RES_SACHETS", PIECES, 4, 4, Trend.SAME),
                Impact("d", "RES_STYROFOAM", PIECES, 3, 0, Trend.DECREASED), // nothing logged for the target
            ),
            impacts,
        )
    }

    @Test
    fun `impact is not measured when nothing was logged in the follow-up week`() {
        val adoption = AdoptionInput("a", "RES_SACHETS", 4)
        assertTrue(measureImpact(listOf(adoption), stats(), taxonomy).isEmpty())
        // Only special waste still makes a report, so the target counts as 0.
        assertEquals(
            listOf(Impact("a", "RES_SACHETS", PIECES, 4, 0, Trend.DECREASED)),
            measureImpact(listOf(adoption), stats(e("SPC_BATTERIES")), taxonomy),
        )
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
