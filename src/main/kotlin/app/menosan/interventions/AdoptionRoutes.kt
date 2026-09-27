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

private const val MAX_IDS_PER_REQUEST = 9

fun Route.adoptionRoutes(adoptions: AdoptionService, reports: ReportService) {
    post("/reports/{weekStart}/adoptions") {
        val userId = call.principal.userId
        val weekStart = call.weekStartParam()
        val ids = call.receive<AdoptRequest>().interventionIds
        if (ids.isNullOrEmpty() || ids.size > MAX_IDS_PER_REQUEST) {
            throw invalid("interventionIds", "Choose between 1 and $MAX_IDS_PER_REQUEST suggestions to adopt.")
        }
        val parsed = ids.map { parseUuid(it) ?: throw invalid("interventionIds", "Invalid suggestion id.") }.toSet()
        adoptions.adopt(userId, weekStart, parsed)
        call.respondReport(reports, userId, weekStart)
    }

    delete("/reports/{weekStart}/adoptions/{interventionId}") {
        val userId = call.principal.userId
        val weekStart = call.weekStartParam()
        val interventionId = parseUuid(call.parameters["interventionId"])
            ?: throw invalid("interventionId", "Invalid suggestion id.")
        adoptions.unadopt(userId, weekStart, interventionId)
        call.respondReport(reports, userId, weekStart)
    }
}

private suspend fun ApplicationCall.respondReport(reports: ReportService, userId: UUID, weekStart: LocalDate) {
    val report = reports.getReport(userId, weekStart)
        ?: throw ApiException(ErrorCode.NOT_FOUND, "Report not found.")
    respond(report)
}

private fun ApplicationCall.weekStartParam(): LocalDate = parseWeekStart(parameters["weekStart"])

private fun parseUuid(value: String?): UUID? =
    value?.let { runCatching { UUID.fromString(it) }.getOrNull() }?.takeIf { it.toString().equals(value, ignoreCase = true) }

private fun invalid(field: String, message: String) =
    ApiException(ErrorCode.VALIDATION_FAILED, message, buildJsonObject { put("field", JsonPrimitive(field)) })
