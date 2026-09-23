package app.menosan.db

import app.menosan.account.ExposedUserRepository
import app.menosan.taxonomy.Taxonomy
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Runs V1/V2 and the Exposed mappings against a real PostgreSQL 17. */
class DatabaseIntegrationTest {
    private val db = PostgresTestDb.db
    private val users = ExposedUserRepository(db)
    private val now = Instant.parse("2026-09-30T04:00:00Z")

    @Test
    fun `migrations seed exactly the taxonomy`() = runBlocking {
        val taxonomy = Taxonomy.loadDefault()
        val rows = db.tx {
            WasteSubcategories.selectAll().associate {
                it[WasteSubcategories.code] to Triple(
                    it[WasteSubcategories.category],
                    it[WasteSubcategories.examples],
                    it[WasteSubcategories.avoidable],
                )
            }
        }
        assertEquals(taxonomy.subcategories.size, rows.size)
        for (s in taxonomy.subcategories) {
            assertEquals(Triple(s.category, s.examples, s.avoidable), rows[s.code], s.code)
        }
    }

    @Test
    fun `health check succeeds`() = runBlocking {
        assertTrue(JdbcHealthCheck(PostgresTestDb.dataSource).isHealthy())
    }

    @Test
    fun `createIfAbsent is idempotent per firebase uid`() = runBlocking {
        val uid = "uid-" + UUID.randomUUID()
        val (first, created) = users.createIfAbsent(uid, "a@example.com", "A", now)
        val (second, createdAgain) = users.createIfAbsent(uid, "changed@example.com", null, now.plusSeconds(60))
        assertTrue(created)
        assertFalse(createdAgain)
        assertEquals(first.id, second.id)
        assertEquals("a@example.com", second.email)
        assertEquals(now, second.createdAt)
        assertEquals(first.id, users.findByFirebaseUid(uid)?.id)
        assertEquals(null, users.findByFirebaseUid("uid-missing-" + UUID.randomUUID()))
    }

    @Test
    fun `deleting a user cascades to their private rows`() = runBlocking {
        val (user, _) = users.createIfAbsent("uid-" + UUID.randomUUID(), "c@example.com", null, now)
        val entryId = UUID.randomUUID()
        db.tx {
            WasteEntries.insert {
                it[id] = entryId
                it[userId] = user.id
                it[name] = "Coffee 3-in-1 sachet"
                it[category] = "RESIDUAL"
                it[subcategoryCode] = "RES_SACHETS"
                it[quantity] = 3
                it[entrySource] = "MANUAL"
                it[createdAt] = now.atOffset(ZoneOffset.UTC)
                it[weekStart] = LocalDate.parse("2026-09-27")
            }
            Users.deleteWhere { Users.id eq user.id }
        }
        val remaining = db.tx { WasteEntries.selectAll().where { WasteEntries.id eq entryId }.count() }
        assertEquals(0, remaining)
    }
}
