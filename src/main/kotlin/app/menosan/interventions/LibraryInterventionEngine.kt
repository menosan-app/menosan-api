package app.menosan.interventions

import app.menosan.common.GeminiClient
import app.menosan.common.geminiErrorSummary
import app.menosan.taxonomy.Taxonomy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The real [InterventionEngine] (§6.2): curated candidates → rule-based pre-rank → Gemini picks and notes →
 * strict validation → rules fallback on any problem. Items that worked last week are pinned first
 * (`continued = true`). Every pick is a library item (NFR12).
 */
class LibraryInterventionEngine(
    private val library: InterventionRepository,
    private val gemini: GeminiClient,
    private val taxonomy: Taxonomy,
    private val timeout: Duration = GEMINI_TIMEOUT,
) : InterventionEngine {

    override suspend fun recommend(input: RecommendationInput): List<RecommendationPick> {
        val candidates = library.activeFor(input.hotspot.subcategoryCode)
        if (candidates.isEmpty()) return emptyList()

        val ranked = RuleRanking.rank(candidates, input.previousAdoptions)
        val slots = RuleRanking.MAX_PICKS - ranked.pinned.size
        if (slots <= 0 || ranked.ranked.isEmpty()) return RuleRanking.picks(ranked)

        val geminiPicks = selectWithGemini(input, candidates, ranked, slots)
            ?: return RuleRanking.picks(ranked)
        return RuleRanking.assemble(ranked, geminiPicks)
    }

    /** Returns validated picks, or null when the rules must be used instead. Never throws for Gemini problems. */
    private suspend fun selectWithGemini(
        input: RecommendationInput,
        candidates: List<Intervention>,
        ranked: RankedCandidates,
        slots: Int,
    ): List<GeminiPick>? {
        val request = GeminiSelection.request(input, taxonomy, candidates, ranked, slots, timeout)
        val raw = try {
            withTimeoutOrNull(timeout) { gemini.generateJson(request) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Gemini intervention selection failed ({}), using rules", geminiErrorSummary(e))
            return null
        }
        if (raw == null) {
            log.warn("Gemini intervention selection timed out after {}, using rules", timeout)
            return null
        }
        return when (val result = GeminiSelection.parse(raw, ranked.ranked.map { it.id }.toSet())) {
            is SelectionResult.Valid -> result.picks
            is SelectionResult.Invalid -> {
                log.warn("Gemini intervention selection rejected ({}), using rules", result.reason)
                null
            }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(LibraryInterventionEngine::class.java)
        val GEMINI_TIMEOUT: Duration = 8.seconds
    }
}
