package app.menosan

import app.menosan.account.AccountDeletionService
import app.menosan.account.ExposedUserRepository
import app.menosan.account.FirebaseAdminUsers
import app.menosan.account.createAccountRoute
import app.menosan.account.deleteAccountRoute
import app.menosan.account.meRoutes
import app.menosan.common.GeminiClient
import app.menosan.common.OverridableClock
import app.menosan.common.StubGeminiClient
import app.menosan.common.weekRoutes
import app.menosan.config.AppConfig
import app.menosan.config.ConfigException
import app.menosan.db.Db
import app.menosan.db.JdbcHealthCheck
import app.menosan.db.createDataSource
import app.menosan.db.migrate
import app.menosan.dev.DevTools
import app.menosan.dev.devRoutes
import app.menosan.entries.EntryService
import app.menosan.entries.ExposedEntryRepository
import app.menosan.entries.entryRoutes
import app.menosan.export.ExposedDataExporter
import app.menosan.export.exportRoutes
import app.menosan.interventions.ExposedAdoptionService
import app.menosan.interventions.ExposedInterventionRepository
import app.menosan.interventions.InterventionEngine
import app.menosan.interventions.LibraryInterventionEngine
import app.menosan.interventions.ReportServiceResponder
import app.menosan.interventions.adoptionRoutes
import app.menosan.jobs.startWeeklyReportScheduler
import app.menosan.jobs.weeklyReportJobRoute
import app.menosan.plugins.FirebaseTokenVerifier
import app.menosan.plugins.authenticated
import app.menosan.plugins.configureCallLogging
import app.menosan.plugins.configureRequestIds
import app.menosan.plugins.configureSerialization
import app.menosan.plugins.configureStatusPages
import app.menosan.plugins.notFoundFallback
import app.menosan.reports.DefaultReportService
import app.menosan.reports.ReportStore
import app.menosan.reports.reportRoutes
import app.menosan.taxonomy.Taxonomy
import app.menosan.taxonomy.taxonomyRoutes
import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

private val log = LoggerFactory.getLogger("app.menosan.Application")

fun main() {
    val config = try {
        AppConfig.load()
    } catch (e: ConfigException) {
        log.error("Configuration error: {}", e.message)
        exitProcess(1)
    }
    config.warnings.forEach { log.warn(it) }
    log.info("Starting menosan-api with {}", config)

    val clock = OverridableClock().apply { setOverride(config.clockOverride) }
    if (clock.isOverridden) log.warn("Clock override active: now = {}", clock.instant())

    migrate(config)
    val dataSource = createDataSource(config)
    val db = Db.connect(dataSource)
    val taxonomy = Taxonomy.loadDefault()
    val gemini: GeminiClient = StubGeminiClient // BE-2 replaces this with the real client
    val interventions: InterventionEngine = LibraryInterventionEngine(ExposedInterventionRepository(db), gemini, taxonomy)
    val tokenVerifier = FirebaseTokenVerifier(config.firebaseProjectId, config.firebaseServiceAccountJsonB64)
    val entries = ExposedEntryRepository(db)
    val reports = DefaultReportService(ReportStore(db), clock, taxonomy, interventions)
    if (config.devToolsEnabled) log.warn("Dev tools are enabled at /internal/dev (staging only)")

    val deps = AppDeps(
        config = config,
        clock = clock,
        taxonomy = taxonomy,
        dbHealth = JdbcHealthCheck(dataSource),
        tokenVerifier = tokenVerifier,
        users = ExposedUserRepository(db),
        entries = entries,
        accountDeletion = AccountDeletionService(db, FirebaseAdminUsers(tokenVerifier.auth)),
        exporter = ExposedDataExporter(db, clock),
        reports = reports,
        interventions = interventions,
        gemini = gemini,
        adoptions = ExposedAdoptionService(db, clock),
        devTools = if (config.devToolsEnabled) DevTools(db, clock, taxonomy, entries, reports) else null,
    )

    val server = embeddedServer(Netty, port = config.port, host = "0.0.0.0") {
        module(deps)
        startWeeklyReportScheduler(deps.clock, deps.reports)
    }
    Runtime.getRuntime().addShutdownHook(Thread { dataSource.close() })
    server.start(wait = true)
}

fun Application.module(deps: AppDeps) {
    configureSerialization()
    configureRequestIds()
    configureCallLogging()
    configureStatusPages()

    val entryService = EntryService(deps.entries, deps.taxonomy, deps.clock, deps.reports)

    routing {
        healthRoutes(deps.dbHealth)
        weeklyReportJobRoute(deps.config.jobKey, deps.reports, deps.clock)
        if (deps.config.devToolsEnabled) deps.devTools?.let { devRoutes(deps.config.jobKey, it) }
        route("/v1") {
            taxonomyRoutes(deps.taxonomy)
            authenticated(deps.tokenVerifier, deps.users, requireAccount = false) {
                createAccountRoute(deps.users, deps.clock)
                deleteAccountRoute(deps.accountDeletion)
            }
            authenticated(deps.tokenVerifier, deps.users) {
                meRoutes()
                weekRoutes(deps.clock)
                entryRoutes(entryService, deps.clock)
                exportRoutes(deps.exporter, deps.reports, deps.clock)
                reportRoutes(deps.reports)
                adoptionRoutes(deps.adoptions, deps.reportResponder ?: ReportServiceResponder(deps.reports))
            }
        }
        notFoundFallback()
    }
}
