package app.menosan.entries

import app.menosan.CURRENT_WEEK
import app.menosan.DbTestEnv
import app.menosan.PREVIOUS_WEEK
import app.menosan.common.OverridableClock
import app.menosan.createAccount
import app.menosan.db.WasteEntries
import app.menosan.dbTest
import app.menosan.entryBody
import app.menosan.errorCode
import app.menosan.fixedClock
import app.menosan.json
import app.menosan.jsonId
import app.menosan.listEntries
import app.menosan.putEntry
import app.menosan.sync
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EntrySyncTest {
    private suspend fun HttpResponse.results(): List<JsonObject> {
        assertEquals(HttpStatusCode.OK, status)
        return json()["results"]!!.jsonArray.map { it.jsonObject }
    }

    private fun JsonObject.status() = this["status"]!!.jsonPrimitive.content
    private fun JsonObject.op() = this["op"]!!.jsonPrimitive.content
    private fun JsonObject.id() = (this["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private suspend fun rowCount(userId: UUID): Long = app.menosan.db.PostgresTestDb.db.tx {
        WasteEntries.selectAll().where { WasteEntries.userId eq userId }.count()
    }

    @Test
    fun `each item gets its own result and one bad item doesn't fail the batch`() = dbTest { env ->
        createAccount(env.aliceToken)
        val ok1 = UUID.randomUUID()
        val ok2 = UUID.randomUUID()
        val badQuantity = UUID.randomUUID()
        val wrongType = UUID.randomUUID()
        val future = UUID.randomUUID()
        val upserts = listOf(
            entryBody(id = ok1),
            entryBody(id = badQuantity, quantity = 1000),
            buildJsonObject { put("id", wrongType.toString()); put("quantity", "lots") },
            entryBody(id = future, createdAt = "2026-10-01T00:00:00Z"),
            buildJsonObject { put("name", "no id") },
            JsonPrimitive("not an object"),
            entryBody(id = ok2, name = "Sando bag", subcategory = "RES_PLASTIC_BAGS"),
        )
        val results = sync(env.aliceToken, upserts, listOf(JsonPrimitive("nope"), JsonNull)).results()

        assertEquals(9, results.size)
        assertEquals(
            listOf("OK", "INVALID", "INVALID", "INVALID_TIMESTAMP", "INVALID", "INVALID", "OK", "INVALID", "INVALID"),
            results.map { it.status() },
        )
        assertEquals(List(7) { "UPSERT" } + List(2) { "DELETE" }, results.map { it.op() })
        assertEquals(
            listOf(ok1.toString(), badQuantity.toString(), wrongType.toString(), future.toString(), null, null, ok2.toString(), "nope", null),
            results.map { it.id() },
        )
        assertEquals("RESIDUAL", results[6]["entry"]!!.jsonObject["category"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, results[1]["entry"])
        assertTrue(results[1]["message"]!!.jsonPrimitive.content.isNotBlank())

        assertEquals(setOf(ok1, ok2).map { it.toString() }.toSet(), listEntries(env.aliceToken).json()["entries"]!!.jsonArray
            .map { it.jsonObject["id"]!!.jsonPrimitive.content }.toSet())
    }

    @Test
    fun `retrying a batch creates no duplicates, also when retries overlap`() = dbTest { env ->
        val aliceId = createAccount(env.aliceToken)
        val ids = List(5) { UUID.randomUUID() }
        val upserts = ids.mapIndexed { i, id -> entryBody(id = id, quantity = i + 1, createdAt = "2026-09-29T0$i:00:00Z") }

        val first = sync(env.aliceToken, upserts).results()
        assertTrue(first.all { it.status() == "OK" })
        val retry = sync(env.aliceToken, upserts).results()
        assertTrue(retry.all { it.status() == "OK" })
        assertEquals(first.map { it["entry"] }, retry.map { it["entry"] })

        val more = List(5) { UUID.randomUUID() }
        val batch = more.map { entryBody(id = it) }
        val parallel = coroutineScope { List(4) { async { sync(env.aliceToken, batch).results() } }.awaitAll() }
        parallel.forEach { results -> assertTrue(results.all { it.status() == "OK" }, results.toString()) }

        assertEquals(10L, rowCount(aliceId))
    }

    @Test
    fun `retrying a create after its week closed still succeeds`() {
        val clock = OverridableClock(fixedClock("2026-10-03T15:00:00Z")) // Sat 23:00 PHT
        val env = DbTestEnv(clock)
        dbTest(env) {
            val aliceId = createAccount(env.aliceToken)
            val id = UUID.randomUUID()
            val item = entryBody(id = id, createdAt = "2026-10-03T14:30:00Z")
            assertEquals("OK", sync(env.aliceToken, listOf(item)).results().single().status())

            clock.setOverride(Instant.parse("2026-10-04T01:00:00Z")) // the app never saw the response; retries on Sunday
            val retry = sync(env.aliceToken, listOf(item)).results().single()
            assertEquals("OK", retry.status())
            assertEquals(CURRENT_WEEK.toString(), retry["entry"]!!.jsonObject["weekStart"]!!.jsonPrimitive.content)
            assertEquals(1L, rowCount(aliceId))
            assertTrue(env.reports.lateEntries.isEmpty(), "a replay isn't a late create")
        }
    }

    @Test
    fun `WEEK_CLOSED results carry the server copy so the app can revert`() = dbTest { env ->
        createAccount(env.aliceToken)
        val id = UUID.randomUUID()
        putEntry(env.aliceToken, id, entryBody(createdAt = "2026-09-22T02:00:00Z", quantity = 2))

        val results = sync(
            env.aliceToken,
            upserts = listOf(entryBody(id = id, createdAt = "2026-09-22T02:00:00Z", quantity = 7)),
            deletes = listOf(jsonId(id)),
        ).results()

        assertEquals(listOf("WEEK_CLOSED", "WEEK_CLOSED"), results.map { it.status() })
        for (r in results) {
            val entry = r["entry"]!!.jsonObject
            assertEquals(2, entry["quantity"]!!.jsonPrimitive.int)
            assertEquals(PREVIOUS_WEEK.toString(), entry["weekStart"]!!.jsonPrimitive.content)
        }
        assertEquals(1, listEntries(env.aliceToken, PREVIOUS_WEEK).json()["entries"]!!.jsonArray.size)
    }

    @Test
    fun `late creates regenerate each affected week once, after the batch`() = dbTest { env ->
        val aliceId = createAccount(env.aliceToken)
        val upserts = listOf(
            entryBody(id = UUID.randomUUID(), createdAt = "2026-09-22T02:00:00Z"),
            entryBody(id = UUID.randomUUID(), createdAt = "2026-09-25T02:00:00Z"),
            entryBody(id = UUID.randomUUID(), createdAt = "2026-09-17T02:00:00Z"), // week 2026-09-13
            entryBody(id = UUID.randomUUID()), // current week
        )
        assertTrue(sync(env.aliceToken, upserts).results().all { it.status() == "OK" })
        assertEquals(
            listOf(aliceId to java.time.LocalDate.parse("2026-09-13"), aliceId to PREVIOUS_WEEK),
            env.reports.lateEntries.toList(),
        )
    }

    @Test
    fun `create then delete in one batch leaves nothing, and deletes are idempotent`() = dbTest { env ->
        val aliceId = createAccount(env.aliceToken)
        val id = UUID.randomUUID()
        val results = sync(env.aliceToken, listOf(entryBody(id = id)), listOf(jsonId(id), jsonId(id), jsonId(UUID.randomUUID()))).results()
        assertEquals(listOf("OK", "OK", "OK", "OK"), results.map { it.status() })
        assertEquals(0L, rowCount(aliceId))
    }

    @Test
    fun `another user's id is a CONFLICT and stays untouched`() = dbTest { env ->
        createAccount(env.aliceToken)
        val bobId = createAccount(env.bobToken)
        val id = UUID.randomUUID()
        putEntry(env.bobToken, id, entryBody(quantity = 4))

        val results = sync(env.aliceToken, listOf(entryBody(id = id, quantity = 9)), listOf(jsonId(id))).results()
        assertEquals(listOf("CONFLICT", "OK"), results.map { it.status() })
        assertEquals(JsonNull, results[0]["entry"])
        assertEquals(1L, rowCount(bobId))
        val bobs = listEntries(env.bobToken).json()["entries"]!!.jsonArray.single().jsonObject
        assertEquals(4, bobs["quantity"]!!.jsonPrimitive.int)
    }

    @Test
    fun `oversized batches are rejected as a whole`() = dbTest { env ->
        createAccount(env.aliceToken)
        val response = sync(env.aliceToken, deletes = List(SYNC_MAX_ITEMS + 1) { jsonId(UUID.randomUUID()) })
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("VALIDATION_FAILED", response.errorCode())
        assertEquals(HttpStatusCode.OK, sync(env.aliceToken, deletes = List(SYNC_MAX_ITEMS) { jsonId(UUID.randomUUID()) }).status)
    }

    @Test
    fun `an empty batch returns no results`() = dbTest { env ->
        createAccount(env.aliceToken)
        assertEquals(emptyList(), sync(env.aliceToken).results())
    }
}
