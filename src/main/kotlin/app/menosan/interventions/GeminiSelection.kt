package app.menosan.interventions

import app.menosan.common.GeminiRequest
import app.menosan.taxonomy.Taxonomy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.UUID
import kotlin.time.Duration

/** One validated pick from Gemini. */
internal data class GeminiPick(val interventionId: UUID, val rank: Int, val note: String?)

internal sealed interface SelectionResult {
    data class Valid(val picks: List<GeminiPick>) : SelectionResult
    data class Invalid(val reason: String) : SelectionResult
}

/**
 * Builds the Gemini request for intervention selection and validates the answer strictly (§6.2 step 3).
 * Only anonymized week numbers and library content are sent: never user ids, emails, or entry names.
 */
internal object GeminiSelection {
    const val NOTE_MAX_CHARS = 200

    val systemInstruction: String by lazy {
        GeminiSelection::class.java.getResource("/prompts/intervention_selection.txt")?.readText()
            ?: error("prompts/intervention_selection.txt is missing from the classpath")
    }

    private val json = Json { ignoreUnknownKeys = true }

    // Notes are shown as-is in the app, so reject anything that links out or reads as blame (§6.1 rule 4).
    private val urlPattern = Regex("""(?i)(https?://|www\.|\b[a-z0-9-]+\.(com|ph|net|org|io|gov)\b)""")
    private val blamingWords = Regex("""(?i)\b(wasted|wasteful|wasting|shame\w*|guilt\w*|lazy|irresponsible|bad habits?)\b""")

    fun request(
        input: RecommendationInput,
        taxonomy: Taxonomy,
        allCandidates: List<Intervention>,
        ranked: RankedCandidates,
        slots: Int,
        timeout: Duration,
    ): GeminiRequest = GeminiRequest(
        systemInstruction = systemInstruction,
        prompt = "Input:\n" + promptData(input, taxonomy, allCandidates, ranked, slots),
        responseSchemaJson = responseSchema(ranked.ranked.map { it.id }),
        timeout = timeout,
    )

    fun promptData(
        input: RecommendationInput,
        taxonomy: Taxonomy,
        allCandidates: List<Intervention>,
        ranked: RankedCandidates,
        slots: Int,
    ): String {
        val hotspot = input.hotspot
        val subcategory = taxonomy.subcategory(hotspot.subcategoryCode)
        val titles = allCandidates.associate { it.id to it.title }
        val sharePct = if (input.analyzedTotalQuantity > 0) {
            Math.round(hotspot.quantity * 1000.0 / input.analyzedTotalQuantity) / 10.0
        } else {
            null
        }
        val data = buildJsonObject {
            put("maxPicks", slots)
            putJsonObject("hotspot") {
                put("subcategory", hotspot.subcategoryCode)
                put("label", subcategory?.label)
                putJsonArray("examples") { subcategory?.examples?.forEach { add(it) } }
                putJsonArray("criteria") { hotspot.criteria.forEach { add(it) } }
                put("entriesThisWeek", hotspot.frequency)
                put("piecesThisWeek", hotspot.quantity)
                put("shareOfAnalyzedPiecesPct", sharePct)
            }
            putJsonArray("lastWeekAdoptionsForThisHotspot") {
                input.previousAdoptions.filter { it.interventionId in titles }.forEach {
                    addJsonObject {
                        put("title", titles[it.interventionId])
                        put("result", it.result ?: "NOT_MEASURED")
                    }
                }
            }
            putJsonArray("alreadyShownFirstBecauseItWorked") { ranked.pinned.forEach { add(it.title) } }
            putJsonArray("candidates") {
                ranked.ranked.forEach {
                    addJsonObject {
                        put("interventionId", it.id.toString())
                        put("title", it.title)
                        put("description", it.description)
                        put("type", it.type.name)
                        put("costLevel", it.costLevel.name)
                        put("effort", it.effort.name)
                    }
                }
            }
        }
        return data.toString()
    }

    /** OpenAPI-subset schema for `responseSchema`. `interventionId` is an enum of the candidate ids. */
    fun responseSchema(candidateIds: List<UUID>): String = buildJsonObject {
        put("type", "OBJECT")
        putJsonObject("properties") {
            putJsonObject("picks") {
                put("type", "ARRAY")
                putJsonObject("items") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("interventionId") {
                            put("type", "STRING")
                            put("enum", JsonArray(candidateIds.map { JsonPrimitive(it.toString()) }))
                        }
                        putJsonObject("rank") { put("type", "INTEGER") }
                        putJsonObject("note") { put("type", "STRING") }
                    }
                    put("required", buildJsonArray { add("interventionId"); add("rank"); add("note") })
                }
            }
        }
        put("required", buildJsonArray { add("picks") })
    }.toString()

    @Serializable
    private data class RawResponse(val picks: List<RawPick>? = null)

    @Serializable
    private data class RawPick(val interventionId: String? = null, val rank: Int? = null, val note: String? = null)

    /** Any violation makes the whole answer invalid, and the caller falls back to the rules. */
    fun parse(raw: String, allowedIds: Set<UUID>): SelectionResult {
        val response = try {
            json.decodeFromString(RawResponse.serializer(), raw)
        } catch (e: Exception) {
            return SelectionResult.Invalid("malformed JSON")
        }
        val picks = response.picks ?: return SelectionResult.Invalid("missing picks")
        if (picks.size !in 1..RuleRanking.MAX_PICKS) return SelectionResult.Invalid("expected 1-3 picks, got ${picks.size}")

        val valid = mutableListOf<GeminiPick>()
        for (pick in picks) {
            val id = pick.interventionId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: return SelectionResult.Invalid("unparseable interventionId")
            if (id !in allowedIds) return SelectionResult.Invalid("interventionId is not a candidate")
            val rank = pick.rank ?: return SelectionResult.Invalid("missing rank")
            if (rank < 1) return SelectionResult.Invalid("rank must be positive")
            val note = pick.note?.trim()?.takeIf { it.isNotEmpty() }
            if (note != null) {
                if (note.length > NOTE_MAX_CHARS) return SelectionResult.Invalid("note longer than $NOTE_MAX_CHARS chars")
                if (urlPattern.containsMatchIn(note)) return SelectionResult.Invalid("note contains a URL")
                if (blamingWords.containsMatchIn(note)) return SelectionResult.Invalid("note uses blaming language")
            }
            valid += GeminiPick(id, rank, note)
        }
        if (valid.map { it.interventionId }.toSet().size != valid.size) return SelectionResult.Invalid("duplicate interventionId")
        if (valid.map { it.rank }.toSet().size != valid.size) return SelectionResult.Invalid("duplicate rank")
        return SelectionResult.Valid(valid.sortedBy { it.rank })
    }
}
