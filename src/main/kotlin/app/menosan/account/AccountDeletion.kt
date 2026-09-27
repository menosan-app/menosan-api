package app.menosan.account

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

fun interface FirebaseUserAdmin {
    suspend fun deleteUser(firebaseUid: String)
}

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

fun interface AccountDeletion {
    suspend fun deleteAccount(firebaseUid: String)
}

class AccountDeletionService(private val db: Db, private val firebase: FirebaseUserAdmin) : AccountDeletion {
    override suspend fun deleteAccount(firebaseUid: String) {
        val deleted = db.tx { Users.deleteWhere { Users.firebaseUid eq firebaseUid } }
        firebase.deleteUser(firebaseUid)
        log.info("Account deleted (rows={})", deleted)
    }
}

fun Route.deleteAccountRoute(deletion: AccountDeletion) {
    delete("/account") {
        deletion.deleteAccount(call.verifiedToken.uid)
        call.respond(HttpStatusCode.NoContent)
    }
}
