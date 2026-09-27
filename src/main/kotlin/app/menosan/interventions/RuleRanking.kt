package app.menosan.interventions

import java.util.UUID

object ImpactResult {
    const val DECREASED = "DECREASED"
    const val SAME = "SAME"
    const val INCREASED = "INCREASED"
}

data class RankedCandidates(
    val pinned: List<Intervention>,
    val ranked: List<Intervention>,
)

object RuleRanking {
    val ORDER: Comparator<Intervention> =
        compareBy<Intervention>({ it.costLevel }, { it.effort }, { it.type }, { it.code })

    fun rank(candidates: List<Intervention>, previousAdoptions: List<PreviousAdoption>): RankedCandidates {
        val previous = previousAdoptions.associateBy { it.interventionId }
        val sorted = candidates.sortedWith(ORDER)

        val (pinned, rest) = sorted.partition { previous[it.id]?.result == ImpactResult.DECREASED }
        val (preferred, didNotHelp) = rest.partition {
            previous[it.id]?.result != ImpactResult.SAME && previous[it.id]?.result != ImpactResult.INCREASED
        }
        val ranked = if (preferred.isEmpty() && pinned.isEmpty()) didNotHelp else preferred
        return RankedCandidates(pinned.take(MAX_PICKS), ranked)
    }

    fun picks(candidates: RankedCandidates): List<RecommendationPick> =
        assemble(candidates, geminiPicks = emptyList())

    internal fun assemble(candidates: RankedCandidates, geminiPicks: List<GeminiPick>): List<RecommendationPick> {
        val result = mutableListOf<RecommendationPick>()
        val used = mutableSetOf<UUID>()
        fun add(id: UUID, note: String?, continued: Boolean, source: RecommendationSource) {
            if (result.size < MAX_PICKS && used.add(id)) {
                result += RecommendationPick(id, result.size + 1, note, continued, source)
            }
        }
        candidates.pinned.forEach { add(it.id, null, continued = true, RecommendationSource.RULES) }
        geminiPicks.sortedBy { it.rank }.forEach { add(it.interventionId, it.note, continued = false, RecommendationSource.GEMINI) }
        candidates.ranked.forEach { add(it.id, null, continued = false, RecommendationSource.RULES) }
        return result
    }

    const val MAX_PICKS = 3
}
