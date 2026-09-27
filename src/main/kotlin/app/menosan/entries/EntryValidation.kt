package app.menosan.entries

import app.menosan.analytics.QuantityUnit
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

@Serializable
data class EntryPutRequest(
    val name: String? = null,
    val subcategory: String? = null,
    val quantity: Int? = null,
    val source: String? = null,
    val createdAt: String? = null,
)

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

const val QUANTITY_MAX_PIECES = 999
const val QUANTITY_MAX_GRAMS = 10_000

fun QuantityUnit.maxQuantity(): Int = if (this == QuantityUnit.GRAMS) QUANTITY_MAX_GRAMS else QUANTITY_MAX_PIECES

private fun QuantityUnit.symbol(): String = if (this == QuantityUnit.GRAMS) "g" else "pieces"

val MAX_FUTURE_SKEW: Duration = Duration.ofMinutes(5)

val MAX_CREATE_AGE: Duration = Duration.ofDays(14)

fun validateEntry(request: EntryPutRequest, taxonomy: Taxonomy): EntryInput {
    val name = request.name?.trim()
        ?: invalid("name", "Please enter a name.")
    val nameLength = name.codePointCount(0, name.length)
    if (nameLength !in 1..NAME_MAX_LENGTH) invalid("name", "Name must be 1 to $NAME_MAX_LENGTH characters.")

    val subcategory = request.subcategory ?: invalid("subcategory", "Please choose a subcategory.")
    val category = taxonomy.categoryOf(subcategory) ?: invalid("subcategory", "Unknown subcategory.")

    val quantity = request.quantity ?: invalid("quantity", "Please enter a quantity.")
    val unit = taxonomy.unitOf(subcategory) ?: invalid("subcategory", "Unknown subcategory.")
    if (quantity !in QUANTITY_MIN..unit.maxQuantity()) {
        invalid("quantity", "Quantity must be a whole number from $QUANTITY_MIN to ${unit.maxQuantity()} ${unit.symbol()}.")
    }

    val source = request.source?.let { s -> EntrySource.entries.firstOrNull { it.name == s } }
        ?: invalid("source", "Source must be MANUAL or PHOTO.")

    val createdAtText = request.createdAt ?: invalid("createdAt", "createdAt is required.")
    val createdAt = parseInstant(createdAtText) ?: invalid("createdAt", "createdAt must be an ISO-8601 instant.")

    return EntryInput(name, category, subcategory, quantity, source, createdAt.truncatedTo(ChronoUnit.MILLIS))
}

fun checkCreateTimestamp(createdAt: Instant, now: Instant) {
    if (createdAt.isAfter(now.plus(MAX_FUTURE_SKEW))) {
        throw invalidTimestamp("FUTURE", "This entry's time is in the future. Please check your phone's date and time.")
    }
    if (createdAt.isBefore(now.minus(MAX_CREATE_AGE))) {
        throw invalidTimestamp("TOO_OLD", "Entries older than 14 days can't be added.")
    }
}

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
