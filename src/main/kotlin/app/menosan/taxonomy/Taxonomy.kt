package app.menosan.taxonomy

import app.menosan.analytics.QuantityUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json

enum class WasteCategory { BIODEGRADABLE, RECYCLABLE, RESIDUAL, SPECIAL }

@Serializable
data class TaxonomyCategory(val code: String, val label: String, val analyzed: Boolean)

@Serializable
data class TaxonomySubcategory(
    val code: String,
    val category: String,
    val label: String,
    val examples: List<String>,
    val avoidable: Boolean,
    val sortOrder: Int,
    val unit: QuantityUnit,
)

@Serializable
data class Taxonomy(
    val version: Int,
    val timezone: String? = null,
    val categories: List<TaxonomyCategory>,
    val subcategories: List<TaxonomySubcategory>,
) {
    @Transient
    private val byCode: Map<String, TaxonomySubcategory> = subcategories.associateBy { it.code }

    fun subcategory(code: String): TaxonomySubcategory? = byCode[code]

    fun unitOf(subcategoryCode: String): QuantityUnit? = byCode[subcategoryCode]?.unit

    fun categoryOf(subcategoryCode: String): WasteCategory? =
        byCode[subcategoryCode]?.let { WasteCategory.valueOf(it.category) }

    fun validate(): Taxonomy {
        val categoryCodes = categories.map { it.code }
        require(categoryCodes.toSet() == WasteCategory.entries.map { it.name }.toSet()) {
            "taxonomy.json categories must be exactly ${WasteCategory.entries}"
        }
        require(byCode.size == subcategories.size) { "taxonomy.json has duplicate subcategory codes" }
        subcategories.forEach {
            require(it.category in categoryCodes) { "Unknown category ${it.category} for ${it.code}" }
        }
        return this
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = false }

        fun parse(text: String): Taxonomy = json.decodeFromString(serializer(), text).validate()

        fun loadDefault(): Taxonomy {
            val text = Taxonomy::class.java.getResource("/taxonomy.json")?.readText()
                ?: error("taxonomy.json is missing from the classpath")
            return parse(text)
        }
    }
}
