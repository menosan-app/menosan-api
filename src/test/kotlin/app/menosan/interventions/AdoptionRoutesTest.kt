package app.menosan.interventions

import app.menosan.AppDeps
import app.menosan.FakeTokenVerifier
import app.menosan.account.ExposedUserRepository
import app.menosan.db.AdoptedInterventions
import app.menosan.db.DbHealthCheck
import app.menosan.db.PostgresTestDb
import app.menosan.errorCode
import app.menosan.fixedClock
import app.menosan.json
import app.menosan.module
import app.menosan.plugins.VerifiedToken
import app.menosan.taxonomy.Taxonomy
import app.menosan.testConfig
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.response.respondText
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/** Adoption endpoints (§6.3, I7) against real PostgreSQL. "Now" is Wed 2026-09-30 PHT, so the latest report is 2026-09-20. */
class AdoptionRoutesTest {
    private val db = PostgresTestDb.db
    private val clock = fixedClock("2026-09-30T04:00:00Z")
    private val fixtures = ReportFixtures(db)
    private val latest = LocalDate.parse("2026-09-20")
    private val older = LocalDate.parse("2026-09-13")

    // Fresh Firebase uids per test class instance, so tests never share rows in the shared database.
    private val aliceUid = "uid-alice-" + UUID.randomUUID()
    private val bobUid = "uid-bob-" + UUID.randomUUID()
    private val alice = runBlocking { fixtures.user(aliceUid) }
    private val bob = runBlocking { fixtures.user(bobUid) }

    private val deps = AppDeps(
        config = testConfig,
        clock = clock,
        taxonomy = Taxonomy.loadDefault(),
        dbHealth = DbHealthCheck { true },
        tokenVerifier = FakeTokenVerifier(
            mapOf("alice" to VerifiedToken(aliceUid, null, null), "bob" to VerifiedToken(bobUid, null, null)),
        ),
        users = ExposedUserRepository(db),
        adoptions = ExposedAdoptionService(db, clock),
        // No report service here (reports are inserted as bare rows), so respond with the adoption state only.
        // The full-report response is covered by AdoptionReportFlowTest.
        reportResponder = AdoptionStateResponder(ExposedAdoptionService(db, clock)),
    )

    private fun test(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { module(deps) }
        block()
    }

    private suspend fun ApplicationTestBuilder.adopt(week: LocalDate, body: String, token: String = "alice"): HttpResponse =
        client.post("/v1/reports/$week/adoptions") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun ApplicationTestBuilder.unadopt(week: LocalDate, interventionId: Any, token: String = "alice"): HttpResponse =
        client.delete("/v1/reports/$week/adoptions/$interventionId") { bearerAuth(token) }

    private fun ids(vararg ids: UUID) = ids.joinToString(",", """{"interventionIds":[""", "]}") { "\"$it\"" }

    private suspend fun HttpResponse.adoptedIds(): List<String> =
        json()["adoptedInterventionIds"]!!.jsonArray.map { it.jsonPrimitive.content }

    private suspend fun adoptionRows(userId: UUID) = db.tx {
        AdoptedInterventions.selectAll().where { AdoptedInterventions.userId eq userId }.map {
            Triple(it[AdoptedInterventions.targetSubcategory], it[AdoptedInterventions.baselineWeekStart], it[AdoptedInterventions.baselineQuantity])
        }
    }

    private suspend fun latestReport(userId: UUID = alice) = fixtures.report(
        userId, latest,
        hotspots = mapOf("RES_SACHETS" to 40, "RES_PLASTIC_BAGS" to 15),
        recommendations = mapOf(
            "RES_SACHETS" to listOf("RES_SACHETS_REFILL_STATION", "RES_SACHETS_TAKAL_COFFEE_SUGAR"),
            "RES_PLASTIC_BAGS" to listOf("RES_PLASTIC_BAGS_BRING_BAG"),
        ),
    )

    @Test
    fun `adopting stores the target subcategory and baseline, and returns the adoption state`() = test {
        latestReport()
        val refill = fixtures.interventionId("RES_SACHETS_REFILL_STATION")
        val bag = fixtures.interventionId("RES_PLASTIC_BAGS_BRING_BAG")

        val response = adopt(latest, ids(refill, bag))
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(listOf(refill, bag).map { it.toString() }.sorted(), response.adoptedIds())
        assertEquals(
            setOf(Triple("RES_SACHETS", latest, 40), Triple("RES_PLASTIC_BAGS", latest, 15)),
            adoptionRows(alice).toSet(),
        )
    }

    @Test
    fun `adoption is idempotent`() = test {
        latestReport()
        val refill = fixtures.interventionId("RES_SACHETS_REFILL_STATION")
        assertEquals(HttpStatusCode.OK, adopt(latest, ids(refill)).status)
        assertEquals(HttpStatusCode.OK, adopt(latest, ids(refill)).status)
        assertEquals(HttpStatusCode.OK, adopt(latest, """{"interventionIds":["$refill","$refill"]}""").status)
        assertEquals(1, adoptionRows(alice).size)
    }

