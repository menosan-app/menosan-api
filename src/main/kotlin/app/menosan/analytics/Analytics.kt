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
 */

const val ALGORITHM_VERSION = 1

const val SPECIAL_CATEGORY = "SPECIAL"

/** Main categories that are analyzed, in display order. SPECIAL is excluded (plan §2.2 I3). */
val ANALYZED_CATEGORIES: List<String> = listOf("BIODEGRADABLE", "RECYCLABLE", "RESIDUAL")

/** What analytics needs to know about a subcategory, taken from `taxonomy.json`. */
data class SubcategoryInfo(val code: String, val category: String, val avoidable: Boolean)

/** One logged entry, reduced to what analytics uses. */
data class EntryInput(val subcategory: String, val quantity: Int)

// ---- §5.1 aggregation ------------------------------------------------------------------------

@Serializable
data class Totals(val frequency: Int, val quantity: Int)

@Serializable
data class CategoryStats(val category: String, val frequency: Int, val quantity: Int, val sharePct: Double)

@Serializable
data class SubcategoryStats(val code: String, val category: String, val frequency: Int, val quantity: Int)

@Serializable
data class WeeklyStats(
    val analyzedTotals: Totals,
    /** Always the three analyzed categories, in [ANALYZED_CATEGORIES] order. */
    val categories: List<CategoryStats>,
    /** Analyzed subcategories with at least one entry: quantity desc, frequency desc, code asc. */
    val subcategories: List<SubcategoryStats>,
    /** SPECIAL waste: logged, but excluded from everything else. */
    val special: Totals,
)

/** §5.1. Throws [IllegalArgumentException] for a subcategory that isn't in [taxonomy]. */
fun aggregate(entries: List<EntryInput>, taxonomy: Map<String, SubcategoryInfo>): WeeklyStats {
    var specialF = 0
    var specialQ = 0
    val perSub = HashMap<String, IntArray>() // code -> [frequency, quantity]
    for (e in entries) {
        val info = requireNotNull(taxonomy[e.subcategory]) { "Unknown subcategory ${e.subcategory}" }
        if (info.category == SPECIAL_CATEGORY) {
            specialF += 1
            specialQ += e.quantity
        } else {
            val acc = perSub.getOrPut(e.subcategory) { IntArray(2) }
            acc[0] += 1
            acc[1] += e.quantity
        }
    }
    val subcategories = perSub.map { (code, acc) ->
        SubcategoryStats(code, taxonomy.getValue(code).category, frequency = acc[0], quantity = acc[1])
    }.sortedWith(compareByDescending<SubcategoryStats> { it.quantity }.thenByDescending { it.frequency }.thenBy { it.code })

    val totalF = subcategories.sumOf { it.frequency }
    val totalQ = subcategories.sumOf { it.quantity }
    val categories = ANALYZED_CATEGORIES.map { cat ->
        val inCat = subcategories.filter { it.category == cat }
        val q = inCat.sumOf { it.quantity }
        CategoryStats(cat, inCat.sumOf { it.frequency }, q, sharePct = percent1(q.toLong(), totalQ.toLong()))
    }
    return WeeklyStats(Totals(totalF, totalQ), categories, subcategories, Totals(specialF, specialQ))
}

// ---- §5.2 hotspots ---------------------------------------------------------------------------

@Serializable
enum class HotspotCriterion { MOST_FREQUENT, HIGHEST_QUANTITY, AVOIDABLE }

@Serializable
data class Hotspot(
    val rank: Int,
    val subcategory: String,
    val criteria: List<HotspotCriterion>,
    val frequency: Int,
    val quantity: Int,
    /** 0.5·f/maxF + 0.5·q/maxQ, rounded to 4 decimals. */
    val score: Double,
)

const val MAX_HOTSPOTS = 3

