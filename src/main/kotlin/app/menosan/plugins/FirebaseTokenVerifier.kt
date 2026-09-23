package app.menosan.plugins

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.Base64

private val log = LoggerFactory.getLogger("app.menosan.auth")

/** Verifies ID tokens with the Firebase Admin SDK, using the service account from FIREBASE_SERVICE_ACCOUNT_JSON_B64. */
class FirebaseTokenVerifier(projectId: String, serviceAccountJsonB64: String) : TokenVerifier {
    val auth: FirebaseAuth

    init {
        val json = try {
            Base64.getMimeDecoder().decode(serviceAccountJsonB64.trim())
        } catch (_: IllegalArgumentException) {
            throw IllegalStateException("FIREBASE_SERVICE_ACCOUNT_JSON_B64 is not valid base64")
        }
        val options = FirebaseOptions.builder()
            .setCredentials(GoogleCredentials.fromStream(json.inputStream()))
            .setProjectId(projectId)
            .build()
        val app = FirebaseApp.getApps().firstOrNull { it.name == APP_NAME } ?: FirebaseApp.initializeApp(options, APP_NAME)
        auth = FirebaseAuth.getInstance(app)
    }

    override suspend fun verify(idToken: String): VerifiedToken? = withContext(Dispatchers.IO) {
        try {
            val token = auth.verifyIdToken(idToken)
            VerifiedToken(uid = token.uid, email = token.email, name = token.name)
        } catch (e: FirebaseAuthException) {
            log.info("ID token rejected: {}", e.authErrorCode) // never log the token itself
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private companion object {
        const val APP_NAME = "menosan"
    }
}
