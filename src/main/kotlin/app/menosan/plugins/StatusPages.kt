package app.menosan.plugins

import app.menosan.common.ApiException
import app.menosan.common.ErrorBody
import app.menosan.common.ErrorCode
import app.menosan.common.ErrorDetail
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.UnsupportedMediaTypeException
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("app.menosan.errors")

/** Every error leaves the API in the §8.1 shape: `{"error":{"code","message","details"}}`. */
fun Application.configureStatusPages() {
    install(StatusPages) {
        exception<ApiException> { call, e ->
            call.respondError(e.status, e.code, e.message, e.details)
        }
        // Never echo the cause: it can contain parts of the request body.
        exception<BadRequestException> { call, _ ->
            call.respondError(HttpStatusCode.BadRequest, ErrorCode.VALIDATION_FAILED, "The request is malformed or has invalid fields.")
        }
        exception<UnsupportedMediaTypeException> { call, _ ->
            call.respondError(HttpStatusCode.UnsupportedMediaType, ErrorCode.VALIDATION_FAILED, "Unsupported content type.")
        }
        exception<Throwable> { call, e ->
            log.error(
                "Unhandled {} on {} {} (requestId={})",
                e.javaClass.name, call.request.httpMethod.value, call.request.path(), call.callId,
            )
            call.respondError(HttpStatusCode.InternalServerError, ErrorCode.INTERNAL, "Something went wrong. Please try again.")
        }
    }
}

suspend fun ApplicationCall.respondError(
    status: HttpStatusCode,
    code: ErrorCode,
    message: String,
    details: JsonObject = JsonObject(emptyMap()),
) {
    respond(status, ErrorBody(ErrorDetail(code.name, message, details)))
}

/** Unmatched paths get the JSON error body instead of Ktor's empty 404. Register it last. */
fun Route.notFoundFallback() {
    route("{...}") {
        handle { throw ApiException(ErrorCode.NOT_FOUND, "Not found.") }
    }
}
