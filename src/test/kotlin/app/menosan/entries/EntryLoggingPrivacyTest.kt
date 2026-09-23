package app.menosan.entries

import app.menosan.createAccount
import app.menosan.dbTest
import app.menosan.entryBody
import app.menosan.jsonId
import app.menosan.listEntries
import app.menosan.putEntry
import app.menosan.sync
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Entry names, emails, and tokens never reach the logs (plan §14), also on error paths. */
class EntryLoggingPrivacyTest {
    @Test
    fun `entry, sync, export, and deletion logs contain no names, emails, or tokens`() {
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        root.addAppender(appender)
        val marker = "name-marker-q7"
        lateinit var secrets: List<String>
        try {
            dbTest { env ->
                env.reports.failLateEntry = true // exercise the error log path too
                secrets = listOf(env.aliceToken, "alice-", "@example.com", marker)
                createAccount(env.aliceToken)
                val id = UUID.randomUUID()
                putEntry(env.aliceToken, id, entryBody(name = marker))
                putEntry(env.aliceToken, UUID.randomUUID(), entryBody(name = marker, createdAt = "2026-09-22T02:00:00Z"))
                putEntry(env.aliceToken, UUID.randomUUID(), entryBody(name = marker, quantity = 0))
                sync(env.aliceToken, listOf(entryBody(id = id, name = "$marker-2")), listOf(jsonId(id)))
                listEntries(env.aliceToken)
                client.get("/v1/export") { bearerAuth(env.aliceToken) }
                client.delete("/v1/account") { bearerAuth(env.aliceToken) }
            }
        } finally {
            root.detachAppender(appender)
        }

        val lines = appender.list.map { it.formattedMessage + " " + it.mdcPropertyMap + " " + (it.throwableProxy?.message ?: "") }
        assertTrue(lines.any { it.contains("PUT /v1/entries/") }, "call logging should record the request: $lines")
        assertTrue(lines.any { it.contains("onLateEntry failed") }, "late-entry failure should be logged: $lines")
        for (secret in secrets) {
            assertFalse(lines.any { it.contains(secret) }, "log leaked '$secret': $lines")
        }
    }
}
