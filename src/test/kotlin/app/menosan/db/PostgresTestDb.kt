package app.menosan.db

import app.menosan.config.AppConfig
import com.zaxxer.hikari.HikariDataSource
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres

/**
 * One embedded PostgreSQL 17 for the whole test run, migrated with the real Flyway setup.
 * Tests that use it must create their own rows and must not rely on the database being empty.
 */
object PostgresTestDb {
    private val postgres: EmbeddedPostgres by lazy {
        EmbeddedPostgres.start().also { pg -> Runtime.getRuntime().addShutdownHook(Thread { pg.close() }) }
    }

    val config: AppConfig by lazy {
        val url = postgres.getJdbcUrl("postgres", "postgres")
        AppConfig.from(
            mapOf(
                "DATABASE_URL" to url,
                "DATABASE_URL_DIRECT" to url,
                "FIREBASE_PROJECT_ID" to "menosan-test",
                "FIREBASE_SERVICE_ACCOUNT_JSON_B64" to "e30=",
            ),
        ).also { migrate(it) }
    }

    val dataSource: HikariDataSource by lazy { createDataSource(config) }

    val db: Db by lazy { Db.connect(dataSource) }
}
