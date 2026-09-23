package app.menosan.interventions

import app.menosan.db.Interventions
import app.menosan.db.PostgresTestDb
import app.menosan.taxonomy.Taxonomy
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Checks the V3 seed against the content rules in plan §6.1 and §6.4 (NFR12, NFR13). */
class InterventionLibrarySeedTest {
    private val taxonomy = Taxonomy.loadDefault()
    private val library: List<Intervention> = runBlocking {
        PostgresTestDb.db.tx { Interventions.selectAll().map { it.toIntervention() } }
    }

    @Test
    fun `every non-SPECIAL subcategory has at least 3 active items`() {
        val active = library.filter { it.active }.groupBy { it.subcategoryCode }
        for (s in taxonomy.subcategories.filter { it.category != "SPECIAL" }) {
            assertTrue((active[s.code]?.size ?: 0) >= 3, "${s.code} has ${active[s.code]?.size ?: 0} active items")
        }
    }

    @Test
    fun `there are no items for SPECIAL waste`() {
        val special = taxonomy.subcategories.filter { it.category == "SPECIAL" }.map { it.code }.toSet()
        assertEquals(emptyList(), library.filter { it.subcategoryCode in special }.map { it.code })
    }

    @Test
    fun `codes are prefixed with their subcategory`() {
        for (item in library) assertTrue(item.code.startsWith(item.subcategoryCode + "_"), item.code)
    }

    @Test
    fun `titles, descriptions, and how-to steps have the required shape`() {
        val sentenceEnd = Regex("""[.!?](\s|$)""")
        for (item in library) {
            assertTrue(item.title.length in 1..60, "${item.code}: title is ${item.title.length} chars")
            val sentences = sentenceEnd.findAll(item.description).count()
            assertTrue(sentences in 1..3, "${item.code}: description has $sentences sentences")
            assertTrue(item.howTo.size in 2..4, "${item.code}: ${item.howTo.size} how-to steps")
            assertTrue(item.howTo.all { it.isNotBlank() && it.length <= 200 }, item.code)
        }
    }

    @Test
    fun `copy is supportive and self-contained`() {
        val blaming = Regex("""(?i)\b(wasted|wasteful|shame|guilt|lazy|irresponsible)\b""")
        val url = Regex("""(?i)(https?://|www\.)""")
        for (item in library) {
            val text = listOf(item.title, item.description, *item.howTo.toTypedArray()).joinToString(" ")
            assertTrue(!blaming.containsMatchIn(text), "${item.code}: blaming language")
            assertTrue(!url.containsMatchIn(text), "${item.code}: contains a URL")
        }
    }
}
