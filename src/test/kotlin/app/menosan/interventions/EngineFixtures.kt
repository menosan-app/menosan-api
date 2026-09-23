package app.menosan.interventions

import app.menosan.common.GeminiClient
import app.menosan.common.GeminiException
import app.menosan.common.GeminiRequest
import java.util.UUID

/** Deterministic ids so tests read clearly: `id(1)` = 00000000-0000-0000-0000-000000000001. */
fun id(n: Int): UUID = UUID.fromString("00000000-0000-0000-0000-%012d".format(n))

fun item(
    n: Int,
    cost: CostLevel = CostLevel.FREE,
    effort: Effort = Effort.LOW,
    type: InterventionType = InterventionType.PREVENT,
    code: String = "RES_SACHETS_ITEM_$n",
    subcategory: String = "RES_SACHETS",
) = Intervention(id(n), code, subcategory, type, "Title $n", "Description $n.", listOf("Step 1.", "Step 2."), cost, effort)

class FakeLibrary(private val items: List<Intervention>) : InterventionRepository {
    override suspend fun activeFor(subcategoryCode: String) =
        items.filter { it.subcategoryCode == subcategoryCode && it.active }
}

/** Answers with [answer] (or throws what it throws) and records every request. */
class FakeGemini(private val answer: suspend (GeminiRequest) -> String) : GeminiClient {
    val requests = mutableListOf<GeminiRequest>()

    override suspend fun generateJson(request: GeminiRequest): String {
        requests += request
        return answer(request)
    }

    companion object {
        fun failing() = FakeGemini { throw GeminiException("boom") }

        fun picks(vararg picks: Triple<UUID, Int, String?>) = FakeGemini {
            picks.joinToString(",", """{"picks":[""", "]}") { (id, rank, note) ->
                val noteJson = note?.let { "\"" + it.replace("\"", "\\\"") + "\"" } ?: "null"
                """{"interventionId":"$id","rank":$rank,"note":$noteJson}"""
            }
        }
    }
}

fun input(
    subcategory: String = "RES_SACHETS",
    previous: List<PreviousAdoption> = emptyList(),
    frequency: Int = 12,
    quantity: Int = 40,
    total: Int = 118,
) = RecommendationInput(
    hotspot = HotspotInput(subcategory, listOf("MOST_FREQUENT", "AVOIDABLE"), frequency, quantity),
    analyzedTotalQuantity = total,
    previousAdoptions = previous,
)
