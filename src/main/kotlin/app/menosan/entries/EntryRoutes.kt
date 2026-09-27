package app.menosan.entries

import app.menosan.common.ApiException
import app.menosan.common.ErrorCode
import app.menosan.common.WeekCalc
import app.menosan.plugins.principal
import app.menosan.plugins.toApiString
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeParseException

@Serializable
data class EntryDto(
    val id: String,
    val name: String,
    val category: String,
    val subcategory: String,
    val quantity: Int,
    val source: String,
    val createdAt: String,
    val weekStart: String,
    val updatedAt: String,
    val editable: Boolean,
)

fun WasteEntry.toDto(clock: Clock) = EntryDto(
    id = id.toString(),
    name = name,
    category = category.name,
    subcategory = subcategoryCode,
    quantity = quantity,
    source = source.name,
    createdAt = createdAt.toApiString(),
    weekStart = weekStart.toString(),
    updatedAt = updatedAt.toApiString(),
    editable = WeekCalc.isCurrentWeek(weekStart, clock),
)

@Serializable
data class EntryListResponse(val weekStart: String, val weekEnd: String, val editable: Boolean, val entries: List<EntryDto>)

@Serializable
data class SyncRequest(val upserts: List<JsonElement> = emptyList(), val deletes: List<JsonElement> = emptyList())

enum class SyncOp { UPSERT, DELETE }

enum class SyncStatus { OK, WEEK_CLOSED, INVALID, INVALID_TIMESTAMP, CONFLICT, ERROR }

@Serializable
data class SyncResult(val id: String?, val op: SyncOp, val status: SyncStatus, val entry: EntryDto?, val message: String?)

@Serializable
data class SyncResponse(val results: List<SyncResult>)

fun Route.entryRoutes(service: EntryService, clock: Clock) {
    route("/entries") {
        get {
            val weekStart = call.request.queryParameters["weekStart"]?.let(::parseWeekStart)
                ?: WeekCalc.currentWeekStart(clock)
            val entries = service.list(call.principal.userId, weekStart)
            call.respond(
                EntryListResponse(
                    weekStart = weekStart.toString(),
                    weekEnd = WeekCalc.weekEnd(weekStart).toString(),
                    editable = WeekCalc.isCurrentWeek(weekStart, clock),
                    entries = entries.map { it.toDto(clock) },
                ),
            )
        }

        post("/sync") {
            val body = call.receive<SyncRequest>()
            val results = service.sync(call.principal.userId, body.upserts, body.deletes)
            call.respond(SyncResponse(results))
        }

        put("/{id}") {
            val id = parseIdOrThrow(call.parameters["id"])
            val request = call.receive<EntryPutRequest>()
            val result = service.put(call.principal.userId, id, request)
            call.respond(if (result.created) HttpStatusCode.Created else HttpStatusCode.OK, result.entry.toDto(clock))
        }

        delete("/{id}") {
            val id = parseIdOrThrow(call.parameters["id"])
            service.delete(call.principal.userId, id)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private fun parseWeekStart(text: String): LocalDate {
    val date = try {
        LocalDate.parse(text)
    } catch (_: DateTimeParseException) {
        null
    }
    if (date == null || !WeekCalc.isWeekStart(date)) {
        throw ApiException(ErrorCode.VALIDATION_FAILED, "weekStart must be a Sunday date (YYYY-MM-DD).", fieldDetails("weekStart"))
    }
    return date
}
