package app.menosan.plugins

import app.menosan.ALICE_EMAIL
import app.menosan.ALICE_TOKEN
import app.menosan.menosanTest
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Logs must never contain tokens, emails, or request bodies (plan §7, §14). */
class LoggingPrivacyTest {
    @Test
    fun `request logs contain no token, email, or body`() {
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        root.addAppender(appender)
        try {
            menosanTest {
                client.post("/v1/account") {
                    bearerAuth(ALICE_TOKEN)
                    contentType(ContentType.Application.Json)
                    setBody("""{"consent":true,"secretField":"body-marker-xyz"}""")
                }
                client.get("/v1/me") { bearerAuth(ALICE_TOKEN) }
                client.get("/v1/me") { bearerAuth("forged-token-marker") }
            }
        } finally {
            root.detachAppender(appender)
        }

        val lines = appender.list.map { it.formattedMessage + " " + it.mdcPropertyMap }
        assertTrue(lines.any { it.contains("POST /v1/account -> 201") }, "call logging should record the request: $lines")
        for (secret in listOf(ALICE_TOKEN, ALICE_EMAIL, "forged-token-marker", "body-marker-xyz", "Alice")) {
            assertFalse(lines.any { it.contains(secret) }, "log leaked '$secret': $lines")
        }
    }
}
