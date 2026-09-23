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
data class CreateAccountRequest(val consent: Boolean? = null)

@Serializable
data class MeResponse(val id: String, val email: String, val displayName: String?, val createdAt: String)

fun User.toMeResponse() = MeResponse(id.toString(), email, displayName, createdAt.toApiString())

/** `GET /v1/me` (SFR2.1–2.2). Mount inside `authenticated { }`. */
fun Route.meRoutes() {
    get("/me") {
        call.respond(call.account.toMeResponse())
    }
}

/**
 * `POST /v1/account {consent:true}` (SFR1.1–1.2). Mount inside `authenticated(requireAccount = false) { }`.
 * Idempotent: 201 when created, 200 when the account already exists.
 */
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
        val token = call.verifiedToken
        val email = token.email
            ?: throw ApiException(ErrorCode.VALIDATION_FAILED, "Your Google account has no email address.")
        val (user, created) = users.createIfAbsent(token.uid, email, token.name, clock.instant())
        call.respond(if (created) HttpStatusCode.Created else HttpStatusCode.OK, user.toMeResponse())
    }
}
