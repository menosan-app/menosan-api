package app.menosan.taxonomy

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json

/** Main categories. SPECIAL is logged but excluded from analysis (plan §2.2 I3). */
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
)

/** The waste taxonomy from `taxonomy.json` (plan §3), the single source of truth for codes. */
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

    fun categoryOf(subcategoryCode: String): WasteCategory? =
        byCode[subcategoryCode]?.let { WasteCategory.valueOf(it.category) }

    /** Throws if the file is internally inconsistent. Called once at startup. */
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

        /** Loads the bundled `taxonomy.json` from the classpath. */
        fun loadDefault(): Taxonomy {
            val text = Taxonomy::class.java.getResource("/taxonomy.json")?.readText()
                ?: error("taxonomy.json is missing from the classpath")
            return parse(text)
        }
    }
}
