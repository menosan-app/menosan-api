package app.menosan.config

import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppConfigTest {
    private val required = mapOf(
        "DATABASE_URL" to "jdbc:postgresql://pooled/db",
        "DATABASE_URL_DIRECT" to "jdbc:postgresql://direct/db",
        "FIREBASE_PROJECT_ID" to "menosan-test",
        "FIREBASE_SERVICE_ACCOUNT_JSON_B64" to "e30=",
    )

    @Test
    fun `dotenv strips inline comments and quotes`() {
        val parsed = DotEnv.parse(
            """
            # comment line
            APP_ENV=dev        # dev | staging | prod
            export PORT=9090
            QUOTED="a # not a comment"
            SINGLE='x=y'
            URL=jdbc:postgresql://h/db?sslmode=require&user=u
            EMPTY=
            EMPTY_WITH_COMMENT=             # ISO-8601 instant
            """.trimIndent(),
        )
        assertEquals("", parsed["EMPTY_WITH_COMMENT"])
        assertEquals("dev", parsed["APP_ENV"])
        assertEquals("9090", parsed["PORT"])
        assertEquals("a # not a comment", parsed["QUOTED"])
        assertEquals("x=y", parsed["SINGLE"])
        assertEquals("jdbc:postgresql://h/db?sslmode=require&user=u", parsed["URL"])
        assertEquals("", parsed["EMPTY"])
    }

    @Test
    fun `missing required variables are reported by name only`() {
        val e = assertFailsWith<ConfigException> { AppConfig.from(mapOf("DATABASE_URL" to "secret-url")) }
        assertTrue(e.message!!.contains("DATABASE_URL_DIRECT"))
        assertTrue(e.message!!.contains("FIREBASE_PROJECT_ID"))
        assertFalse(e.message!!.contains("secret-url"))
    }

    @Test
    fun `defaults apply when optional variables are absent`() {
        val config = AppConfig.from(required)
        assertEquals(AppEnv.DEV, config.appEnv)
        assertEquals(8080, config.port)
        assertEquals(AppConfig.DEFAULT_GEMINI_PHOTO_MODEL, config.geminiPhotoModel)
        assertEquals(AppConfig.DEFAULT_GEMINI_INTERVENTION_MODEL, config.geminiInterventionModel)
        assertEquals(AppConfig.DEFAULT_GEMINI_RPM, config.geminiRpm)
        assertEquals(AppConfig.DEFAULT_GEMINI_RPD, config.geminiRpd)
        assertFalse(config.devToolsEnabled)
        assertNull(config.clockOverride)
        assertNull(config.databaseUser)
    }

    @Test
    fun `valid clock override is parsed outside prod`() {
        val config = AppConfig.from(required + ("CLOCK_OVERRIDE" to "2026-10-04T00:05:00Z") + ("APP_ENV" to "staging"))
        assertEquals(Instant.parse("2026-10-04T00:05:00Z"), config.clockOverride)
    }

    @Test
    fun `invalid clock override is ignored with a warning`() {
        val config = AppConfig.from(required + ("CLOCK_OVERRIDE" to "true"))
        assertNull(config.clockOverride)
        assertEquals(1, config.warnings.size)
    }

    @Test
    fun `clock override and dev tools are ignored in prod`() {
        val config = AppConfig.from(
            required + mapOf("APP_ENV" to "prod", "CLOCK_OVERRIDE" to "2026-10-04T00:05:00Z", "DEV_TOOLS_ENABLED" to "true"),
        )
        assertNull(config.clockOverride)
        assertFalse(config.devToolsEnabled)
    }

    @Test
    fun `real environment wins over dotenv file`() {
        val file: File = Files.createTempFile("menosan", ".env").toFile().apply {
            writeText(required.entries.joinToString("\n") { "${it.key}=${it.value}" } + "\nPORT=1111\nGEMINI_PHOTO_MODEL=from-file\n")
            deleteOnExit()
        }
        val config = AppConfig.load(env = mapOf("PORT" to "2222"), dotEnvFile = file)
        assertEquals(2222, config.port)
        assertEquals("from-file", config.geminiPhotoModel)
    }

    @Test
    fun `dotenv file is not read in prod`() {
        val file: File = Files.createTempFile("menosan", ".env").toFile().apply {
            writeText(required.entries.joinToString("\n") { "${it.key}=${it.value}" })
            deleteOnExit()
        }
        assertFailsWith<ConfigException> { AppConfig.load(env = mapOf("APP_ENV" to "prod"), dotEnvFile = file) }
    }

    @Test
    fun `env example parses without warnings`() {
        val example = DotEnv.read(File(".env.example"))
        assertEquals("", example["CLOCK_OVERRIDE"])
        assertEquals("dev", example["APP_ENV"])
        assertTrue(AppConfig.from(example).warnings.isEmpty())
    }

    @Test
    fun `toString never prints secrets`() {
        val config = AppConfig.from(required + ("GEMINI_API_KEY" to "super-secret-key"))
        assertFalse(config.toString().contains("super-secret-key"))
        assertFalse(config.toString().contains("e30="))
        assertFalse(config.toString().contains("pooled"))
    }

    @Test
    fun `Gemini models and limits can be set per environment`() {
        val config = AppConfig.from(
            required + mapOf(
                "GEMINI_PHOTO_MODEL" to "photo-model",
                "GEMINI_INTERVENTION_MODEL" to "intervention-model",
                "GEMINI_RPM" to "5",
                "GEMINI_RPD" to "20",
            ),
        )
        assertEquals("photo-model", config.geminiPhotoModel)
        assertEquals("intervention-model", config.geminiInterventionModel)
        assertEquals(5, config.geminiRpm)
        assertEquals(20, config.geminiRpd)
    }

    @Test
    fun `Gemini limits must be positive whole numbers`() {
        for (bad in listOf("0", "-1", "ten", "2.5")) {
            val e = assertFailsWith<ConfigException> { AppConfig.from(required + ("GEMINI_RPM" to bad)) }
            assertEquals("GEMINI_RPM must be a positive whole number", e.message)
        }
        assertFailsWith<ConfigException> { AppConfig.from(required + ("GEMINI_RPD" to "0")) }
    }

    @Test
    fun `the old GEMINI_MODEL variable is ignored with a warning`() {
        val config = AppConfig.from(required + ("GEMINI_MODEL" to "gemini-3.6-flash"))
        assertEquals(AppConfig.DEFAULT_GEMINI_PHOTO_MODEL, config.geminiPhotoModel)
        assertTrue(config.warnings.any { it.startsWith("GEMINI_MODEL is no longer used") })
    }
}
