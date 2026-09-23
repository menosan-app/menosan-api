package app.menosan.entries

import app.menosan.CURRENT_WEEK
import app.menosan.DbTestEnv
import app.menosan.PREVIOUS_WEEK
import app.menosan.common.OverridableClock
import app.menosan.createAccount
import app.menosan.dbTest
import app.menosan.deleteEntry
import app.menosan.entryBody
import app.menosan.errorCode
import app.menosan.fixedClock
import app.menosan.json
import app.menosan.listEntries
import app.menosan.putEntry
import app.menosan.putEntryRaw
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EntryRoutesTest {
    private fun JsonObject.str(key: String) = this[key]!!.jsonPrimitive.content
    private suspend fun io.ktor.client.statement.HttpResponse.entries() = json()["entries"]!!.jsonArray.map { it.jsonObject }

    @Test
    fun `create returns 201 with derived fields, update returns 200`() = dbTest { env ->
        createAccount(env.aliceToken)
        val id = UUID.randomUUID()

        val created = putEntry(env.aliceToken, id, entryBody(name = "  Shampoo sachet ", quantity = 2))
        assertEquals(HttpStatusCode.Created, created.status)
        val body = created.json()
        assertEquals(id.toString(), body.str("id"))
        assertEquals("Shampoo sachet", body.str("name"))
        assertEquals("RESIDUAL", body.str("category"))
        assertEquals("RES_SACHETS", body.str("subcategory"))
        assertEquals(2, body["quantity"]!!.jsonPrimitive.int)
        assertEquals("MANUAL", body.str("source"))
        assertEquals("2026-09-29T01:00:00Z", body.str("createdAt"))
        assertEquals(CURRENT_WEEK.toString(), body.str("weekStart"))
        assertEquals("2026-09-30T04:00:00Z", body.str("updatedAt"))
        assertTrue(body["editable"]!!.jsonPrimitive.boolean)

        val updated = putEntry(env.aliceToken, id, entryBody(name = "PET bottle", subcategory = "REC_PET_BOTTLES", quantity = 4, source = "PHOTO"))
        assertEquals(HttpStatusCode.OK, updated.status)
        assertEquals("RECYCLABLE", updated.json().str("category"))

        val listed = listEntries(env.aliceToken).entries().single()
        assertEquals("PET bottle", listed.str("name"))
        assertEquals("REC_PET_BOTTLES", listed.str("subcategory"))
        assertEquals(4, listed["quantity"]!!.jsonPrimitive.int)
        assertEquals("PHOTO", listed.str("source"))
        assertEquals("2026-09-29T01:00:00Z", listed.str("createdAt"))
    }

    @Test
    fun `list defaults to the current week, newest first, and accepts a weekStart`() = dbTest { env ->
        createAccount(env.aliceToken)
        val older = UUID.randomUUID()
        val newer = UUID.randomUUID()
        val lastWeek = UUID.randomUUID()
        putEntry(env.aliceToken, older, entryBody(createdAt = "2026-09-27T00:00:00Z"))
        putEntry(env.aliceToken, newer, entryBody(createdAt = "2026-09-30T03:00:00Z"))
        putEntry(env.aliceToken, lastWeek, entryBody(createdAt = "2026-09-22T02:00:00Z"))

        val current = listEntries(env.aliceToken)
        assertEquals(HttpStatusCode.OK, current.status)
        val body = current.json()
        assertEquals(CURRENT_WEEK.toString(), body.str("weekStart"))
        assertEquals("2026-10-03", body.str("weekEnd"))
        assertTrue(body["editable"]!!.jsonPrimitive.boolean)
        assertEquals(listOf(newer, older).map { it.toString() }, current.entries().map { it.str("id") })

        val past = listEntries(env.aliceToken, PREVIOUS_WEEK)
        assertEquals(listOf(lastWeek.toString()), past.entries().map { it.str("id") })
        assertEquals(false, past.json()["editable"]!!.jsonPrimitive.boolean)
        assertEquals(false, past.entries().single()["editable"]!!.jsonPrimitive.boolean)

        assertEquals(0, listEntries(env.aliceToken, LocalDate.parse("2026-09-06")).entries().size)
    }

    @Test
    fun `weekStart must be a Sunday date`() = dbTest { env ->
        createAccount(env.aliceToken)
        for (value in listOf("2026-09-28", "2026-13-01", "last-week", "")) {
            val response = client.get("/v1/entries?weekStart=$value") { bearerAuth(env.aliceToken) }
            assertEquals(HttpStatusCode.BadRequest, response.status, value)
            assertEquals("VALIDATION_FAILED", response.errorCode())
        }
    }

    @Test
    fun `invalid fields return 400 with the field name and store nothing`() = dbTest { env ->
        createAccount(env.aliceToken)
        val cases = mapOf(
            "name" to entryBody(name = " "),
            "subcategory" to entryBody(subcategory = "NOPE"),
            "quantity" to entryBody(quantity = 0),
            "source" to entryBody(source = "AUTO"),
            "createdAt" to entryBody(createdAt = "tomorrow"),
        )
        for ((field, body) in cases) {
            val response = putEntry(env.aliceToken, UUID.randomUUID(), body)
            assertEquals(HttpStatusCode.BadRequest, response.status, field)
            assertEquals("VALIDATION_FAILED", response.errorCode())
            assertEquals(field, response.json()["error"]!!.jsonObject["details"]!!.jsonObject.str("field"))
        }
        val wrongType = putEntryRaw(env.aliceToken, UUID.randomUUID().toString(), """{"name":"x","quantity":"many"}""")
        assertEquals(HttpStatusCode.BadRequest, wrongType.status)
        assertEquals("VALIDATION_FAILED", wrongType.errorCode())

        val badId = putEntryRaw(env.aliceToken, "not-a-uuid", entryBody().toString())
        assertEquals(HttpStatusCode.BadRequest, badId.status)
        assertEquals(HttpStatusCode.BadRequest, client.delete("/v1/entries/1-1-1-1-1") { bearerAuth(env.aliceToken) }.status)

        assertEquals(0, listEntries(env.aliceToken).entries().size)
    }

    @Test
    fun `create timestamps are bounded - 5 minutes ahead, 14 days back`() = dbTest { env ->
        createAccount(env.aliceToken)
        val future = putEntry(env.aliceToken, UUID.randomUUID(), entryBody(createdAt = "2026-09-30T04:05:00.001Z"))
        assertEquals(HttpStatusCode.UnprocessableEntity, future.status)
        assertEquals("INVALID_TIMESTAMP", future.errorCode())
        val old = putEntry(env.aliceToken, UUID.randomUUID(), entryBody(createdAt = "2026-09-16T03:59:59.999Z"))
        assertEquals(HttpStatusCode.UnprocessableEntity, old.status)
        assertEquals("INVALID_TIMESTAMP", old.errorCode())

        assertEquals(HttpStatusCode.Created, putEntry(env.aliceToken, UUID.randomUUID(), entryBody(createdAt = "2026-09-30T04:05:00Z")).status)
        assertEquals(HttpStatusCode.Created, putEntry(env.aliceToken, UUID.randomUUID(), entryBody(createdAt = "2026-09-16T04:00:00Z")).status)
        assertTrue(env.reports.lateEntries.isNotEmpty(), "the 14-day-old create lands in a closed week")
    }

    @Test
    fun `createdAt can't change on update`() = dbTest { env ->
        createAccount(env.aliceToken)
        val id = UUID.randomUUID()
        putEntry(env.aliceToken, id, entryBody())
        val response = putEntry(env.aliceToken, id, entryBody(createdAt = "2026-09-29T01:00:01Z"))
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("createdAt", response.json()["error"]!!.jsonObject["details"]!!.jsonObject.str("field"))
        // The same instant written differently is not a change.
        assertEquals(HttpStatusCode.OK, putEntry(env.aliceToken, id, entryBody(createdAt = "2026-09-29T09:00:00.000+08:00", quantity = 5)).status)
    }

    @Test
    fun `late create into a closed week is accepted and triggers report regeneration`() = dbTest { env ->
        val aliceId = createAccount(env.aliceToken)
        val id = UUID.randomUUID()
        val response = putEntry(env.aliceToken, id, entryBody(createdAt = "2026-09-22T02:00:00Z"))
        assertEquals(HttpStatusCode.Created, response.status)
        assertEquals(PREVIOUS_WEEK.toString(), response.json().str("weekStart"))
        assertEquals(false, response.json()["editable"]!!.jsonPrimitive.boolean)
        assertEquals(listOf(aliceId to PREVIOUS_WEEK), env.reports.lateEntries.toList())

        // A retry of the same create is an unchanged replay: 200, and no second regeneration.
        assertEquals(HttpStatusCode.OK, putEntry(env.aliceToken, id, entryBody(createdAt = "2026-09-22T02:00:00Z")).status)
        // A real change is rejected, because the week is closed.
        val edit = putEntry(env.aliceToken, id, entryBody(createdAt = "2026-09-22T02:00:00Z", quantity = 9))
        assertEquals(HttpStatusCode.Conflict, edit.status)
        assertEquals("WEEK_CLOSED", edit.errorCode())
        assertEquals(1, env.reports.lateEntries.size)

        // Current-week creates never trigger regeneration.
        putEntry(env.aliceToken, UUID.randomUUID(), entryBody())
        assertEquals(1, env.reports.lateEntries.size)
    }

    @Test
    fun `a failing report refresh doesn't fail the late create`() {
        val env = DbTestEnv().apply { reports.failLateEntry = true }
        dbTest(env) {
            createAccount(env.aliceToken)
            val response = putEntry(env.aliceToken, UUID.randomUUID(), entryBody(createdAt = "2026-09-22T02:00:00Z"))
            assertEquals(HttpStatusCode.Created, response.status)
            assertEquals(1, listEntries(env.aliceToken, PREVIOUS_WEEK).entries().size)
        }
    }

    @Test
    fun `week boundary - edits allowed until Saturday 23 59 59 PHT, rejected from Sunday 00 00`() {
        val clock = OverridableClock(fixedClock("2026-10-03T15:59:00Z")) // Sat 23:59 PHT
        val env = DbTestEnv(clock)
        dbTest(env) {
            createAccount(env.aliceToken)
            val id = UUID.randomUUID()
            val lastMoment = "2026-10-03T15:59:59.999Z" // Sat 23:59:59.999 PHT
            assertEquals(HttpStatusCode.Created, putEntry(env.aliceToken, id, entryBody(createdAt = lastMoment)).status)
            assertEquals(HttpStatusCode.OK, putEntry(env.aliceToken, id, entryBody(createdAt = lastMoment, quantity = 4)).status)

            clock.setOverride(Instant.parse("2026-10-03T15:59:59.999Z"))
            assertEquals(HttpStatusCode.OK, putEntry(env.aliceToken, id, entryBody(createdAt = lastMoment, quantity = 5)).status)

            clock.setOverride(Instant.parse("2026-10-03T16:00:00Z")) // Sun 00:00 PHT: week 2026-09-27 is closed
            val edit = putEntry(env.aliceToken, id, entryBody(createdAt = lastMoment, quantity = 6))
            assertEquals(HttpStatusCode.Conflict, edit.status)
            assertEquals("WEEK_CLOSED", edit.errorCode())
            assertEquals(CURRENT_WEEK.toString(), edit.json()["error"]!!.jsonObject["details"]!!.jsonObject.str("weekStart"))
            val delete = deleteEntry(env.aliceToken, id)
            assertEquals(HttpStatusCode.Conflict, delete.status)
            assertEquals("WEEK_CLOSED", delete.errorCode())

            // The entry is unchanged and still there, now in a read-only week.
            val stored = listEntries(env.aliceToken, CURRENT_WEEK).entries().single()
            assertEquals(5, stored["quantity"]!!.jsonPrimitive.int)
            assertEquals(false, stored["editable"]!!.jsonPrimitive.boolean)

            // Sun 00:00:00 PHT belongs to the new week and is editable.
            val next = UUID.randomUUID()
            val created = putEntry(env.aliceToken, next, entryBody(createdAt = "2026-10-03T16:00:00Z"))
            assertEquals(HttpStatusCode.Created, created.status)
            assertEquals("2026-10-04", created.json().str("weekStart"))
            assertEquals(HttpStatusCode.OK, putEntry(env.aliceToken, next, entryBody(createdAt = "2026-10-03T16:00:00Z", quantity = 2)).status)
            assertEquals(HttpStatusCode.NoContent, deleteEntry(env.aliceToken, next).status)
        }
    }

    @Test
    fun `delete is idempotent`() = dbTest { env ->
        createAccount(env.aliceToken)
        val id = UUID.randomUUID()
        putEntry(env.aliceToken, id, entryBody())
        assertEquals(HttpStatusCode.NoContent, deleteEntry(env.aliceToken, id).status)
        assertEquals(HttpStatusCode.NoContent, deleteEntry(env.aliceToken, id).status)
        assertEquals(HttpStatusCode.NoContent, deleteEntry(env.aliceToken, UUID.randomUUID()).status)
        assertEquals(0, listEntries(env.aliceToken).entries().size)
    }

    @Test
    fun `users can't read, update, or delete each other's entries`() = dbTest { env ->
        createAccount(env.aliceToken)
        createAccount(env.bobToken)
        val id = UUID.randomUUID()
        putEntry(env.aliceToken, id, entryBody(name = "Alice's sachet", quantity = 3))

        assertEquals(0, listEntries(env.bobToken).entries().size)
        assertEquals(0, listEntries(env.bobToken, CURRENT_WEEK).entries().size)

        val hijack = putEntry(env.bobToken, id, entryBody(name = "Bob's", quantity = 9))
        assertEquals(HttpStatusCode.Conflict, hijack.status)
        assertEquals("CONFLICT", hijack.errorCode())

        assertEquals(HttpStatusCode.NoContent, deleteEntry(env.bobToken, id).status)

        val stored = listEntries(env.aliceToken).entries().single()
        assertEquals("Alice's sachet", stored.str("name"))
        assertEquals(3, stored["quantity"]!!.jsonPrimitive.int)
        assertEquals(0, listEntries(env.bobToken).entries().size)
    }

    @Test
    fun `entry routes require a token and an account`() = dbTest { env ->
        val id = UUID.randomUUID()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/entries").status)
        assertEquals(HttpStatusCode.Unauthorized, putEntry("forged", id, entryBody()).status)
        assertEquals(HttpStatusCode.Unauthorized, deleteEntry("forged", id).status)

        val noAccount = putEntry(env.noAccountToken, id, entryBody())
        assertEquals(HttpStatusCode.NotFound, noAccount.status)
        assertEquals("ACCOUNT_NOT_FOUND", noAccount.errorCode())
        assertEquals("ACCOUNT_NOT_FOUND", listEntries(env.noAccountToken).errorCode())
    }
}

