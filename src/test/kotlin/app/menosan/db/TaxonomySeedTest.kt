package app.menosan.db

import app.menosan.taxonomy.Taxonomy
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * V2__seed_taxonomy.sql is generated from taxonomy.json (version 1). This test fails if they drift.
 *
 * Regenerate with: REGENERATE_TAXONOMY_SEED=true ./gradlew test --tests '*TaxonomySeedTest*'
 * Only do that before V2 has been applied anywhere. After that, taxonomy changes need a new migration.
 */
class TaxonomySeedTest {
    private val seedFile = File("src/main/resources/db/migration/V2__seed_taxonomy.sql")

    @Test
    fun `V2 seed matches taxonomy json`() {
        val taxonomy = Taxonomy.loadDefault()
        val expected = render(taxonomy)
        if (System.getenv("REGENERATE_TAXONOMY_SEED") == "true") {
            seedFile.writeText(expected)
        }
        if (taxonomy.version != 1) {
            println("taxonomy.json is version ${taxonomy.version}; V2 reflects version 1. Add a new migration for the changes.")
            return
        }
        assertEquals(expected, seedFile.readText().replace("\r\n", "\n"))
    }

    companion object {
        fun render(taxonomy: Taxonomy): String = buildString {
            append("-- V2__seed_taxonomy.sql — GENERATED from src/main/resources/taxonomy.json (version ${taxonomy.version}).\n")
            append("-- Do not edit by hand. See TaxonomySeedTest. Append-only: change the taxonomy in a new migration.\n\n")
            append("INSERT INTO waste_subcategories (code, category, label, examples, avoidable, sort_order) VALUES\n")
            append(
                taxonomy.subcategories.joinToString(",\n") { s ->
                    val examples = if (s.examples.isEmpty()) {
                        "'{}'::text[]"
                    } else {
                        s.examples.joinToString(", ", "ARRAY[", "]") { sql(it) }
                    }
                    "  (${sql(s.code)}, ${sql(s.category)}, ${sql(s.label)}, $examples, ${s.avoidable}, ${s.sortOrder})"
                },
            )
            append(";\n")
        }

        private fun sql(value: String) = "'" + value.replace("'", "''") + "'"
    }
}
