package app.menosan.account

import app.menosan.common.ApiException
import app.menosan.common.ErrorCode
import app.menosan.plugins.account
import app.menosan.plugins.toApiString
import app.menosan.plugins.verifiedToken
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.Clock

@Serializable
data class CreateAccountRequest(val consent: Boolean? = null, val displayName: String? = null)

private const val DISPLAY_NAME_MAX = 60

@Serializable
data class MeResponse(val id: String, val email: String, val displayName: String?, val createdAt: String)

fun User.toMeResponse() = MeResponse(id.toString(), email, displayName, createdAt.toApiString())

fun Route.meRoutes() {
    get("/me") {
        call.respond(call.account.toMeResponse())
    }
}

fun Route.createAccountRoute(users: UserRepository, clock: Clock) {
    post("/account") {
        val body = call.receive<CreateAccountRequest>()
        if (body.consent != true) {
            throw ApiException(
                ErrorCode.VALIDATION_FAILED,
                "Please accept the privacy notice to create your account.",
                buildJsonObject { put("field", JsonPrimitive("consent")) },
            )
        }
        val displayName = body.displayName?.let(::normalizeDisplayName)
        val token = call.verifiedToken
        val email = token.email
            ?: throw ApiException(ErrorCode.VALIDATION_FAILED, "Your sign-in has no email address.")
        if (!token.emailVerified) {
            throw ApiException(ErrorCode.VALIDATION_FAILED, "Please confirm your email address before creating an account.")
        }
        val (user, created) = users.createIfAbsent(token.uid, email, displayName ?: token.name, clock.instant())
        call.respond(if (created) HttpStatusCode.Created else HttpStatusCode.OK, user.toMeResponse())
    }
}

private fun normalizeDisplayName(raw: String): String {
    val name = raw.trim().replace(Regex("\\s+"), " ")
    if (name.isEmpty() || name.length > DISPLAY_NAME_MAX) {
        throw ApiException(
            ErrorCode.VALIDATION_FAILED,
            "Please enter a name between 1 and $DISPLAY_NAME_MAX characters.",
            buildJsonObject { put("field", JsonPrimitive("displayName")) },
        )
    }
    return name
}
