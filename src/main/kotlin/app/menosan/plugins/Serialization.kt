package app.menosan.plugins

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.temporal.ChronoUnit

val ApiJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = true
}

fun Application.configureSerialization() {
    install(ContentNegotiation) { json(ApiJson) }
}

fun Instant.toApiString(): String = truncatedTo(ChronoUnit.MILLIS).toString()
