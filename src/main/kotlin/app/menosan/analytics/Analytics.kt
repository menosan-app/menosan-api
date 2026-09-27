package app.menosan.analytics

import kotlinx.serialization.Serializable
import kotlin.math.abs

/*
 * Weekly analytics (plan §5.1–§5.4, NFR9): pure, deterministic functions with no I/O.
 *
 * This file is ported as-is to menosan-android `core/analytics` for offline reports (plan §5.7).
 * Keep it free of server dependencies: only the Kotlin stdlib and kotlinx.serialization.
 * Any rule change must bump ALGORITHM_VERSION and update docs/analytics-test-vectors.json in both repos.
 *
 * Rounding is done with integer arithmetic (half away from zero), so results are identical on every JVM/ART.
 *
 * Version 2 (2026-09-27): every subcategory has a fixed [QuantityUnit]. Food subcategories are logged in grams,
 * everything else in pieces. Quantities are only ever added or compared within one unit:
 * totals are split into pieces and grams, category shares use entry counts, and the hotspot quantity score
 * is normalized against the largest quantity of the same unit.
 */

const val ALGORITHM_VERSION = 2

const val SPECIAL_CATEGORY = "SPECIAL"

/** Main categories that are analyzed, in display order. SPECIAL is excluded (plan §2.2 I3). */
val ANALYZED_CATEGORIES: List<String> = listOf("BIODEGRADABLE", "RECYCLABLE", "RESIDUAL")

/** The unit a subcategory's quantity is logged in, from `taxonomy.json`. Declaration order is display order. */
@Serializable
enum class QuantityUnit { PIECES, GRAMS }

/** What analytics needs to know about a subcategory, taken from `taxonomy.json`. */
data class SubcategoryInfo(val code: String, val category: String, val avoidable: Boolean, val unit: QuantityUnit)

/** One logged entry, reduced to what analytics uses. [quantity] is in the subcategory's unit. */
data class EntryInput(val subcategory: String, val quantity: Int)

// ---- §5.1 aggregation ------------------------------------------------------------------------

/** Entry count plus the summed quantity of each unit. */
@Serializable
data class Totals(val frequency: Int, val pieces: Int, val grams: Int)

/** [sharePct] is this category's share of the analyzed entries (frequency), since pieces and grams don't add up. */
@Serializable
data class CategoryStats(val category: String, val frequency: Int, val pieces: Int, val grams: Int, val sharePct: Double)

/** [quantity] is in [unit]. */
@Serializable
data class SubcategoryStats(val code: String, val category: String, val unit: QuantityUnit, val frequency: Int, val quantity: Int)

@Serializable
data class WeeklyStats(
    val analyzedTotals: Totals,
    /** Always the three analyzed categories, in [ANALYZED_CATEGORIES] order. */
    val categories: List<CategoryStats>,
    /** Analyzed subcategories with at least one entry: frequency desc, then code asc. */
    val subcategories: List<SubcategoryStats>,
    /** SPECIAL waste: logged, but excluded from everything else. */
    val special: Totals,
)

/** §5.1. Throws [IllegalArgumentException] for a subcategory that isn't in [taxonomy]. */
fun aggregate(entries: List<EntryInput>, taxonomy: Map<String, SubcategoryInfo>): WeeklyStats {
    val special = IntArray(3) // frequency, pieces, grams
    val perSub = HashMap<String, IntArray>() // code -> [frequency, quantity]
    for (e in entries) {
        val info = requireNotNull(taxonomy[e.subcategory]) { "Unknown subcategory ${e.subcategory}" }
        if (info.category == SPECIAL_CATEGORY) {
            special[0] += 1
            special[if (info.unit == QuantityUnit.GRAMS) 2 else 1] += e.quantity
        } else {
            val acc = perSub.getOrPut(e.subcategory) { IntArray(2) }
            acc[0] += 1
            acc[1] += e.quantity
        }
    }
    val subcategories = perSub.map { (code, acc) ->
        val info = taxonomy.getValue(code)
        SubcategoryStats(code, info.category, info.unit, frequency = acc[0], quantity = acc[1])
    }.sortedWith(compareByDescending<SubcategoryStats> { it.frequency }.thenBy { it.code })

    val totalF = subcategories.sumOf { it.frequency }
    val categories = ANALYZED_CATEGORIES.map { cat ->
        val inCat = subcategories.filter { it.category == cat }
        val f = inCat.sumOf { it.frequency }
        CategoryStats(
            category = cat,
            frequency = f,
            pieces = inCat.sumIn(QuantityUnit.PIECES),
            grams = inCat.sumIn(QuantityUnit.GRAMS),
            sharePct = percent1(f.toLong(), totalF.toLong()),
        )
    }
    return WeeklyStats(
        analyzedTotals = Totals(totalF, subcategories.sumIn(QuantityUnit.PIECES), subcategories.sumIn(QuantityUnit.GRAMS)),
        categories = categories,
        subcategories = subcategories,
        special = Totals(special[0], special[1], special[2]),
    )
}

