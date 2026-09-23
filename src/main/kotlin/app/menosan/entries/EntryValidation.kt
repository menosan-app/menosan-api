package app.menosan.entries

import app.menosan.common.ApiException
import app.menosan.common.ErrorCode
import app.menosan.taxonomy.Taxonomy
import app.menosan.taxonomy.WasteCategory
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Body of `PUT /v1/entries/{id}` and of each `upserts[]` item in `POST /v1/entries/sync`. All optional so we can name the bad field. */
@Serializable
data class EntryPutRequest(
    val name: String? = null,
    val subcategory: String? = null,
    val quantity: Int? = null,
    val source: String? = null,
    val createdAt: String? = null,
)

/** A validated entry payload. `category` is derived from the subcategory; `createdAt` is truncated to milliseconds. */
class EntryInput(
    val name: String,
    val category: WasteCategory,
    val subcategoryCode: String,
    val quantity: Int,
    val source: EntrySource,
    val createdAt: Instant,
) {
    override fun toString() = "EntryInput(subcategory=$subcategoryCode, quantity=$quantity, createdAt=$createdAt)"
}

const val NAME_MAX_LENGTH = 60
const val QUANTITY_MIN = 1
const val QUANTITY_MAX = 999

/** Clients may be a little ahead of the server clock (plan §4). */
val MAX_FUTURE_SKEW: Duration = Duration.ofMinutes(5)

/** Offline creates are accepted into their original week for this long (plan §2.2 I10). */
val MAX_CREATE_AGE: Duration = Duration.ofDays(14)

/** Field validation from plan §4 and §8 (SFR5.1). Throws `400 VALIDATION_FAILED` with `details.field`. */
fun validateEntry(request: EntryPutRequest, taxonomy: Taxonomy): EntryInput {
    val name = request.name?.trim()
        ?: invalid("name", "Please enter a name.")
    val nameLength = name.codePointCount(0, name.length) // matches Postgres char_length
    if (nameLength !in 1..NAME_MAX_LENGTH) invalid("name", "Name must be 1 to $NAME_MAX_LENGTH characters.")

    val subcategory = request.subcategory ?: invalid("subcategory", "Please choose a subcategory.")
    val category = taxonomy.categoryOf(subcategory) ?: invalid("subcategory", "Unknown subcategory.")

    val quantity = request.quantity ?: invalid("quantity", "Please enter a quantity.")
    if (quantity !in QUANTITY_MIN..QUANTITY_MAX) invalid("quantity", "Quantity must be a whole number from $QUANTITY_MIN to $QUANTITY_MAX.")

    val source = request.source?.let { s -> EntrySource.entries.firstOrNull { it.name == s } }
        ?: invalid("source", "Source must be MANUAL or PHOTO.")

    val createdAtText = request.createdAt ?: invalid("createdAt", "createdAt is required.")
    val createdAt = parseInstant(createdAtText) ?: invalid("createdAt", "createdAt must be an ISO-8601 instant.")

    return EntryInput(name, category, subcategory, quantity, source, createdAt.truncatedTo(ChronoUnit.MILLIS))
}

/** Timestamp rules for a **create** (plan §4). Throws `422 INVALID_TIMESTAMP`. */
fun checkCreateTimestamp(createdAt: Instant, now: Instant) {
    if (createdAt.isAfter(now.plus(MAX_FUTURE_SKEW))) {
        throw invalidTimestamp("FUTURE", "This entry's time is in the future. Please check your phone's date and time.")
    }
    if (createdAt.isBefore(now.minus(MAX_CREATE_AGE))) {
        throw invalidTimestamp("TOO_OLD", "Entries older than 14 days can't be added.")
    }
}

/** Parses a canonical UUID (`8-4-4-4-12` hex). `UUID.fromString` alone accepts odd forms like `1-1-1-1-1`. */
fun parseUuidOrNull(text: String?): UUID? {
    if (text == null || text.length != 36) return null
    return try {
        UUID.fromString(text).takeIf { it.toString() == text.lowercase() }
    } catch (_: IllegalArgumentException) {
        null
    }
}

fun parseIdOrThrow(text: String?): UUID = parseUuidOrNull(text) ?: invalid("id", "The entry id must be a UUID.")

private fun parseInstant(text: String): Instant? = try {
    Instant.parse(text)
} catch (_: DateTimeParseException) {
    try {
        OffsetDateTime.parse(text).toInstant()
    } catch (_: DateTimeParseException) {
        null
    }
}

private fun invalid(field: String, message: String): Nothing =
    throw ApiException(ErrorCode.VALIDATION_FAILED, message, fieldDetails(field))

private fun invalidTimestamp(reason: String, message: String) = ApiException(
    ErrorCode.INVALID_TIMESTAMP,
    message,
    JsonObject(mapOf("field" to JsonPrimitive("createdAt"), "reason" to JsonPrimitive(reason))),
)

internal fun fieldDetails(field: String) = JsonObject(mapOf("field" to JsonPrimitive(field)))