/** §5.2. Returns at most [MAX_HOTSPOTS] hotspots, ranked 1..n. Empty when nothing analyzed was logged. */
fun findHotspots(stats: WeeklyStats, taxonomy: Map<String, SubcategoryInfo>): List<Hotspot> {
    val candidates = stats.subcategories.filter { it.frequency > 0 }
    if (candidates.isEmpty()) return emptyList()
    val maxF = candidates.maxOf { it.frequency }
    val maxQ = candidates.maxOf { it.quantity }

    // Score in units of 1e-4, computed exactly: (f·maxQ + q·maxF) / (2·maxF·maxQ).
    fun scaledScore(s: SubcategoryStats): Long =
        roundDiv((s.frequency.toLong() * maxQ + s.quantity.toLong() * maxF) * 10_000, 2L * maxF * maxQ)

    fun isAvoidable(s: SubcategoryStats) = taxonomy[s.code]?.avoidable == true

    val order = compareByDescending<SubcategoryStats> { scaledScore(it) }
        .thenByDescending { it.quantity }
        .thenByDescending { it.frequency }
        .thenBy { it.code }

    val selected = LinkedHashSet<SubcategoryStats>()
    candidates.filterTo(selected) { it.frequency == maxF } // MOST_FREQUENT
    candidates.filterTo(selected) { it.quantity == maxQ } // HIGHEST_QUANTITY
    candidates.filter(::isAvoidable).minWithOrNull(order)?.let { selected += it } // TOP_AVOIDABLE

    return selected.sortedWith(order).take(MAX_HOTSPOTS).mapIndexed { i, s ->
        val criteria = buildList {
            if (s.frequency == maxF) add(HotspotCriterion.MOST_FREQUENT)
            if (s.quantity == maxQ) add(HotspotCriterion.HIGHEST_QUANTITY)
            if (isAvoidable(s)) add(HotspotCriterion.AVOIDABLE)
        }
        Hotspot(i + 1, s.code, criteria, s.frequency, s.quantity, score = scaledScore(s) / 10_000.0)
    }
}

// ---- §5.3 week-over-week comparison ----------------------------------------------------------

@Serializable
enum class Trend { DECREASED, SAME, INCREASED }

@Serializable
data class ComparisonRow(val previous: Int, val current: Int, val delta: Int, val deltaPct: Double?, val trend: Trend)

@Serializable
data class CategoryComparison(
    val category: String,
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
    val previous: Int,
    val current: Int,
    val delta: Int,
    val deltaPct: Double?,
    val trend: Trend,
)

/** Quantities of week W against W−1. Subcategories present in either week, ordered by code. */
@Serializable
data class Comparison(
    val previousWeekStart: String,
    val total: ComparisonRow,
    val categories: List<CategoryComparison>,
    val subcategories: List<SubcategoryComparison>,
)

/**
 * §5.3. [previous] is the aggregation of W−1 ([previousWeekStart], `YYYY-MM-DD`), or null if nothing was logged.
 * Returns null when W−1 has no analyzed entries; only W−1 is ever used as a baseline.
 */
fun compare(current: WeeklyStats, previous: WeeklyStats?, previousWeekStart: String): Comparison? {
    if (previous == null || previous.analyzedTotals.frequency == 0) return null
    val categories = ANALYZED_CATEGORIES.map { cat ->
        val p = previous.categories.firstOrNull { it.category == cat }?.quantity ?: 0
        val c = current.categories.firstOrNull { it.category == cat }?.quantity ?: 0
        val row = comparisonRow(p, c)
        CategoryComparison(cat, row.previous, row.current, row.delta, row.deltaPct, row.trend)
    }
    val prevByCode = previous.subcategories.associateBy { it.code }
    val currByCode = current.subcategories.associateBy { it.code }
    val subcategories = (prevByCode.keys + currByCode.keys).sorted().map { code ->
        val category = (currByCode[code] ?: prevByCode.getValue(code)).category
        val row = comparisonRow(prevByCode[code]?.quantity ?: 0, currByCode[code]?.quantity ?: 0)
        SubcategoryComparison(code, category, row.previous, row.current, row.delta, row.deltaPct, row.trend)
    }
    return Comparison(
        previousWeekStart = previousWeekStart,
        total = comparisonRow(previous.analyzedTotals.quantity, current.analyzedTotals.quantity),
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

/** An intervention adopted on report W−1. [baselineQuantity] is the value stored at adoption time (SFR16.2). */
@Serializable
data class AdoptionInput(val interventionId: String, val targetSubcategory: String, val baselineQuantity: Int)

@Serializable
data class Impact(
    val interventionId: String,
    val targetSubcategory: String,
    val baselineQuantity: Int,
    val followupQuantity: Int,
    val result: Trend,
)

/**
 * §5.4. Measures W−1 adoptions against week W's [followup] stats, ordered by target subcategory, then intervention id.
 * If nothing at all was logged in W there is no report W, so nothing is measured (empty list).
 */
fun measureImpact(adoptions: List<AdoptionInput>, followup: WeeklyStats): List<Impact> {
    if (followup.analyzedTotals.frequency + followup.special.frequency == 0) return emptyList()
    val qty = followup.subcategories.associate { it.code to it.quantity }
    return adoptions
        .sortedWith(compareBy<AdoptionInput> { it.targetSubcategory }.thenBy { it.interventionId })
        .map { a ->
            val after = qty[a.targetSubcategory] ?: 0
            Impact(a.interventionId, a.targetSubcategory, a.baselineQuantity, after, trendOf(a.baselineQuantity, after))
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
