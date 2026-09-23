package app.menosan.account

import app.menosan.common.notImplemented
import app.menosan.db.Db
import app.menosan.db.Users
import app.menosan.plugins.verifiedToken
import com.google.firebase.auth.AuthErrorCode
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("app.menosan.account")

/** Deletes Firebase Auth users. Deleting a user that is already gone succeeds. */
fun interface FirebaseUserAdmin {
    suspend fun deleteUser(firebaseUid: String)
}

/** Firebase Admin SDK implementation. [deleteFn] is `FirebaseAuth::deleteUser` in production. */
class FirebaseAdminUsers(private val deleteFn: (String) -> Unit) : FirebaseUserAdmin {
    constructor(auth: FirebaseAuth) : this({ uid -> auth.deleteUser(uid) })

    override suspend fun deleteUser(firebaseUid: String) = withContext(Dispatchers.IO) {
        try {
            deleteFn(firebaseUid)
        } catch (e: FirebaseAuthException) {
            if (e.authErrorCode != AuthErrorCode.USER_NOT_FOUND) throw e
        }
    }
}

/** `DELETE /v1/account` (SFR4.1–4.2, NFR4, plan §9 BE-1). */
fun interface AccountDeletion {
    /** Deletes every row of the account linked to [firebaseUid] (if any), then the Firebase user. Idempotent. */
    suspend fun deleteAccount(firebaseUid: String)
}

/**
 * One `DELETE FROM users` removes all private data: every private table has `ON DELETE CASCADE` (plan §7).
 * The Firebase user is deleted after the commit. If that fails, the call fails and a retry finishes the job,
 * because the route also works when the Menosan account is already gone.
 */
class AccountDeletionService(private val db: Db, private val firebase: FirebaseUserAdmin) : AccountDeletion {
    override suspend fun deleteAccount(firebaseUid: String) {
        val deleted = db.tx { Users.deleteWhere { Users.firebaseUid eq firebaseUid } }
        firebase.deleteUser(firebaseUid)
        log.info("Account deleted (rows={})", deleted)
    }
}

object StubAccountDeletion : AccountDeletion {
    override suspend fun deleteAccount(firebaseUid: String) = notImplemented("Account deletion")
}

/** Mount inside `authenticated(requireAccount = false) { }` so a retry after a partial failure still works. */
fun Route.deleteAccountRoute(deletion: AccountDeletion) {
    delete("/account") {
        deletion.deleteAccount(call.verifiedToken.uid)
        call.respond(HttpStatusCode.NoContent)
    }
}
