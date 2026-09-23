package app.menosan.common

import app.menosan.interventions.GeminiSelection
import app.menosan.interventions.id
import app.menosan.photo.PhotoPrompt
import app.menosan.taxonomy.Taxonomy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Offline checks that our schema JSON survives the SDK's own parsing, so a live call isn't needed to catch typos. */
class GenAiGeminiClientTest {
    private val taxonomy = Taxonomy.loadDefault()

    private fun request(schema: String) = GeminiRequest("system", "prompt", schema, timeout = 15.seconds)

    @Test
    fun `the photo schema parses into the SDK schema with every constraint`() {
        val config = GenAiGeminiClient.buildConfig(request(PhotoPrompt.responseSchema(taxonomy)))
        assertEquals("application/json", config.responseMimeType().get())
        assertEquals(15_000, config.httpOptions().get().timeout().get())
        assertTrue(config.systemInstruction().isPresent)

        val schema = config.responseSchema().get()
        val properties = schema.properties().get()
        assertEquals(setOf("isWaste", "name", "category", "subcategory", "quantity", "confidence"), properties.keys)
        assertEquals(taxonomy.subcategories.map { it.code }, properties["subcategory"]!!.enum_().get())
        assertEquals(1.0, properties["quantity"]!!.minimum().get())
        assertEquals(999.0, properties["quantity"]!!.maximum().get())
        assertEquals(60L, properties["name"]!!.maxLength().get())
        assertEquals(6, schema.required().get().size)
        assertEquals("isWaste", schema.propertyOrdering().get().first())
    }

    @Test
    fun `Gemini 3 models get a low thinking level, older models are left alone`() {
        assertEquals("low", GenAiGeminiClient.defaultThinkingLevel("gemini-3.6-flash"))
        assertEquals(null, GenAiGeminiClient.defaultThinkingLevel("gemini-2.5-flash"))
        val config = GenAiGeminiClient.buildConfig(request(PhotoPrompt.responseSchema(taxonomy)), "low")
        assertEquals("low", config.thinkingConfig().get().thinkingLevel().get().toString().lowercase())
        assertTrue(GenAiGeminiClient.buildConfig(request(PhotoPrompt.responseSchema(taxonomy))).thinkingConfig().isEmpty)
    }

    @Test
    fun `the intervention selection schema parses too`() {
        val ids = listOf(id(1), id(2))
        val schema = GenAiGeminiClient.buildConfig(request(GeminiSelection.responseSchema(ids))).responseSchema().get()
        val item = schema.properties().get()["picks"]!!.items().get()
        assertEquals(ids.map { it.toString() }, item.properties().get()["interventionId"]!!.enum_().get())
    }
}
