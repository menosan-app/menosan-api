package app.menosan.common

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

enum class ErrorCode(val status: HttpStatusCode) {
    UNAUTHENTICATED(HttpStatusCode.Unauthorized),
    ACCOUNT_NOT_FOUND(HttpStatusCode.NotFound),
    VALIDATION_FAILED(HttpStatusCode.BadRequest),
    INVALID_TIMESTAMP(HttpStatusCode.UnprocessableEntity),
    WEEK_CLOSED(HttpStatusCode.Conflict),
    NOT_FOUND(HttpStatusCode.NotFound),
    CONFLICT(HttpStatusCode.Conflict),
    ADOPTION_WINDOW_CLOSED(HttpStatusCode.Conflict),
    ANALYSIS_FAILED(HttpStatusCode.UnprocessableEntity),
    NOT_WASTE(HttpStatusCode.UnprocessableEntity),
    IMAGE_TOO_LARGE(HttpStatusCode.PayloadTooLarge),
    RATE_LIMITED(HttpStatusCode.TooManyRequests),
    INTERNAL(HttpStatusCode.InternalServerError),
}

class ApiException(
    val code: ErrorCode,
    override val message: String,
    val details: JsonObject = JsonObject(emptyMap()),
    val status: HttpStatusCode = code.status,
) : RuntimeException(message)

@Serializable
data class ErrorBody(val error: ErrorDetail)

@Serializable
data class ErrorDetail(val code: String, val message: String, val details: JsonObject = JsonObject(emptyMap()))
