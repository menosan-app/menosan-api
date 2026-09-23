package app.menosan.interventions

import app.menosan.common.ApiException
import app.menosan.common.ErrorCode
import app.menosan.plugins.principal
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.UUID

@Serializable
data class AdoptRequest(val interventionIds: List<String>? = null)

/** Interim adoption response until BE-3's report payload is wired through [ReportResponder]. */
@Serializable
data class AdoptionsResponse(val weekStart: String, val adoptedInterventionIds: List<String>)

/**
 * Writes the response after an adoption change. The contract says "→ the updated report" (§8.2), so BE-3
 * provides one that renders the full §8.3 payload. [AdoptionStateResponder] is the interim default.
 */
fun interface ReportResponder {
    suspend fun respond(call: ApplicationCall, userId: UUID, weekStart: LocalDate)
}

class AdoptionStateResponder(private val adoptions: AdoptionService) : ReportResponder {
    override suspend fun respond(call: ApplicationCall, userId: UUID, weekStart: LocalDate) {
        val ids = adoptions.adoptedIds(userId, weekStart)
            ?: throw ApiException(ErrorCode.NOT_FOUND, "Report not found.")
        call.respond(AdoptionsResponse(weekStart.toString(), ids.map { it.toString() }.sorted()))
    }
}

private const val MAX_IDS_PER_REQUEST = 9 // 3 hotspots × 3 recommendations

/**
 * `POST /v1/reports/{weekStart}/adoptions` and `DELETE /v1/reports/{weekStart}/adoptions/{interventionId}`
 * (§6.3, SFR16). Mount inside `authenticated { }`.
 */
fun Route.adoptionRoutes(adoptions: AdoptionService, responder: ReportResponder) {
    post("/reports/{weekStart}/adoptions") {
        val userId = call.principal.userId
        val weekStart = call.weekStartParam()
        val ids = call.receive<AdoptRequest>().interventionIds
        if (ids.isNullOrEmpty() || ids.size > MAX_IDS_PER_REQUEST) {
            throw invalid("interventionIds", "Choose between 1 and $MAX_IDS_PER_REQUEST suggestions to adopt.")
        }
        val parsed = ids.map { parseUuid(it) ?: throw invalid("interventionIds", "Invalid suggestion id.") }.toSet()
        adoptions.adopt(userId, weekStart, parsed)
        responder.respond(call, userId, weekStart)
    }

    delete("/reports/{weekStart}/adoptions/{interventionId}") {
        val userId = call.principal.userId
        val weekStart = call.weekStartParam()
        val interventionId = parseUuid(call.parameters["interventionId"])
            ?: throw invalid("interventionId", "Invalid suggestion id.")
        adoptions.unadopt(userId, weekStart, interventionId)
        responder.respond(call, userId, weekStart)
    }
}

private fun ApplicationCall.weekStartParam(): LocalDate {
    val date = runCatching { LocalDate.parse(parameters["weekStart"]) }.getOrNull()
    if (date == null || date.dayOfWeek != DayOfWeek.SUNDAY) {
        throw invalid("weekStart", "weekStart must be a Sunday date (YYYY-MM-DD).")
    }
    return date
}

private fun parseUuid(value: String?): UUID? =
    value?.let { runCatching { UUID.fromString(it) }.getOrNull() }?.takeIf { it.toString().equals(value, ignoreCase = true) }

private fun invalid(field: String, message: String) =
    ApiException(ErrorCode.VALIDATION_FAILED, message, buildJsonObject { put("field", JsonPrimitive(field)) })
