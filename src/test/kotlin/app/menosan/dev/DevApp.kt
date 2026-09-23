package app.menosan.dev

import app.menosan.AppDeps
import app.menosan.FakeTokenVerifier
import app.menosan.account.ExposedUserRepository
import app.menosan.common.OverridableClock
import app.menosan.common.StubGeminiClient
import app.menosan.config.AppConfig
import app.menosan.db.DbHealthCheck
import app.menosan.db.PostgresTestDb
import app.menosan.entries.ExposedEntryRepository
import app.menosan.interventions.ExposedAdoptionService
import app.menosan.interventions.ExposedInterventionRepository
import app.menosan.interventions.LibraryInterventionEngine
import app.menosan.jobs.JOB_KEY_HEADER
import app.menosan.module
import app.menosan.plugins.VerifiedToken
import app.menosan.reports.DefaultReportService
import app.menosan.reports.ReportStore
import app.menosan.taxonomy.Taxonomy
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Instant
import java.util.UUID

const val DEV_JOB_KEY = "dev-job-key"

/**
 * The app as `main()` wires it on staging, on the shared embedded Postgres: real entries, reports, the V3
 * library with the rules engine (Gemini is the stub, so it always falls back), adoption, and dev tools.
 * Each instance has its own signed-in user (no account yet) and its own clock.
 */
class DevApp(
    now: Instant,
    devToolsEnabled: Boolean = true,
    jobKey: String? = DEV_JOB_KEY,
    appEnv: String = "staging",
) {
    val db = PostgresTestDb.db
    val clock = OverridableClock().apply { setOverride(now) }
    val taxonomy: Taxonomy = Taxonomy.loadDefault()
    private val suffix = UUID.randomUUID().toString()
    val token = "token-dev-$suffix"
    val otherToken = "token-dev-other-$suffix"

    /** Mixed case on purpose: dev tools look emails up case-insensitively. */
    val email = "Tester-$suffix@Example.com"

    private val entries = ExposedEntryRepository(db)
    private val engine = LibraryInterventionEngine(ExposedInterventionRepository(db), StubGeminiClient, taxonomy)
    private val reports = DefaultReportService(ReportStore(db), clock, taxonomy, engine)

    val deps = AppDeps(
        config = AppConfig.from(
            buildMap {
                put("APP_ENV", appEnv)
                put("DATABASE_URL", "jdbc:postgresql://localhost/test")
                put("DATABASE_URL_DIRECT", "jdbc:postgresql://localhost/test")
                put("FIREBASE_PROJECT_ID", "menosan-test")
                put("FIREBASE_SERVICE_ACCOUNT_JSON_B64", "e30=")
                put("DEV_TOOLS_ENABLED", devToolsEnabled.toString())
                jobKey?.let { put("JOB_KEY", it) }
            },
        ),
        clock = clock,
        taxonomy = taxonomy,
        dbHealth = DbHealthCheck { true },
        tokenVerifier = FakeTokenVerifier(
            mapOf(
                token to VerifiedToken("uid-dev-$suffix", email, "Tester"),
                otherToken to VerifiedToken("uid-dev-other-$suffix", "other-$suffix@example.com", null),
            ),
        ),
        users = ExposedUserRepository(db),
        entries = entries,
        reports = reports,
        interventions = engine,
        adoptions = ExposedAdoptionService(db, clock),
        // Built even when disabled, to prove the config flag alone keeps the routes unmounted.
        devTools = DevTools(db, clock, taxonomy, entries, reports),
    )

    fun test(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { module(deps) }
        block()
    }
}

suspend fun ApplicationTestBuilder.devPost(path: String, body: String? = null, key: String? = DEV_JOB_KEY): HttpResponse =
    client.post("/internal/dev$path") {
        key?.let { header(JOB_KEY_HEADER, it) }
        if (body != null) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    }
