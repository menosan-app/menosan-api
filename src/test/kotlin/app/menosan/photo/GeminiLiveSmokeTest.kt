package app.menosan.photo

import app.menosan.common.ApiException
import app.menosan.common.GeminiException
import app.menosan.common.GeminiImage
import app.menosan.common.GeminiRequest
import app.menosan.common.GenAiGeminiClient
import app.menosan.config.AppConfig
import app.menosan.config.DotEnv
import app.menosan.interventions.CostLevel
import app.menosan.interventions.Effort
import app.menosan.interventions.FakeLibrary
import app.menosan.interventions.InterventionType
import app.menosan.interventions.LibraryInterventionEngine
import app.menosan.interventions.input
import app.menosan.interventions.item
import app.menosan.taxonomy.Taxonomy
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Clock
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Live calls to Gemini with the key in `.env` / the environment. Skipped unless GEMINI_LIVE_TEST=true.
 *
 *   GEMINI_LIVE_TEST=true ./gradlew test --tests '*GeminiLiveSmokeTest*'
 *   GEMINI_LIVE_TEST=true PHOTO_SMOKE_DIR=/path/to/jpegs ./gradlew test --tests '*GeminiLiveSmokeTest*'
 *
 * Results are printed to the test's stdout (build/test-results/test/TEST-*GeminiLiveSmokeTest.xml).
 * Use real photos for docs/photo-smoke.md. Never commit the photos.
 */
@EnabledIfEnvironmentVariable(named = "GEMINI_LIVE_TEST", matches = "true")
class GeminiLiveSmokeTest {
    private val env = DotEnv.read(File(".env")) + System.getenv()
    private val model = env["GEMINI_MODEL"]?.takeIf { it.isNotBlank() } ?: AppConfig.DEFAULT_GEMINI_MODEL
    private val client = GenAiGeminiClient(
        requireNotNull(env["GEMINI_API_KEY"]?.takeIf { it.isNotBlank() }) { "GEMINI_API_KEY is not set" },
        model,
        // For latency experiments: SMOKE_THINKING_LEVEL=minimal|low|…, or "default" for the model's own default.
        when (val level = env["SMOKE_THINKING_LEVEL"]) {
            null, "" -> GenAiGeminiClient.defaultThinkingLevel(model)
            "default" -> null
            else -> level
        },
    )
    private val taxonomy = Taxonomy.loadDefault()

    @Test
    fun `photo analysis on a synthetic non-waste image and on PHOTO_SMOKE_DIR`() = runBlocking {
        val analyzer = GeminiPhotoAnalyzer(client, taxonomy, Clock.systemUTC())
        val photos = buildList {
            add("synthetic-blue-card.jpg" to syntheticJpeg())
            env["PHOTO_SMOKE_DIR"]?.let { dir ->
                File(dir).listFiles { f -> f.extension.lowercase() in setOf("jpg", "jpeg") }
                    ?.sortedBy { it.name }?.forEach { add(it.name to it.readBytes()) }
            }
        }
        for ((name, bytes) in photos) {
            val started = System.nanoTime()
            val outcome = try {
                analyzer.analyze(UUID.randomUUID(), bytes, "image/jpeg").toString()
            } catch (e: ApiException) {
                e.code.name
            }
            println("PHOTO-SMOKE | $name | ${bytes.size / 1024} KB | ${(System.nanoTime() - started) / 1_000_000} ms | $outcome")
        }
    }

    @Test
    fun `raw client call returns JSON`() = runBlocking {
        val request = GeminiRequest(
            systemInstruction = PhotoPrompt.systemInstruction(taxonomy),
            prompt = PhotoPrompt.USER_PROMPT,
            responseSchemaJson = PhotoPrompt.responseSchema(taxonomy),
            image = GeminiImage(syntheticJpeg(), "image/jpeg"),
            timeout = 15.seconds,
        )
        val raw = try {
            client.generateJson(request)
        } catch (e: GeminiException) {
            println("RAW-SMOKE | failed: ${e.message} | cause: ${generateSequence<Throwable>(e) { it.cause }.last().javaClass.name}")
            throw e
        }
        println("RAW-SMOKE | $raw")
    }

    @Test
    fun `intervention selection gets a valid Gemini answer`() = runBlocking {
        val library = listOf(
            item(1, CostLevel.SAVES_MONEY, Effort.LOW, InterventionType.REDUCE),
            item(2, CostLevel.FREE, Effort.LOW, InterventionType.PREVENT),
            item(3, CostLevel.SAVES_MONEY, Effort.MEDIUM, InterventionType.REDUCE),
            item(4, CostLevel.FREE, Effort.LOW, InterventionType.REUSE),
        ).map { it.copy(title = "Refill station for shampoo #${it.code.takeLast(1)}", description = "Bring an old bottle to a refill station.") }
        val engine = LibraryInterventionEngine(FakeLibrary(library), client, taxonomy)
        val started = System.nanoTime()
        val picks = engine.recommend(input())
        println("SELECTION-SMOKE | ${(System.nanoTime() - started) / 1_000_000} ms | $picks")
        assertTrue(picks.isNotEmpty())
    }

    /** A plain card with text on it: clearly not household waste. */
    private fun syntheticJpeg(): ByteArray {
        val image = BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB)
        with(image.createGraphics()) {
            color = Color(40, 90, 200)
            fillRect(0, 0, 640, 480)
            color = Color.WHITE
            font = Font(Font.SANS_SERIF, Font.BOLD, 48)
            drawString("Hello, Menosan", 120, 250)
            dispose()
        }
        return ByteArrayOutputStream().also { ImageIO.write(image, "jpg", it) }.toByteArray()
    }
}
