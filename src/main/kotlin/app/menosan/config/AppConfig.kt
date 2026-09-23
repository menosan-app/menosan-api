package app.menosan.config

import java.io.File
import java.time.Instant
import java.time.format.DateTimeParseException

enum class AppEnv { DEV, STAGING, PROD }

class ConfigException(message: String) : RuntimeException(message)

/** All runtime configuration, read from environment variables (plan §9). */
class AppConfig(
    val appEnv: AppEnv,
    val port: Int,
    val databaseUrl: String,
    val databaseUrlDirect: String,
    val databaseUser: String?,
    val databasePassword: String?,
    val firebaseProjectId: String,
    val firebaseServiceAccountJsonB64: String,
    val geminiApiKey: String?,
    val geminiModel: String,
    val jobKey: String?,
    val devToolsEnabled: Boolean,
    val clockOverride: Instant?,
    /** Non-fatal problems found while loading. They never contain secret values. */
    val warnings: List<String>,
) {
    override fun toString(): String =
        "AppConfig(appEnv=$appEnv, port=$port, firebaseProjectId=$firebaseProjectId, geminiModel=$geminiModel, " +
            "devToolsEnabled=$devToolsEnabled, clockOverride=$clockOverride, secrets=<redacted>)"

    companion object {
        val REQUIRED = listOf(
            "DATABASE_URL",
            "DATABASE_URL_DIRECT",
            "FIREBASE_PROJECT_ID",
            "FIREBASE_SERVICE_ACCOUNT_JSON_B64",
        )
        const val DEFAULT_GEMINI_MODEL = "gemini-3.6-flash"

        /** Real environment variables win over `.env`. `.env` is never read when APP_ENV=prod. */
        fun load(env: Map<String, String> = System.getenv(), dotEnvFile: File = File(".env")): AppConfig {
            val isProd = env["APP_ENV"]?.trim().equals("prod", ignoreCase = true)
            val merged = if (isProd) env else DotEnv.read(dotEnvFile) + env
            return from(merged)
        }

        fun from(env: Map<String, String>): AppConfig {
            fun opt(name: String): String? = env[name]?.trim()?.takeIf { it.isNotEmpty() }

            val missing = REQUIRED.filter { opt(it) == null }
            if (missing.isNotEmpty()) {
                throw ConfigException("Missing required environment variables: ${missing.joinToString()}")
            }

            val warnings = mutableListOf<String>()
            val appEnv = when (val raw = opt("APP_ENV")?.lowercase()) {
                null, "dev" -> AppEnv.DEV
                "staging" -> AppEnv.STAGING
                "prod" -> AppEnv.PROD
                else -> throw ConfigException("APP_ENV must be dev, staging, or prod (got '$raw')")
            }
            val port = opt("PORT")?.let { it.toIntOrNull() ?: throw ConfigException("PORT must be a number") } ?: 8080

            val devTools = opt("DEV_TOOLS_ENABLED").toBool()
            if (devTools && appEnv == AppEnv.PROD) warnings += "DEV_TOOLS_ENABLED is ignored in prod"

            var clockOverride: Instant? = null
            opt("CLOCK_OVERRIDE")?.let { raw ->
                if (appEnv == AppEnv.PROD) {
                    warnings += "CLOCK_OVERRIDE is ignored in prod"
                } else {
                    try {
                        clockOverride = Instant.parse(raw)
                    } catch (_: DateTimeParseException) {
                        warnings += "CLOCK_OVERRIDE is not an ISO-8601 instant (e.g. 2026-10-04T00:05:00Z); ignoring it"
                    }
                }
            }

            return AppConfig(
                appEnv = appEnv,
                port = port,
                databaseUrl = opt("DATABASE_URL")!!,
                databaseUrlDirect = opt("DATABASE_URL_DIRECT")!!,
                databaseUser = opt("DATABASE_USER"),
                databasePassword = opt("DATABASE_PASSWORD"),
                firebaseProjectId = opt("FIREBASE_PROJECT_ID")!!,
                firebaseServiceAccountJsonB64 = opt("FIREBASE_SERVICE_ACCOUNT_JSON_B64")!!,
                geminiApiKey = opt("GEMINI_API_KEY"),
                geminiModel = opt("GEMINI_MODEL") ?: DEFAULT_GEMINI_MODEL,
                jobKey = opt("JOB_KEY"),
                devToolsEnabled = devTools && appEnv != AppEnv.PROD,
                clockOverride = clockOverride,
                warnings = warnings,
            )
        }

        private fun String?.toBool(): Boolean = this?.lowercase() in setOf("true", "1", "yes")
    }
}
