package app.menosan.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.calllogging.processingTimeMillis
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.util.UUID

const val REQUEST_ID_HEADER = "X-Request-Id"

/** Accepts a sane client request id or generates one, and echoes it back in `X-Request-Id`. */
fun Application.configureRequestIds() {
    install(CallId) {
        retrieveFromHeader(REQUEST_ID_HEADER)
        generate { UUID.randomUUID().toString() }
        verify { id -> id.length in 1..64 && id.all { it.isLetterOrDigit() || it == '-' || it == '_' } }
        replyToHeader(REQUEST_ID_HEADER)
    }
}

/**
 * One line per request: method, path (no query string), status, duration, and request id (via MDC).
 * Never logs headers, bodies, tokens, or emails (plan §2, AGENTS.md).
 */
fun Application.configureCallLogging() {
    install(CallLogging) {
        logger = LoggerFactory.getLogger("app.menosan.http")
        level = Level.INFO
        callIdMdc("requestId")
        disableDefaultColors()
        filter { call -> call.request.path() != "/health" }
        format { call ->
            "${call.request.httpMethod.value} ${call.request.path()} -> ${call.response.status()?.value} " +
                "(${call.processingTimeMillis()} ms)"
        }
    }
}
