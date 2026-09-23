package app.menosan.taxonomy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaxonomyTest {
    private val taxonomy = Taxonomy.loadDefault()

    @Test
    fun `bundled taxonomy has 4 categories and 25 subcategories`() {
        assertEquals(4, taxonomy.categories.size)
        assertEquals(25, taxonomy.subcategories.size)
        assertEquals(listOf(false), taxonomy.categories.filter { it.code == "SPECIAL" }.map { it.analyzed })
    }

    @Test
    fun `special subcategories are never avoidable`() {
        assertTrue(taxonomy.subcategories.filter { it.category == "SPECIAL" }.none { it.avoidable })
    }

    @Test
    fun `lookup derives category from subcategory`() {
        assertEquals(WasteCategory.RESIDUAL, taxonomy.categoryOf("RES_SACHETS"))
        assertNull(taxonomy.categoryOf("NOPE"))
    }

    @Test
    fun `duplicate codes are rejected`() {
        val bad = """
            {"version":1,"categories":[
              {"code":"BIODEGRADABLE","label":"B","analyzed":true},{"code":"RECYCLABLE","label":"R","analyzed":true},
              {"code":"RESIDUAL","label":"R","analyzed":true},{"code":"SPECIAL","label":"S","analyzed":false}],
             "subcategories":[
              {"code":"X","category":"RESIDUAL","label":"x","examples":[],"avoidable":false,"sortOrder":1},
              {"code":"X","category":"RESIDUAL","label":"x","examples":[],"avoidable":false,"sortOrder":2}]}
        """.trimIndent()
        assertFailsWith<IllegalArgumentException> { Taxonomy.parse(bad) }
    }
}