    @Test
    fun `un-adopting removes the adoption and is idempotent`() = test {
        latestReport()
        val refill = fixtures.interventionId("RES_SACHETS_REFILL_STATION")
        val bag = fixtures.interventionId("RES_PLASTIC_BAGS_BRING_BAG")
        adopt(latest, ids(refill, bag))

        val response = unadopt(latest, refill)
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(listOf(bag.toString()), response.adoptedIds())
        assertEquals(HttpStatusCode.OK, unadopt(latest, refill).status)
        assertEquals(1, adoptionRows(alice).size)
    }

    @Test
    fun `adoption window closed returns 409 for adopt and un-adopt`() = test {
        val refillCode = "RES_SACHETS_REFILL_STATION"
        fixtures.report(alice, older, mapOf("RES_SACHETS" to 10), mapOf("RES_SACHETS" to listOf(refillCode)))
        val refill = fixtures.interventionId(refillCode)

        val response = adopt(older, ids(refill))
        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals("ADOPTION_WINDOW_CLOSED", response.errorCode())

        val removal = unadopt(older, refill)
        assertEquals(HttpStatusCode.Conflict, removal.status)
        assertEquals("ADOPTION_WINDOW_CLOSED", removal.errorCode())
        assertEquals(0, adoptionRows(alice).size)
    }

    @Test
    fun `a report for the current week is not adoptable either`() = runBlocking {
        // Only possible with a clock rollback, but the window rule must still hold.
        assertEquals(false, AdoptionWindow.isLatest(LocalDate.parse("2026-09-27"), clock))
        assertEquals(true, AdoptionWindow.isLatest(latest, clock))
        // Sat 2026-10-03 23:59:59 PHT is still inside the window; Sun 00:00 PHT is not.
        assertEquals(true, AdoptionWindow.isLatest(latest, fixedClock("2026-10-03T15:59:59Z")))
        assertEquals(false, AdoptionWindow.isLatest(latest, fixedClock("2026-10-03T16:00:00Z")))
    }

    @Test
    fun `an intervention that was not recommended on the report is rejected and nothing is adopted`() = test {
        latestReport()
        val refill = fixtures.interventionId("RES_SACHETS_REFILL_STATION")
        val notShown = fixtures.interventionId("RES_SACHETS_NEXT_SIZE_UP")
        for (body in listOf(ids(refill, notShown), ids(UUID.randomUUID()))) {
            val response = adopt(latest, body)
            assertEquals(HttpStatusCode.BadRequest, response.status, body)
            assertEquals("VALIDATION_FAILED", response.errorCode())
        }
        assertEquals(0, adoptionRows(alice).size)
    }

    @Test
    fun `another user's report is not found`() = test {
        latestReport(userId = bob)
        val refill = fixtures.interventionId("RES_SACHETS_REFILL_STATION")
        for (response in listOf(adopt(latest, ids(refill)), unadopt(latest, refill))) {
            assertEquals(HttpStatusCode.NotFound, response.status)
            assertEquals("NOT_FOUND", response.errorCode())
        }
        assertEquals(0, adoptionRows(bob).size)
    }

    @Test
    fun `a missing report is not found`() = test {
        val refill = fixtures.interventionId("RES_SACHETS_REFILL_STATION")
        val response = adopt(latest, ids(refill))
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("NOT_FOUND", response.errorCode())
    }

    @Test
    fun `malformed requests return 400 VALIDATION_FAILED`() = test {
        latestReport()
        val refill = fixtures.interventionId("RES_SACHETS_REFILL_STATION")
        val cases = listOf(
            adopt(LocalDate.parse("2026-09-21"), ids(refill)), // Monday
            client.post("/v1/reports/not-a-date/adoptions") {
                bearerAuth("alice"); contentType(ContentType.Application.Json); setBody(ids(refill))
            },
            adopt(latest, """{"interventionIds":[]}"""),
            adopt(latest, "{}"),
            adopt(latest, """{"interventionIds":["nope"]}"""),
            adopt(latest, ids(*Array(10) { UUID.randomUUID() })),
            unadopt(latest, "nope"),
        )
        for (response in cases) {
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("VALIDATION_FAILED", response.errorCode())
        }
    }

    @Test
    fun `adoption requires authentication`() = test {
        val response = client.post("/v1/reports/$latest/adoptions") {
            contentType(ContentType.Application.Json); setBody("""{"interventionIds":[]}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `a wired report responder renders the response`() = testApplication {
        latestReport()
        val refill = fixtures.interventionId("RES_SACHETS_REFILL_STATION")
        val withResponder = AppDeps(
            config = deps.config, clock = deps.clock, taxonomy = deps.taxonomy, dbHealth = deps.dbHealth,
            tokenVerifier = deps.tokenVerifier, users = deps.users, adoptions = deps.adoptions,
            reportResponder = ReportResponder { call, userId, weekStart ->
                call.respondText("""{"rendered":"$weekStart","self":"${userId == alice}"}""", ContentType.Application.Json)
            },
        )
        application { module(withResponder) }
        val body = adopt(latest, ids(refill)).json()
        assertEquals("2026-09-20", body["rendered"]!!.jsonPrimitive.content)
        assertEquals("true", body["self"]!!.jsonPrimitive.content)
    }
}
