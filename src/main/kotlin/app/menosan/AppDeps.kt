package app.menosan

import app.menosan.account.AccountDeletion
import app.menosan.account.UserRepository
import app.menosan.config.AppConfig
import app.menosan.db.DbHealthCheck
import app.menosan.entries.EntryRepository
import app.menosan.export.DataExporter
import app.menosan.interventions.AdoptionService
import app.menosan.photo.PhotoAnalyzer
import app.menosan.plugins.TokenVerifier
import app.menosan.reports.ReportService
import app.menosan.taxonomy.Taxonomy
import java.time.Clock

class AppDeps(
    val config: AppConfig,
    val clock: Clock,
    val taxonomy: Taxonomy,
    val dbHealth: DbHealthCheck,
    val tokenVerifier: TokenVerifier,
    val users: UserRepository,
    val entries: EntryRepository,
    val accountDeletion: AccountDeletion,
    val exporter: DataExporter,
    val photoAnalyzer: PhotoAnalyzer,
    val reports: ReportService,
    val adoptions: AdoptionService,
)