private fun List<SubcategoryStats>.sumIn(unit: QuantityUnit): Int = filter { it.unit == unit }.sumOf { it.quantity }

// ---- §5.2 hotspots ---------------------------------------------------------------------------

@Serializable
enum class HotspotCriterion { MOST_FREQUENT, HIGHEST_QUANTITY, AVOIDABLE }

@Serializable
data class Hotspot(
    val rank: Int,
    val subcategory: String,
    val criteria: List<HotspotCriterion>,
    val frequency: Int,
    /** In [unit]. */
    val quantity: Int,
    val unit: QuantityUnit,
    /** 0.5·f/maxF + 0.5·q/maxQ(unit), rounded to 4 decimals. */
    val score: Double,
)

const val MAX_HOTSPOTS = 3

/**
 * §5.2. Returns at most [MAX_HOTSPOTS] hotspots, ranked 1..n. Empty when nothing analyzed was logged.
 *
 * maxQ is taken per unit, so the largest pieces subcategory and the largest grams subcategory are both
 * HIGHEST_QUANTITY, and each quantity is scored against the maximum of its own unit.
 */
fun findHotspots(stats: WeeklyStats, taxonomy: Map<String, SubcategoryInfo>): List<Hotspot> {
    val candidates = stats.subcategories.filter { it.frequency > 0 }
    if (candidates.isEmpty()) return emptyList()
    val maxF = candidates.maxOf { it.frequency }
    val maxQByUnit = candidates.groupBy { it.unit }.mapValues { (_, list) -> list.maxOf { it.quantity } }
    fun maxQ(s: SubcategoryStats): Int = maxQByUnit.getValue(s.unit)

    // Score in units of 1e-4, computed exactly: (f·maxQ + q·maxF) / (2·maxF·maxQ).
    fun scaledScore(s: SubcategoryStats): Long =
        roundDiv((s.frequency.toLong() * maxQ(s) + s.quantity.toLong() * maxF) * 10_000, 2L * maxF * maxQ(s))

    fun isAvoidable(s: SubcategoryStats) = taxonomy[s.code]?.avoidable == true

    // Normalized quantity q/maxQ(unit), larger first, compared exactly by cross-multiplying.
    val byNormalizedQuantityDesc = Comparator<SubcategoryStats> { a, b ->
        (b.quantity.toLong() * maxQ(a)).compareTo(a.quantity.toLong() * maxQ(b))
    }
    val order = compareByDescending<SubcategoryStats> { scaledScore(it) }
        .then(byNormalizedQuantityDesc)
        .thenByDescending { it.frequency }
        .thenBy { it.code }

    val selected = LinkedHashSet<SubcategoryStats>()
    candidates.filterTo(selected) { it.frequency == maxF } // MOST_FREQUENT
    candidates.filterTo(selected) { it.quantity == maxQ(it) } // HIGHEST_QUANTITY (per unit)
    candidates.filter(::isAvoidable).minWithOrNull(order)?.let { selected += it } // TOP_AVOIDABLE

    return selected.sortedWith(order).take(MAX_HOTSPOTS).mapIndexed { i, s ->
        val criteria = buildList {
            if (s.frequency == maxF) add(HotspotCriterion.MOST_FREQUENT)
            if (s.quantity == maxQ(s)) add(HotspotCriterion.HIGHEST_QUANTITY)
            if (isAvoidable(s)) add(HotspotCriterion.AVOIDABLE)
        }
        Hotspot(i + 1, s.code, criteria, s.frequency, s.quantity, s.unit, score = scaledScore(s) / 10_000.0)
    }
}

// ---- §5.3 week-over-week comparison ----------------------------------------------------------

@Serializable
enum class Trend { DECREASED, SAME, INCREASED }

@Serializable
data class ComparisonRow(val previous: Int, val current: Int, val delta: Int, val deltaPct: Double?, val trend: Trend)

/** One row per category and unit that the category can hold (Biodegradable has a pieces row and a grams row). */
@Serializable
data class CategoryComparison(
    val category: String,
    val unit: QuantityUnit,
    val previous: Int,
    val current: Int,
    val delta: Int,
    val deltaPct: Double?,
    val trend: Trend,
)

@Serializable
data class SubcategoryComparison(
    val code: String,
    val category: String,
    val unit: QuantityUnit,
    val previous: Int,
    val current: Int,
    val delta: Int,
    val deltaPct: Double?,
    val trend: Trend,
)

/** Quantities of week W against W−1, per unit. Subcategories present in either week, ordered by code. */
@Serializable
data class Comparison(
    val previousWeekStart: String,
    /** Analyzed pieces in total. */
    val pieces: ComparisonRow,
    /** Analyzed grams in total. */
    val grams: ComparisonRow,
    /** [ANALYZED_CATEGORIES] order, then [QuantityUnit] order; only the units the taxonomy uses in that category. */
    val categories: List<CategoryComparison>,
    val subcategories: List<SubcategoryComparison>,
)

