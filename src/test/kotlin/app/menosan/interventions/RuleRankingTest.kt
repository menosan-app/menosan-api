package app.menosan.interventions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuleRankingTest {
    @Test
    fun `orders by cost, then effort, then type, then code`() {
        val items = listOf(
            item(1, cost = CostLevel.SMALL_ONE_TIME_COST, code = "A"),
            item(2, cost = CostLevel.SAVES_MONEY, code = "B"),
            item(3, cost = CostLevel.FREE, effort = Effort.MEDIUM, code = "C"),
            item(4, cost = CostLevel.FREE, type = InterventionType.REUSE, code = "D"),
            item(5, cost = CostLevel.FREE, type = InterventionType.PREVENT, code = "F"),
            item(6, cost = CostLevel.FREE, type = InterventionType.PREVENT, code = "E"),
            item(7, cost = CostLevel.FREE, type = InterventionType.REDUCE, code = "G"),
        )
        val ranked = RuleRanking.rank(items.shuffled(kotlin.random.Random(7)), emptyList())
        assertEquals(listOf("E", "F", "G", "D", "C", "B", "A"), ranked.ranked.map { it.code })
        assertTrue(ranked.pinned.isEmpty())
    }

    @Test
    fun `rules picks take the top 3 with no notes`() {
        val items = (1..5).map { item(it, code = "C$it") }
        val picks = RuleRanking.picks(RuleRanking.rank(items, emptyList()))
        assertEquals(listOf(id(1), id(2), id(3)), picks.map { it.interventionId })
        assertEquals(listOf(1, 2, 3), picks.map { it.rank })
        assertTrue(picks.all { it.note == null && !it.continued && it.source == RecommendationSource.RULES })
    }

    @Test
    fun `previously adopted items that stayed the same or increased are left out`() {
        val items = (1..5).map { item(it, code = "C$it") }
        val previous = listOf(
            PreviousAdoption(id(1), "RES_SACHETS", ImpactResult.SAME),
            PreviousAdoption(id(2), "RES_SACHETS", ImpactResult.INCREASED),
        )
        val picks = RuleRanking.picks(RuleRanking.rank(items, previous))
        assertEquals(listOf(id(3), id(4), id(5)), picks.map { it.interventionId })
    }

    @Test
    fun `items that did not help are still used when no alternative remains`() {
        val items = listOf(item(1, code = "C1"), item(2, code = "C2"))
        val previous = listOf(
            PreviousAdoption(id(1), "RES_SACHETS", ImpactResult.SAME),
            PreviousAdoption(id(2), "RES_SACHETS", ImpactResult.INCREASED),
        )
        val picks = RuleRanking.picks(RuleRanking.rank(items, previous))
        assertEquals(listOf(id(1), id(2)), picks.map { it.interventionId })
    }

    @Test
    fun `not measured adoptions are neutral`() {
        val items = (1..3).map { item(it, code = "C$it") }
        val previous = listOf(PreviousAdoption(id(1), "RES_SACHETS", null))
        val picks = RuleRanking.picks(RuleRanking.rank(items, previous))
        assertEquals(listOf(id(1), id(2), id(3)), picks.map { it.interventionId })
        assertTrue(picks.none { it.continued })
    }

    @Test
    fun `an item that decreased the target is pinned first as continued`() {
        val items = (1..4).map { item(it, code = "C$it") }
        val previous = listOf(PreviousAdoption(id(3), "RES_SACHETS", ImpactResult.DECREASED))
        val picks = RuleRanking.picks(RuleRanking.rank(items, previous))
        assertEquals(listOf(id(3), id(1), id(2)), picks.map { it.interventionId })
        assertEquals(listOf(true, false, false), picks.map { it.continued })
        assertEquals(listOf(1, 2, 3), picks.map { it.rank })
    }
}
