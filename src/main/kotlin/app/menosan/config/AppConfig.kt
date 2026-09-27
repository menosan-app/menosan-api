package app.menosan.config

class ConfigException(message: String) : RuntimeException(message)

class AppConfig(
    val port: Int,
    val databaseUrl: String,
    val databaseUrlDirect: String,
    val databaseUser: String?,
    val databasePassword: String?,
    val firebaseProjectId: String,
    val firebaseServiceAccountJsonB64: String,
    val geminiApiKey: String?,
    val geminiPhotoModel: String,
    val geminiInterventionModel: String,
    val geminiRpm: Int,
    val geminiRpd: Int,
    val jobKey: String?,
) {
    override fun toString(): String =
        "AppConfig(port=$port, firebaseProjectId=$firebaseProjectId, " +
            "geminiPhotoModel=$geminiPhotoModel, geminiInterventionModel=$geminiInterventionModel, " +
            "geminiRpm=$geminiRpm, geminiRpd=$geminiRpd, secrets=<redacted>)"

    companion object {
        val REQUIRED = listOf(
            "DATABASE_URL",
            "DATABASE_URL_DIRECT",
            "FIREBASE_PROJECT_ID",
            "FIREBASE_SERVICE_ACCOUNT_JSON_B64",
        )
        const val DEFAULT_GEMINI_PHOTO_MODEL = "gemini-3.5-flash-lite"
        const val DEFAULT_GEMINI_INTERVENTION_MODEL = "gemini-3.1-flash-lite"
        const val DEFAULT_GEMINI_RPM = 15
        const val DEFAULT_GEMINI_RPD = 500

        fun load(env: Map<String, String> = System.getenv()): AppConfig {
            fun opt(name: String): String? = env[name]?.trim()?.takeIf { it.isNotEmpty() }

            val missing = REQUIRED.filter { opt(it) == null }
            if (missing.isNotEmpty()) {
                throw ConfigException("Missing required environment variables: ${missing.joinToString()}")
            }

            val port = opt("PORT")?.let { it.toIntOrNull() ?: throw ConfigException("PORT must be a number") } ?: 8080

            fun positiveInt(name: String, default: Int): Int = opt(name)?.let {
                it.toIntOrNull()?.takeIf { n -> n > 0 } ?: throw ConfigException("$name must be a positive whole number")
            } ?: default

            return AppConfig(
                port = port,
                databaseUrl = opt("DATABASE_URL")!!,
                databaseUrlDirect = opt("DATABASE_URL_DIRECT")!!,
                databaseUser = opt("DATABASE_USER"),
                databasePassword = opt("DATABASE_PASSWORD"),
                firebaseProjectId = opt("FIREBASE_PROJECT_ID")!!,
                firebaseServiceAccountJsonB64 = opt("FIREBASE_SERVICE_ACCOUNT_JSON_B64")!!,
                geminiApiKey = opt("GEMINI_API_KEY"),
                geminiPhotoModel = opt("GEMINI_PHOTO_MODEL") ?: DEFAULT_GEMINI_PHOTO_MODEL,
                geminiInterventionModel = opt("GEMINI_INTERVENTION_MODEL") ?: DEFAULT_GEMINI_INTERVENTION_MODEL,
                geminiRpm = positiveInt("GEMINI_RPM", DEFAULT_GEMINI_RPM),
                geminiRpd = positiveInt("GEMINI_RPD", DEFAULT_GEMINI_RPD),
                jobKey = opt("JOB_KEY"),
            )
        }
    }
}
