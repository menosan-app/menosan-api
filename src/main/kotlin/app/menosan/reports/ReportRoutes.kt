package app.menosan.reports

import app.menosan.common.ApiException
import app.menosan.common.ErrorCode
import app.menosan.common.WeekCalc
import app.menosan.plugins.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** `GET /v1/reports` and `GET /v1/reports/{weekStart}` (§8.2, §8.3). Mount inside `authenticated { }`. */
fun Route.reportRoutes(reports: ReportService) {
    get("/reports") {
        call.respond(reports.listReports(call.principal.userId))
    }
    get("/reports/{weekStart}") {
        val weekStart = parseWeekStart(call.parameters["weekStart"])
        val report = reports.getReport(call.principal.userId, weekStart)
            ?: throw ApiException(ErrorCode.NOT_FOUND, "There is no report for this week.")
        call.respond(report)
    }
}

/** A `YYYY-MM-DD` Sunday, or 400 VALIDATION_FAILED with `details.field`. Also used by BE-4's adoption routes. */
fun parseWeekStart(raw: String?, field: String = "weekStart"): LocalDate {
    val date = try {
        raw?.let(LocalDate::parse)
    } catch (_: DateTimeParseException) {
        null
    }
    if (date == null || !WeekCalc.isWeekStart(date)) {
        throw ApiException(
            ErrorCode.VALIDATION_FAILED,
            "weekStart must be a Sunday in YYYY-MM-DD format.",
            buildJsonObject { put("field", JsonPrimitive(field)) },
        )
    }
    return date
}
