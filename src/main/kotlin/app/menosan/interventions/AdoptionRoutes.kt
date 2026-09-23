package app.menosan.interventions

import app.menosan.common.ApiException
import app.menosan.common.ErrorCode
import app.menosan.plugins.principal
import app.menosan.reports.ReportService
import app.menosan.reports.parseWeekStart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.LocalDate
import java.util.UUID

@Serializable
data class AdoptRequest(val interventionIds: List<String>? = null)

/** Minimal adoption state, used by [AdoptionStateResponder] in tests that run without a report service. */
@Serializable
data class AdoptionsResponse(val weekStart: String, val adoptedInterventionIds: List<String>)

/** Writes the response after an adoption change. The contract says "→ the updated report" (§8.2). */
fun interface ReportResponder {
    suspend fun respond(call: ApplicationCall, userId: UUID, weekStart: LocalDate)
}

/** The production responder: the full §8.3 report from BE-3, with `adopted` flags reflecting the change. */
class ReportServiceResponder(private val reports: ReportService) : ReportResponder {
    override suspend fun respond(call: ApplicationCall, userId: UUID, weekStart: LocalDate) {
        val report = reports.getReport(userId, weekStart)
            ?: throw ApiException(ErrorCode.NOT_FOUND, "Report not found.")
        call.respond(report)
    }
}

/** Responds with only the adopted ids. For tests that don't wire a real [ReportService]. */
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

private fun ApplicationCall.weekStartParam(): LocalDate = parseWeekStart(parameters["weekStart"])

private fun parseUuid(value: String?): UUID? =
    value?.let { runCatching { UUID.fromString(it) }.getOrNull() }?.takeIf { it.toString().equals(value, ignoreCase = true) }

private fun invalid(field: String, message: String) =
    ApiException(ErrorCode.VALIDATION_FAILED, message, buildJsonObject { put("field", JsonPrimitive(field)) })
