package app.menosan.plugins

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.temporal.ChronoUnit

/** JSON settings for the whole API (§8.1: camelCase, explicit nulls). */
val ApiJson = Json {
    ignoreUnknownKeys = true // tolerate additive client fields
    encodeDefaults = true
    explicitNulls = true
}

fun Application.configureSerialization() {
    install(ContentNegotiation) { json(ApiJson) }
}

/** ISO-8601 UTC with millisecond precision, e.g. `2026-09-27T02:15:00Z` (§8.1). */
fun Instant.toApiString(): String = truncatedTo(ChronoUnit.MILLIS).toString()
