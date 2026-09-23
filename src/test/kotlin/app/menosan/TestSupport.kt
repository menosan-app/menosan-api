package app.menosan

import app.menosan.account.User
import app.menosan.account.UserRepository
import app.menosan.config.AppConfig
import app.menosan.db.DbHealthCheck
import app.menosan.photo.PhotoAnalyzer
import app.menosan.photo.StubPhotoAnalyzer
import app.menosan.plugins.TokenVerifier
import app.menosan.plugins.VerifiedToken
import app.menosan.taxonomy.Taxonomy
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Accepts only the tokens it was given. */
class FakeTokenVerifier(private val tokens: Map<String, VerifiedToken>) : TokenVerifier {
    override suspend fun verify(idToken: String): VerifiedToken? = tokens[idToken]
}

class InMemoryUserRepository : UserRepository {
    val users = ConcurrentHashMap<String, User>()

    override suspend fun findByFirebaseUid(firebaseUid: String): User? = users[firebaseUid]

    override suspend fun createIfAbsent(firebaseUid: String, email: String, displayName: String?, now: Instant): Pair<User, Boolean> {
        var created = false
        val user = users.computeIfAbsent(firebaseUid) {
            created = true
            User(UUID.randomUUID(), firebaseUid, email, displayName, now)
        }
        return user to created
    }
}

val testConfig: AppConfig = AppConfig.from(
    mapOf(
        "DATABASE_URL" to "jdbc:postgresql://localhost/test",
        "DATABASE_URL_DIRECT" to "jdbc:postgresql://localhost/test",
        "FIREBASE_PROJECT_ID" to "menosan-test",
        "FIREBASE_SERVICE_ACCOUNT_JSON_B64" to "e30=",
    ),
)

const val ALICE_TOKEN = "token-alice"
const val BOB_TOKEN = "token-bob"
const val ALICE_EMAIL = "alice@example.com"

fun fixedClock(iso: String): Clock = Clock.fixed(Instant.parse(iso), ZoneOffset.UTC)

fun testDeps(
    clock: Clock = fixedClock("2026-09-30T04:00:00Z"),
    users: UserRepository = InMemoryUserRepository(),
    dbHealthy: Boolean = true,
    photoAnalyzer: PhotoAnalyzer = StubPhotoAnalyzer,
) = AppDeps(
    config = testConfig,
    clock = clock,
    taxonomy = Taxonomy.loadDefault(),
    dbHealth = DbHealthCheck { dbHealthy },
    tokenVerifier = FakeTokenVerifier(
        mapOf(
            ALICE_TOKEN to VerifiedToken("uid-alice", ALICE_EMAIL, "Alice"),
            BOB_TOKEN to VerifiedToken("uid-bob", "bob@example.com", null),
        ),
    ),
    users = users,
    photoAnalyzer = photoAnalyzer,
)

fun menosanTest(deps: AppDeps = testDeps(), block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
    application { module(deps) }
    block()
}

suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

suspend fun HttpResponse.errorCode(): String? =
    json()["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content
