package app.menosan.db

import app.menosan.config.AppConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import javax.sql.DataSource

private val log = LoggerFactory.getLogger("app.menosan.db")

fun migrate(config: AppConfig) {
    val result = Flyway.configure()
        .dataSource(config.databaseUrlDirect, config.databaseUser, config.databasePassword)
        .locations("classpath:db/migration")
        .connectRetries(3)
        .load()
        .migrate()
    log.info("Flyway: {} migration(s) applied, schema version {}", result.migrationsExecuted, result.targetSchemaVersion)
}

fun createDataSource(config: AppConfig): HikariDataSource {
    val hikari = HikariConfig().apply {
        jdbcUrl = config.databaseUrl
        config.databaseUser?.let { username = it }
        config.databasePassword?.let { password = it }
        poolName = "menosan"
        maximumPoolSize = 5
        minimumIdle = 0
        connectionTimeout = 10_000
        idleTimeout = 60_000
        maxLifetime = 300_000
        initializationFailTimeout = -1
        isAutoCommit = false
        transactionIsolation = "TRANSACTION_READ_COMMITTED"
    }
    return HikariDataSource(hikari)
}

class Db(val database: Database) {
    suspend fun <T> tx(block: JdbcTransaction.() -> T): T =
        withContext(Dispatchers.IO) { transaction(database) { block() } }

    companion object {
        fun connect(dataSource: DataSource): Db = Db(Database.connect(dataSource))
    }
}

fun interface DbHealthCheck {
    suspend fun isHealthy(): Boolean
}

class JdbcHealthCheck(private val dataSource: DataSource) : DbHealthCheck {
    override suspend fun isHealthy(): Boolean = withContext(Dispatchers.IO) {
        try {
            dataSource.connection.use { it.isValid(3) }
        } catch (e: Exception) {
            log.warn("DB health check failed: {}", e.javaClass.simpleName)
            false
        }
    }
}