/**
 * §5.3. [previous] is the aggregation of W−1 ([previousWeekStart], `YYYY-MM-DD`), or null if nothing was logged.
 * Returns null when W−1 has no analyzed entries; only W−1 is ever used as a baseline.
 */
fun compare(
    current: WeeklyStats,
    previous: WeeklyStats?,
    previousWeekStart: String,
    taxonomy: Map<String, SubcategoryInfo>,
): Comparison? {
    if (previous == null || previous.analyzedTotals.frequency == 0) return null
    fun WeeklyStats.amount(category: String, unit: QuantityUnit): Int {
        val stats = categories.firstOrNull { it.category == category } ?: return 0
        return if (unit == QuantityUnit.GRAMS) stats.grams else stats.pieces
    }
    val categories = ANALYZED_CATEGORIES.flatMap { cat ->
        val units = taxonomy.values.filter { it.category == cat }.mapTo(HashSet()) { it.unit }
        QuantityUnit.entries.filter { it in units }.map { unit ->
            val row = comparisonRow(previous.amount(cat, unit), current.amount(cat, unit))
            CategoryComparison(cat, unit, row.previous, row.current, row.delta, row.deltaPct, row.trend)
        }
    }
    val prevByCode = previous.subcategories.associateBy { it.code }
    val currByCode = current.subcategories.associateBy { it.code }
    val subcategories = (prevByCode.keys + currByCode.keys).sorted().map { code ->
        val s = currByCode[code] ?: prevByCode.getValue(code)
        val row = comparisonRow(prevByCode[code]?.quantity ?: 0, currByCode[code]?.quantity ?: 0)
        SubcategoryComparison(code, s.category, s.unit, row.previous, row.current, row.delta, row.deltaPct, row.trend)
    }
    return Comparison(
        previousWeekStart = previousWeekStart,
        pieces = comparisonRow(previous.analyzedTotals.pieces, current.analyzedTotals.pieces),
        grams = comparisonRow(previous.analyzedTotals.grams, current.analyzedTotals.grams),
        categories = categories,
        subcategories = subcategories,
    )
}

private fun comparisonRow(previous: Int, current: Int): ComparisonRow {
    val delta = current - previous
    val deltaPct = if (previous == 0) null else percent1(delta.toLong(), previous.toLong())
    return ComparisonRow(previous, current, delta, deltaPct, trendOf(previous, current))
}

private fun trendOf(before: Int, after: Int): Trend = when {
    after < before -> Trend.DECREASED
    after > before -> Trend.INCREASED
    else -> Trend.SAME
}

// ---- §5.4 intervention impact ----------------------------------------------------------------

/**
 * An intervention adopted on report W−1. [baselineQuantity] is the value stored at adoption time (SFR16.2),
 * in the unit of the target subcategory.
 */
@Serializable
data class AdoptionInput(val interventionId: String, val targetSubcategory: String, val baselineQuantity: Int)

@Serializable
data class Impact(
    val interventionId: String,
    val targetSubcategory: String,
    val unit: QuantityUnit,
    val baselineQuantity: Int,
    val followupQuantity: Int,
    val result: Trend,
)

/**
 * §5.4. Measures W−1 adoptions against week W's [followup] stats, ordered by target subcategory, then intervention id.
 * If nothing at all was logged in W there is no report W, so nothing is measured (empty list).
 */
fun measureImpact(
    adoptions: List<AdoptionInput>,
    followup: WeeklyStats,
    taxonomy: Map<String, SubcategoryInfo>,
): List<Impact> {
    if (followup.analyzedTotals.frequency + followup.special.frequency == 0) return emptyList()
    val qty = followup.subcategories.associate { it.code to it.quantity }
    return adoptions
        .sortedWith(compareBy<AdoptionInput> { it.targetSubcategory }.thenBy { it.interventionId })
        .map { a ->
            val info = requireNotNull(taxonomy[a.targetSubcategory]) { "Unknown subcategory ${a.targetSubcategory}" }
            val after = qty[a.targetSubcategory] ?: 0
            Impact(a.interventionId, a.targetSubcategory, info.unit, a.baselineQuantity, after, trendOf(a.baselineQuantity, after))
        }
}

// ---- rounding --------------------------------------------------------------------------------

/** 100·num/den rounded to 1 decimal (half away from zero). 0.0 when [den] is 0. */
private fun percent1(num: Long, den: Long): Double = if (den == 0L) 0.0 else roundDiv(num * 1000, den) / 10.0

/** num/den rounded to the nearest integer, halves away from zero. [den] > 0. */
private fun roundDiv(num: Long, den: Long): Long {
    val q = (abs(num) * 2 + den) / (2 * den)
    return if (num < 0) -q else q
}
