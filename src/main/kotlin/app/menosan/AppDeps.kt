package app.menosan

import app.menosan.account.AccountDeletion
import app.menosan.account.StubAccountDeletion
import app.menosan.account.UserRepository
import app.menosan.common.GeminiClient
import app.menosan.common.StubGeminiClient
import app.menosan.config.AppConfig
import app.menosan.db.DbHealthCheck
import app.menosan.dev.DevTools
import app.menosan.entries.EntryRepository
import app.menosan.entries.StubEntryRepository
import app.menosan.export.DataExporter
import app.menosan.export.StubDataExporter
import app.menosan.interventions.AdoptionService
import app.menosan.interventions.InterventionEngine
import app.menosan.interventions.ReportResponder
import app.menosan.interventions.StubAdoptionService
import app.menosan.interventions.StubInterventionEngine
import app.menosan.photo.PhotoAnalyzer
import app.menosan.photo.StubPhotoAnalyzer
import app.menosan.plugins.TokenVerifier
import app.menosan.reports.ReportService
import app.menosan.reports.StubReportService
import app.menosan.taxonomy.Taxonomy
import java.time.Clock

/**
 * Manual dependency wiring. `main()` builds the real graph; tests pass fakes.
 * Workstreams replace their stub default here when they land.
 */
class AppDeps(
    val config: AppConfig,
    val clock: Clock,
    val taxonomy: Taxonomy,
    val dbHealth: DbHealthCheck,
    val tokenVerifier: TokenVerifier,
    val users: UserRepository,
    val entries: EntryRepository = StubEntryRepository,
    val accountDeletion: AccountDeletion = StubAccountDeletion,
    val exporter: DataExporter = StubDataExporter,
    val photoAnalyzer: PhotoAnalyzer = StubPhotoAnalyzer,
    val reports: ReportService = StubReportService,
    val interventions: InterventionEngine = StubInterventionEngine,
    val gemini: GeminiClient = StubGeminiClient,
    val adoptions: AdoptionService = StubAdoptionService,
    /** Response after an adoption change. Null → the full report from [reports] (`ReportServiceResponder`). */
    val reportResponder: ReportResponder? = null,
    /** `/internal/dev/...` (BE-5). Mounted only when this is set **and** `config.devToolsEnabled` (never in prod). */
    val devTools: DevTools? = null,
)
