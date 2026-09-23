package app.menosan.e2e

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.time.Instant
import kotlin.test.Test

/**
 * [EndToEndScenario] against a deployed staging API. Skipped unless `E2E_BASE_URL` is set; run it with
 * `scripts/e2e-staging.sh` (see docs/ENVIRONMENTS.md). Needs `E2E_ID_TOKEN` (a Firebase ID token for a
 * **throwaway** account; all its entries and reports are deleted) and `E2E_JOB_KEY` (staging's JOB_KEY).
 */
@EnabledIfEnvironmentVariable(named = "E2E_BASE_URL", matches = ".+")
class StagingE2eTest {

    @Test
    fun `full loop on staging`() = runBlocking {
        fun env(name: String) = System.getenv(name)?.takeIf { it.isNotBlank() } ?: error("$name is not set")
        val scenario = EndToEndScenario(
            api = JdkHttpDriver(env("E2E_BASE_URL")),
            token = env("E2E_ID_TOKEN"),
            jobKey = env("E2E_JOB_KEY"),
            log = { println("[e2e] $it") },
        )
        scenario.run(EndToEndScenario.weekAStartFor(Instant.now()))
    }
}
