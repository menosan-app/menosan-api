package app.menosan.analytics

import kotlinx.serialization.Serializable
import kotlin.math.abs

const val ALGORITHM_VERSION = 2

const val SPECIAL_CATEGORY = "SPECIAL"

val ANALYZED_CATEGORIES: List<String> = listOf("BIODEGRADABLE", "RECYCLABLE", "RESIDUAL")

@Serializable
enum class QuantityUnit { PIECES, GRAMS }

data class SubcategoryInfo(val code: String, val category: String, val avoidable: Boolean, val unit: QuantityUnit)

data class EntryInput(val subcategory: String, val quantity: Int)

@Serializable
data class Totals(val frequency: Int, val pieces: Int, val grams: Int)

@Serializable
data class CategoryStats(val category: String, val frequency: Int, val pieces: Int, val grams: Int, val sharePct: Double)

@Serializable
data class SubcategoryStats(val code: String, val category: String, val unit: QuantityUnit, val frequency: Int, val quantity: Int)

@Serializable
data class WeeklyStats(
    val analyzedTotals: Totals,
    val categories: List<CategoryStats>,
    val subcategories: List<SubcategoryStats>,
    val special: Totals,
)

fun aggregate(entries: List<EntryInput>, taxonomy: Map<String, SubcategoryInfo>): WeeklyStats {
    val special = IntArray(3)
    val perSub = HashMap<String, IntArray>()
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

@Serializable
enum class HotspotCriterion { MOST_FREQUENT, HIGHEST_QUANTITY, AVOIDABLE }

@Serializable
data class Hotspot(
    val rank: Int,
    val subcategory: String,
    val criteria: List<HotspotCriterion>,
    val frequency: Int,
    val quantity: Int,
    val unit: QuantityUnit,
    val score: Double,
)

const val MAX_HOTSPOTS = 3

fun findHotspots(stats: WeeklyStats, taxonomy: Map<String, SubcategoryInfo>): List<Hotspot> {
    val candidates = stats.subcategories.filter { it.frequency > 0 }
    if (candidates.isEmpty()) return emptyList()
    val maxF = candidates.maxOf { it.frequency }
    val maxQByUnit = candidates.groupBy { it.unit }.mapValues { (_, list) -> list.maxOf { it.quantity } }
    fun maxQ(s: SubcategoryStats): Int = maxQByUnit.getValue(s.unit)

    fun scaledScore(s: SubcategoryStats): Long =
        roundDiv((s.frequency.toLong() * maxQ(s) + s.quantity.toLong() * maxF) * 10_000, 2L * maxF * maxQ(s))

    fun isAvoidable(s: SubcategoryStats) = taxonomy[s.code]?.avoidable == true

    val byNormalizedQuantityDesc = Comparator<SubcategoryStats> { a, b ->
        (b.quantity.toLong() * maxQ(a)).compareTo(a.quantity.toLong() * maxQ(b))
    }
    val order = compareByDescending<SubcategoryStats> { scaledScore(it) }
        .then(byNormalizedQuantityDesc)
        .thenByDescending { it.frequency }
        .thenBy { it.code }

    val selected = LinkedHashSet<SubcategoryStats>()
    candidates.filterTo(selected) { it.frequency == maxF }
    candidates.filterTo(selected) { it.quantity == maxQ(it) }
    candidates.filter(::isAvoidable).minWithOrNull(order)?.let { selected += it }

    return selected.sortedWith(order).take(MAX_HOTSPOTS).mapIndexed { i, s ->
        val criteria = buildList {
            if (s.frequency == maxF) add(HotspotCriterion.MOST_FREQUENT)
            if (s.quantity == maxQ(s)) add(HotspotCriterion.HIGHEST_QUANTITY)
            if (isAvoidable(s)) add(HotspotCriterion.AVOIDABLE)
        }
        Hotspot(i + 1, s.code, criteria, s.frequency, s.quantity, s.unit, score = scaledScore(s) / 10_000.0)
    }
}

@Serializable
enum class Trend { DECREASED, SAME, INCREASED }

@Serializable
data class ComparisonRow(val previous: Int, val current: Int, val delta: Int, val deltaPct: Double?, val trend: Trend)

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

@Serializable
data class Comparison(
    val previousWeekStart: String,
    val pieces: ComparisonRow,
    val grams: ComparisonRow,
    val categories: List<CategoryComparison>,
    val subcategories: List<SubcategoryComparison>,
)

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

private fun percent1(num: Long, den: Long): Double = if (den == 0L) 0.0 else roundDiv(num * 1000, den) / 10.0

private fun roundDiv(num: Long, den: Long): Long {
    val q = (abs(num) * 2 + den) / (2 * den)
    return if (num < 0) -q else q
}
