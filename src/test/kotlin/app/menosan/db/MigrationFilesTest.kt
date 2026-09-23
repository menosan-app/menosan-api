package app.menosan.db

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MigrationFilesTest {
    private val dir = File("src/main/resources/db/migration")

    @Test
    fun `migrations are named V{n}__name and numbered without gaps`() {
        val versions = dir.listFiles()!!.map { f ->
            val match = Regex("""V(\d+)__[a-z0-9_]+\.sql""").matchEntire(f.name)
            assertTrue(match != null, "bad migration file name: ${f.name}")
            match.groupValues[1].toInt()
        }.sorted()
        assertEquals((1..versions.size).toList(), versions)
    }

    @Test
    fun `V1 creates every table mapped in Exposed`() {
        val v1 = File(dir, "V1__init.sql").readText()
        val created = Regex("""CREATE TABLE (\w+)""").findAll(v1).map { it.groupValues[1] }.toSet()
        assertEquals(ALL_TABLES.map { it.tableName }.toSet(), created)
        assertEquals(9, created.size)
    }

    @Test
    fun `Exposed columns exist in V1`() {
        val v1 = File(dir, "V1__init.sql").readText()
        for (table in ALL_TABLES) {
            val body = Regex("""CREATE TABLE ${table.tableName} \((.*?)\n\);""", RegexOption.DOT_MATCHES_ALL)
                .find(v1)!!.groupValues[1]
            for (column in table.columns) {
                assertTrue(Regex("""\n\s+${column.name}\s""").containsMatchIn(body), "${table.tableName}.${column.name}")
            }
        }
    }
}
