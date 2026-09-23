package app.menosan.interventions

import java.util.UUID

/** Impact results as stored in `intervention_impacts.result` (§5.4). */
object ImpactResult {
    const val DECREASED = "DECREASED"
    const val SAME = "SAME"
    const val INCREASED = "INCREASED"
}

/** Candidates for one hotspot after the deterministic rules (§6.2 steps 2 and 4). */
data class RankedCandidates(
    /** Adopted last week and the target decreased: shown first with `continued = true`. */
    val pinned: List<Intervention>,
    /** Everything else that may be recommended, best first. Gemini picks only from this list. */
    val ranked: List<Intervention>,
)

/** Pure, deterministic pre-rank. Also the fallback when Gemini fails (§6.2). */
object RuleRanking {
    /** costLevel (FREE, SAVES_MONEY, SMALL_ONE_TIME_COST), then effort, then type, then code. */
    val ORDER: Comparator<Intervention> =
        compareBy<Intervention>({ it.costLevel }, { it.effort }, { it.type }, { it.code })

    fun rank(candidates: List<Intervention>, previousAdoptions: List<PreviousAdoption>): RankedCandidates {
        val previous = previousAdoptions.associateBy { it.interventionId }
        val sorted = candidates.sortedWith(ORDER)

        val (pinned, rest) = sorted.partition { previous[it.id]?.result == ImpactResult.DECREASED }
        // Adopted last week but the target stayed the same or went up: prefer something different (I8).
        val (preferred, didNotHelp) = rest.partition {
            previous[it.id]?.result != ImpactResult.SAME && previous[it.id]?.result != ImpactResult.INCREASED
        }
        val ranked = if (preferred.isEmpty() && pinned.isEmpty()) didNotHelp else preferred
        return RankedCandidates(pinned.take(MAX_PICKS), ranked)
    }

    /** The rules-only recommendation: pinned items first, then the top of [RankedCandidates.ranked]. */
    fun picks(candidates: RankedCandidates): List<RecommendationPick> =
        assemble(candidates, geminiPicks = emptyList())

    /**
     * Pinned items first, then [geminiPicks] in their rank order, then the best remaining rule-ranked items,
     * until [MAX_PICKS]. Ranks are renumbered 1..n.
     */
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
