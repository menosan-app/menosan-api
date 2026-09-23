package app.menosan.interventions

import app.menosan.taxonomy.Taxonomy
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class LibraryInterventionEngineTest {
    private val taxonomy = Taxonomy.loadDefault()
    private val items = (1..5).map { item(it, code = "RES_SACHETS_C$it") }
    private val rulesTop3 = listOf(id(1), id(2), id(3))

    private fun engine(gemini: FakeGemini, library: List<Intervention> = items) =
        LibraryInterventionEngine(FakeLibrary(library), gemini, taxonomy, timeout = 200.milliseconds)

    private fun assertRulesFallback(picks: List<RecommendationPick>, expected: List<UUID> = rulesTop3) {
        assertEquals(expected, picks.map { it.interventionId })
        assertEquals((1..expected.size).toList(), picks.map { it.rank })
        assertTrue(picks.all { it.source == RecommendationSource.RULES && it.note == null })
    }

    @Test
    fun `valid Gemini picks are used with their notes and ranks`() = runBlocking {
        val gemini = FakeGemini.picks(
            Triple(id(4), 1, "You logged 12 sachets this week. Try a refill station for shampoo."),
            Triple(id(2), 2, "  "),
            Triple(id(5), 3, null),
        )
        val picks = engine(gemini).recommend(input())
        assertEquals(listOf(id(4), id(2), id(5)), picks.map { it.interventionId })
        assertEquals(listOf(1, 2, 3), picks.map { it.rank })
        assertEquals("You logged 12 sachets this week. Try a refill station for shampoo.", picks[0].note)
        assertEquals(null, picks[1].note) // blank note → null
        assertTrue(picks.all { it.source == RecommendationSource.GEMINI && !it.continued })
    }

    @Test
    fun `Gemini ranks are respected even when returned out of order`() = runBlocking {
        val gemini = FakeGemini.picks(Triple(id(5), 2, null), Triple(id(4), 1, null))
        val picks = engine(gemini).recommend(input())
        // Fewer than 3 picks are padded with the best remaining rule-ranked items.
        assertEquals(listOf(id(4), id(5), id(1)), picks.map { it.interventionId })
        assertEquals(
            listOf(RecommendationSource.GEMINI, RecommendationSource.GEMINI, RecommendationSource.RULES),
            picks.map { it.source },
        )
    }

    @Test
    fun `an id outside the candidates falls back to rules`() = runBlocking {
        val gemini = FakeGemini.picks(Triple(id(1), 1, "ok"), Triple(UUID.randomUUID(), 2, "invented"))
        assertRulesFallback(engine(gemini).recommend(input()))
    }

    @Test
    fun `an intervention from another subcategory falls back to rules`() = runBlocking {
        val other = item(99, code = "RES_PLASTIC_BAGS_X", subcategory = "RES_PLASTIC_BAGS")
        val gemini = FakeGemini.picks(Triple(other.id, 1, null))
        assertRulesFallback(engine(gemini, items + other).recommend(input()))
    }

    @Test
    fun `a note longer than 200 characters falls back to rules`() = runBlocking {
        val gemini = FakeGemini.picks(Triple(id(4), 1, "a".repeat(201)))
        assertRulesFallback(engine(gemini).recommend(input()))
    }

    @Test
    fun `a note of exactly 200 characters is accepted`() = runBlocking {
        val gemini = FakeGemini.picks(Triple(id(4), 1, "a".repeat(200)))
        val picks = engine(gemini).recommend(input())
        assertEquals(RecommendationSource.GEMINI, picks[0].source)
    }

    @Test
    fun `notes with URLs or blaming language fall back to rules`() = runBlocking {
        for (note in listOf("See https://example.com", "Visit www.refill.ph", "Order at shop.com today", "You wasted 12 sachets.")) {
            val gemini = FakeGemini.picks(Triple(id(4), 1, note))
            assertRulesFallback(engine(gemini).recommend(input()))
        }
    }

    @Test
    fun `wrong pick counts, duplicate ranks, duplicate ids, and bad ranks fall back to rules`() = runBlocking {
        val bad = listOf(
            FakeGemini { """{"picks":[]}""" },
            FakeGemini.picks(Triple(id(1), 1, null), Triple(id(2), 2, null), Triple(id(3), 3, null), Triple(id(4), 4, null)),
            FakeGemini.picks(Triple(id(1), 1, null), Triple(id(2), 1, null)),
            FakeGemini.picks(Triple(id(1), 1, null), Triple(id(1), 2, null)),
            FakeGemini.picks(Triple(id(1), 0, null)),
            FakeGemini { """{"picks":[{"interventionId":"not-a-uuid","rank":1}]}""" },
            FakeGemini { """{"picks":[{"interventionId":"${id(1)}"}]}""" },
            FakeGemini { """{"choices":[]}""" },
        )
        for (gemini in bad) assertRulesFallback(engine(gemini).recommend(input()))
    }

    @Test
    fun `malformed JSON falls back to rules`() = runBlocking {
        assertRulesFallback(engine(FakeGemini { "Here are my picks: 1, 2, 3" }).recommend(input()))
    }

    @Test
    fun `Gemini errors fall back to rules`() = runBlocking {
        assertRulesFallback(engine(FakeGemini.failing()).recommend(input()))
        assertRulesFallback(engine(FakeGemini { error("unexpected") }).recommend(input()))
    }

    @Test
    fun `a timeout falls back to rules`() = runBlocking {
        val slow = FakeGemini { delay(5_000); """{"picks":[{"interventionId":"${id(4)}","rank":1,"note":null}]}""" }
        val started = System.nanoTime()
        assertRulesFallback(engine(slow).recommend(input()))
        assertTrue((System.nanoTime() - started) / 1_000_000 < 3_000, "engine must not wait for a slow Gemini")
    }

    @Test
    fun `previously adopted items that stayed the same or increased are not offered to Gemini`() = runBlocking {
        val previous = listOf(
            PreviousAdoption(id(1), "RES_SACHETS", ImpactResult.SAME),
            PreviousAdoption(id(2), "RES_SACHETS", ImpactResult.INCREASED),
        )
        val gemini = FakeGemini.picks(Triple(id(5), 1, "Try something new this week."))
        val picks = engine(gemini).recommend(input(previous = previous))
        assertEquals(listOf(id(5), id(3), id(4)), picks.map { it.interventionId })

        val schema = Json.parseToJsonElement(gemini.requests.single().responseSchemaJson).jsonObject
        val enum = schema["properties"]!!.jsonObject["picks"]!!.jsonObject["items"]!!.jsonObject["properties"]!!
            .jsonObject["interventionId"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf(id(3), id(4), id(5)).map { it.toString() }, enum)

        // Gemini choosing a deprioritized item is rejected.
        val sneaky = FakeGemini.picks(Triple(id(1), 1, null))
        assertRulesFallback(engine(sneaky).recommend(input(previous = previous)), listOf(id(3), id(4), id(5)))
    }

    @Test
    fun `an item that decreased the target is pinned as continued, with Gemini filling the other slots`() = runBlocking {
        val previous = listOf(PreviousAdoption(id(4), "RES_SACHETS", ImpactResult.DECREASED))
        val gemini = FakeGemini.picks(Triple(id(5), 1, "A good next step."), Triple(id(2), 2, null))
        val picks = engine(gemini).recommend(input(previous = previous))
        assertEquals(listOf(id(4), id(5), id(2)), picks.map { it.interventionId })
        assertEquals(listOf(true, false, false), picks.map { it.continued })
        assertEquals(RecommendationSource.RULES, picks[0].source)
        assertEquals(listOf(1, 2, 3), picks.map { it.rank })

        val prompt = gemini.requests.single().prompt
        assertTrue("\"maxPicks\":2" in prompt)
        assertFalse(id(4).toString() in gemini.requests.single().responseSchemaJson)
    }

    @Test
    fun `continued pinning also applies on the rules fallback`() = runBlocking {
        val previous = listOf(PreviousAdoption(id(4), "RES_SACHETS", ImpactResult.DECREASED))
        val picks = engine(FakeGemini.failing()).recommend(input(previous = previous))
        assertEquals(listOf(id(4), id(1), id(2)), picks.map { it.interventionId })
        assertEquals(listOf(true, false, false), picks.map { it.continued })
    }

    @Test
    fun `no candidates means no recommendations and no Gemini call`() = runBlocking {
        val gemini = FakeGemini.picks(Triple(id(1), 1, null))
        assertEquals(emptyList(), engine(gemini).recommend(input(subcategory = "SPC_BATTERIES")))
        assertTrue(gemini.requests.isEmpty())
    }

    @Test
    fun `the prompt carries week numbers and library content but no personal data`() = runBlocking {
        val gemini = FakeGemini.picks(Triple(id(1), 1, null))
        engine(gemini).recommend(input(frequency = 12, quantity = 40, total = 118))
        val request = gemini.requests.single()
        assertTrue("\"piecesThisWeek\":40" in request.prompt)
        assertTrue("\"shareOfAnalyzedPiecesPct\":33.9" in request.prompt)
        assertTrue("Sachets & small packets" in request.prompt)
        assertTrue("Title 1" in request.prompt)
        assertTrue(request.systemInstruction!!.contains("tingi"))
        assertFalse("@" in request.prompt)
        assertEquals(null, request.image)
    }
}
