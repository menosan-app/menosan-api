package app.menosan.dev

import app.menosan.common.ApiException
import app.menosan.common.ErrorCode
import app.menosan.jobs.requireJobKey
import app.menosan.plugins.ApiJson
import app.menosan.reports.parseWeekStart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.Instant
import java.time.format.DateTimeParseException

@Serializable
data class DevClockRequest(val now: String? = null)

@Serializable
data class DevUserRequest(val email: String? = null)

@Serializable
data class SeedHistoryRequest(val email: String? = null, val weeks: Int = 3, val adopt: Boolean = true, val seed: Long = 1)

@Serializable
data class GenerateReportRequest(val email: String? = null, val weekStart: String? = null, val regenerate: Boolean = false)

const val MAX_SEED_WEEKS = 8

/**
 * `/internal/dev/...` (plan §8.2). Mount only when DEV_TOOLS_ENABLED=true. Every call needs `X-Job-Key`
 * (404 when JOB_KEY isn't configured, 401 for a wrong key), like `/internal/jobs/...`.
 */
fun Route.devRoutes(jobKey: String?, tools: DevTools) {
    route("/internal/dev") {
        get("/clock") {
            call.requireJobKey(jobKey)
            call.respond(tools.clockState())
        }
        // {"now":"2026-10-04T00:05:00Z"} sets the server time; {} or {"now":null} restores real time.
        post("/clock") {
            call.requireJobKey(jobKey)
            val body = call.receiveJson(DevClockRequest.serializer(), DevClockRequest())
            val now = body.now?.let {
                try {
                    Instant.parse(it)
                } catch (_: DateTimeParseException) {
                    throw invalid("now", "now must be an ISO-8601 instant, e.g. 2026-10-04T00:05:00Z.")
                }
            }
            call.respond(tools.setClock(now))
        }
        post("/seed-history") {
            call.requireJobKey(jobKey)
            val body = call.receiveJson(SeedHistoryRequest.serializer(), null)
            if (body.weeks !in 1..MAX_SEED_WEEKS) throw invalid("weeks", "weeks must be 1–$MAX_SEED_WEEKS.")
            val userId = tools.userIdByEmail(requireEmail(body.email))
            call.respond(tools.seedHistory(userId, body.weeks, body.adopt, body.seed))
        }
        post("/reset") {
            call.requireJobKey(jobKey)
            val body = call.receiveJson(DevUserRequest.serializer(), null)
            call.respond(tools.reset(tools.userIdByEmail(requireEmail(body.email))))
        }
        post("/reports/generate") {
            call.requireJobKey(jobKey)
            val body = call.receiveJson(GenerateReportRequest.serializer(), null)
            val weekStart = parseWeekStart(body.weekStart)
            val userId = tools.userIdByEmail(requireEmail(body.email))
            val report = tools.generateReport(userId, weekStart, body.regenerate)
                ?: throw ApiException(ErrorCode.NOT_FOUND, "Nothing was logged that week, so there is no report.")
            call.respond(report)
        }
    }
}

private fun requireEmail(email: String?): String =
    email?.takeIf { it.isNotBlank() } ?: throw invalid("email", "email is required.")

private fun invalid(field: String, message: String) =
    ApiException(ErrorCode.VALIDATION_FAILED, message, buildJsonObject { put("field", JsonPrimitive(field)) })

/** Decodes the body with [serializer]. A blank body gives [whenEmpty], or 400 when the body is required (null). */
private suspend fun <T> ApplicationCall.receiveJson(serializer: KSerializer<T>, whenEmpty: T?): T {
    val text = receiveText()
    if (text.isBlank()) return whenEmpty ?: throw ApiException(ErrorCode.VALIDATION_FAILED, "A JSON body is required.")
    return try {
        ApiJson.decodeFromString(serializer, text)
    } catch (_: SerializationException) {
        throw ApiException(ErrorCode.VALIDATION_FAILED, "The request is malformed or has invalid fields.")
    } catch (_: IllegalArgumentException) {
        throw ApiException(ErrorCode.VALIDATION_FAILED, "The request is malformed or has invalid fields.")
    }
}
