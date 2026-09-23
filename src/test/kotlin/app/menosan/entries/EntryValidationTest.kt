package app.menosan.entries

import app.menosan.common.ApiException
import app.menosan.common.ErrorCode
import app.menosan.taxonomy.Taxonomy
import app.menosan.taxonomy.WasteCategory
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class EntryValidationTest {
    private val taxonomy = Taxonomy.loadDefault()
    private val valid = EntryPutRequest("  Coffee 3-in-1 sachet ", "RES_SACHETS", 3, "MANUAL", "2026-09-29T01:00:00.123456Z")

    private fun failure(request: EntryPutRequest): ApiException =
        assertFailsWith<ApiException> { validateEntry(request, taxonomy) }

    private fun assertInvalid(field: String, request: EntryPutRequest) {
        val e = failure(request)
        assertEquals(ErrorCode.VALIDATION_FAILED, e.code, field)
        assertEquals(field, e.details["field"]?.jsonPrimitive?.content)
    }

    @Test
    fun `valid input is trimmed, category is derived, createdAt is truncated to millis`() {
        val input = validateEntry(valid, taxonomy)
        assertEquals("Coffee 3-in-1 sachet", input.name)
        assertEquals(WasteCategory.RESIDUAL, input.category)
        assertEquals("RES_SACHETS", input.subcategoryCode)
        assertEquals(EntrySource.MANUAL, input.source)
        assertEquals(Instant.parse("2026-09-29T01:00:00.123Z"), input.createdAt)
    }

    @Test
    fun `category always comes from the subcategory`() {
        for (s in taxonomy.subcategories) {
            val input = validateEntry(valid.copy(subcategory = s.code), taxonomy)
            assertEquals(s.category, input.category.name, s.code)
        }
    }

    @Test
    fun `name must be 1 to 60 characters after trimming`() {
        assertInvalid("name", valid.copy(name = null))
        assertInvalid("name", valid.copy(name = ""))
        assertInvalid("name", valid.copy(name = "   "))
        assertInvalid("name", valid.copy(name = "a".repeat(61)))
        assertEquals(60, validateEntry(valid.copy(name = "a".repeat(60)), taxonomy).name.length)
        assertEquals("x", validateEntry(valid.copy(name = " x "), taxonomy).name)
        // Counted in code points like Postgres char_length: 60 emoji are 120 UTF-16 chars but valid.
        validateEntry(valid.copy(name = "🥤".repeat(60)), taxonomy)
        assertInvalid("name", valid.copy(name = "🥤".repeat(61)))
    }

    @Test
    fun `subcategory must be a taxonomy code`() {
        assertInvalid("subcategory", valid.copy(subcategory = null))
        assertInvalid("subcategory", valid.copy(subcategory = "RES_UNKNOWN"))
        assertInvalid("subcategory", valid.copy(subcategory = "res_sachets"))
        assertInvalid("subcategory", valid.copy(subcategory = "RESIDUAL"))
    }

    @Test
    fun `quantity must be 1 to 999`() {
        assertInvalid("quantity", valid.copy(quantity = null))
        assertInvalid("quantity", valid.copy(quantity = 0))
        assertInvalid("quantity", valid.copy(quantity = -1))
        assertInvalid("quantity", valid.copy(quantity = 1000))
        assertEquals(1, validateEntry(valid.copy(quantity = 1), taxonomy).quantity)
        assertEquals(999, validateEntry(valid.copy(quantity = 999), taxonomy).quantity)
    }

    @Test
    fun `source must be MANUAL or PHOTO`() {
        assertInvalid("source", valid.copy(source = null))
        assertInvalid("source", valid.copy(source = "manual"))
        assertInvalid("source", valid.copy(source = "AUTO"))
        assertEquals(EntrySource.PHOTO, validateEntry(valid.copy(source = "PHOTO"), taxonomy).source)
    }

    @Test
    fun `createdAt must be an ISO instant, offsets are converted to UTC`() {
        assertInvalid("createdAt", valid.copy(createdAt = null))
        assertInvalid("createdAt", valid.copy(createdAt = "yesterday"))
        assertInvalid("createdAt", valid.copy(createdAt = "2026-09-29"))
        assertInvalid("createdAt", valid.copy(createdAt = "2026-09-29T09:00:00")) // no zone
        assertEquals(
            Instant.parse("2026-09-29T01:00:00Z"),
            validateEntry(valid.copy(createdAt = "2026-09-29T09:00:00+08:00"), taxonomy).createdAt,
        )
    }

    @Test
    fun `create timestamps may be at most 5 minutes ahead and 14 days old`() {
        val now = Instant.parse("2026-09-30T04:00:00Z")
        checkCreateTimestamp(now.plus(Duration.ofMinutes(5)), now)
        checkCreateTimestamp(now.minus(Duration.ofDays(14)), now)

        val future = assertFailsWith<ApiException> { checkCreateTimestamp(now.plus(Duration.ofMinutes(5)).plusMillis(1), now) }
        assertEquals(ErrorCode.INVALID_TIMESTAMP, future.code)
        assertEquals("FUTURE", future.details["reason"]?.jsonPrimitive?.content)

        val old = assertFailsWith<ApiException> { checkCreateTimestamp(now.minus(Duration.ofDays(14)).minusMillis(1), now) }
        assertEquals(ErrorCode.INVALID_TIMESTAMP, old.code)
        assertEquals("TOO_OLD", old.details["reason"]?.jsonPrimitive?.content)
    }

    @Test
    fun `ids must be canonical UUIDs`() {
        val id = UUID.randomUUID()
        assertEquals(id, parseUuidOrNull(id.toString()))
        assertEquals(id, parseUuidOrNull(id.toString().uppercase()))
        assertNull(parseUuidOrNull("1-1-1-1-1"))
        assertNull(parseUuidOrNull("not-a-uuid"))
        assertNull(parseUuidOrNull(null))
        assertNull(parseUuidOrNull(id.toString().replace("-", "")))
    }
}
