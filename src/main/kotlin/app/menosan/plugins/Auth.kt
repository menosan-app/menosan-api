package app.menosan.plugins

import app.menosan.account.User
import app.menosan.account.UserRepository
import app.menosan.common.ApiException
import app.menosan.common.ErrorCode
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.routing.Route
import io.ktor.server.routing.RouteSelector
import io.ktor.server.routing.RouteSelectorEvaluation
import io.ktor.server.routing.RoutingResolveContext
import io.ktor.util.AttributeKey
import java.util.UUID

class VerifiedToken(val uid: String, val email: String?, val name: String?) {
    override fun toString() = "VerifiedToken(uid=$uid)"
}

fun interface TokenVerifier {
    suspend fun verify(idToken: String): VerifiedToken?
}

class UserPrincipal(val userId: UUID, val firebaseUid: String, val email: String) {
    override fun toString() = "UserPrincipal(userId=$userId)"
}

private val VerifiedTokenKey = AttributeKey<VerifiedToken>("VerifiedToken")
private val AccountKey = AttributeKey<User>("MenosanAccount")

val ApplicationCall.verifiedToken: VerifiedToken
    get() = attributes.getOrNull(VerifiedTokenKey) ?: error("Route is not inside authenticated { }")

val ApplicationCall.account: User
    get() = attributes.getOrNull(AccountKey) ?: error("Route is not inside authenticated(requireAccount = true) { }")

val ApplicationCall.principal: UserPrincipal
    get() = account.let { UserPrincipal(it.id, it.firebaseUid, it.email) }

class FirebaseAuthConfig {
    var verifier: TokenVerifier? = null
    var users: UserRepository? = null
    var requireAccount: Boolean = true
}

val FirebaseAuthPlugin = createRouteScopedPlugin("FirebaseAuth", ::FirebaseAuthConfig) {
    val verifier = requireNotNull(pluginConfig.verifier) { "verifier is required" }
    val users = requireNotNull(pluginConfig.users) { "users is required" }
    val requireAccount = pluginConfig.requireAccount

    onCall { call ->
        val token = call.bearerToken()
            ?: throw ApiException(ErrorCode.UNAUTHENTICATED, "Sign in is required.")
        val verified = verifier.verify(token)
            ?: throw ApiException(ErrorCode.UNAUTHENTICATED, "Your sign-in has expired or is invalid. Please sign in again.")
        call.attributes.put(VerifiedTokenKey, verified)
        if (requireAccount) {
            val user = users.findByFirebaseUid(verified.uid)
                ?: throw ApiException(ErrorCode.ACCOUNT_NOT_FOUND, "No Menosan account exists for this sign-in yet.")
            call.attributes.put(AccountKey, user)
        }
    }
}

private fun ApplicationCall.bearerToken(): String? {
    val header = request.headers[HttpHeaders.Authorization]?.trim() ?: return null
    val parts = header.split(Regex("\\s+"), limit = 2)
    if (parts.size != 2 || !parts[0].equals("Bearer", ignoreCase = true)) return null
    return parts[1].trim().takeIf { it.isNotEmpty() }
}

fun Route.authenticated(
    verifier: TokenVerifier,
    users: UserRepository,
    requireAccount: Boolean = true,
    build: Route.() -> Unit,
): Route {
    val child = createChild(AuthenticatedSelector(requireAccount))
    child.install(FirebaseAuthPlugin) {
        this.verifier = verifier
        this.users = users
        this.requireAccount = requireAccount
    }
    child.build()
    return child
}

private class AuthenticatedSelector(private val requireAccount: Boolean) : RouteSelector() {
    override suspend fun evaluate(context: RoutingResolveContext, segmentIndex: Int) = RouteSelectorEvaluation.Transparent
    override fun toString() = if (requireAccount) "(authenticated)" else "(authenticated, no account)"
}
