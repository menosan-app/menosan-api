package app.menosan.account

import app.menosan.PREVIOUS_WEEK
import app.menosan.createAccount
import app.menosan.db.AdoptedInterventions
import app.menosan.db.Hotspots
import app.menosan.db.InterventionImpacts
import app.menosan.db.PostgresTestDb
import app.menosan.db.ReportRecommendations
import app.menosan.db.Users
import app.menosan.db.WasteEntries
import app.menosan.db.WeeklyReports
import app.menosan.dbTest
import app.menosan.deleteTestIntervention
import app.menosan.entryBody
import app.menosan.errorCode
import app.menosan.insertTestIntervention
import app.menosan.putEntry
import app.menosan.seedReportGraph
import com.google.firebase.ErrorCode
import com.google.firebase.auth.AuthErrorCode
import com.google.firebase.auth.FirebaseAuthException
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AccountDeletionTest {
    private val db = PostgresTestDb.db

    /** Row counts of every table that holds data of [userId], directly or through its reports. */
    private suspend fun rowsOf(userId: UUID): Map<String, Long> = db.tx {
        val reportIds = WeeklyReports.selectAll().where { WeeklyReports.userId eq userId }.map { it[WeeklyReports.id] }
        val hotspotIds = Hotspots.selectAll().where { Hotspots.reportId inList reportIds }.map { it[Hotspots.id] }
        val adoptionIds = AdoptedInterventions.selectAll().where { AdoptedInterventions.userId eq userId }.map { it[AdoptedInterventions.id] }
        mapOf(
            "users" to Users.selectAll().where { Users.id eq userId }.count(),
            "waste_entries" to WasteEntries.selectAll().where { WasteEntries.userId eq userId }.count(),
            "weekly_reports" to reportIds.size.toLong(),
            "hotspots" to hotspotIds.size.toLong(),
            "report_recommendations" to ReportRecommendations.selectAll().where { ReportRecommendations.hotspotId inList hotspotIds }.count(),
            "adopted_interventions" to adoptionIds.size.toLong(),
            "intervention_impacts" to InterventionImpacts.selectAll().where { InterventionImpacts.adoptionId inList adoptionIds }.count(),
        )
    }

    @Test
    fun `deleting the account removes every row and the Firebase user, and nothing of other users`() {
        val interventionId = runBlocking { insertTestIntervention() }
        try {
            dbTest { env ->
                val aliceId = createAccount(env.aliceToken)
                val bobId = createAccount(env.bobToken)
                for (token in listOf(env.aliceToken, env.bobToken)) {
                    putEntry(token, UUID.randomUUID(), entryBody())
                    putEntry(token, UUID.randomUUID(), entryBody(createdAt = "2026-09-22T02:00:00Z"))
                }
                seedReportGraph(aliceId, interventionId, PREVIOUS_WEEK)
                seedReportGraph(bobId, interventionId, PREVIOUS_WEEK)
                assertEquals(
                    mapOf(
                        "users" to 1L, "waste_entries" to 2L, "weekly_reports" to 1L, "hotspots" to 1L,
                        "report_recommendations" to 1L, "adopted_interventions" to 1L, "intervention_impacts" to 1L,
                    ),
                    rowsOf(aliceId),
                )
                val bobBefore = rowsOf(bobId)

                val response = client.delete("/v1/account") { bearerAuth(env.aliceToken) }
                assertEquals(HttpStatusCode.NoContent, response.status)

                assertEquals(rowsOf(aliceId).mapValues { 0L }, rowsOf(aliceId))
                assertEquals(bobBefore, rowsOf(bobId))
                assertEquals(listOf(env.aliceUid), env.firebase.deleted.toList())

                val me = client.get("/v1/me") { bearerAuth(env.aliceToken) }
                assertEquals("ACCOUNT_NOT_FOUND", me.errorCode())

                // Signing up again starts from scratch.
                val newId = createAccount(env.aliceToken)
                assertEquals(0L, rowsOf(newId)["waste_entries"])
                client.delete("/v1/account") { bearerAuth(env.aliceToken) }
                client.delete("/v1/account") { bearerAuth(env.bobToken) }
            }
        } finally {
            runBlocking { deleteTestIntervention(interventionId) }
        }
    }

    @Test
    fun `deletion is idempotent - an already deleted account still returns 204`() = dbTest { env ->
        createAccount(env.aliceToken)
        assertEquals(HttpStatusCode.NoContent, client.delete("/v1/account") { bearerAuth(env.aliceToken) }.status)
        assertEquals(HttpStatusCode.NoContent, client.delete("/v1/account") { bearerAuth(env.aliceToken) }.status)
        // A signed-in user who never created an account can still remove their Firebase user.
        assertEquals(HttpStatusCode.NoContent, client.delete("/v1/account") { bearerAuth(env.noAccountToken) }.status)
        assertEquals(3, env.firebase.deleted.size)
    }

    @Test
    fun `a Firebase failure returns 500 and a retry finishes the deletion`() = dbTest { env ->
        val aliceId = createAccount(env.aliceToken)
        putEntry(env.aliceToken, UUID.randomUUID(), entryBody())
        env.firebase.failNext = true

        val failed = client.delete("/v1/account") { bearerAuth(env.aliceToken) }
        assertEquals(HttpStatusCode.InternalServerError, failed.status)
        assertEquals("INTERNAL", failed.errorCode())
        assertEquals(0L, rowsOf(aliceId)["users"], "database rows are deleted first")
        assertEquals(0L, rowsOf(aliceId)["waste_entries"])

        assertEquals(HttpStatusCode.NoContent, client.delete("/v1/account") { bearerAuth(env.aliceToken) }.status)
        assertEquals(listOf(env.aliceUid), env.firebase.deleted.toList())
    }

    @Test
    fun `deletion requires a valid token`() = dbTest { env ->
        createAccount(env.aliceToken)
        assertEquals(HttpStatusCode.Unauthorized, client.delete("/v1/account").status)
        assertEquals(HttpStatusCode.Unauthorized, client.delete("/v1/account") { bearerAuth("forged") }.status)
        assertEquals(emptyList(), env.firebase.deleted.toList())
    }

    @Test
    fun `Firebase USER_NOT_FOUND counts as deleted, other errors propagate`() = runBlocking {
        fun authError(code: AuthErrorCode) = FirebaseAuthException(ErrorCode.NOT_FOUND, "x", null, null, code)

        FirebaseAdminUsers { throw authError(AuthErrorCode.USER_NOT_FOUND) }.deleteUser("uid")

        val other = assertFailsWith<FirebaseAuthException> {
            FirebaseAdminUsers { throw authError(AuthErrorCode.CONFIGURATION_NOT_FOUND) }.deleteUser("uid")
        }
        assertEquals(AuthErrorCode.CONFIGURATION_NOT_FOUND, other.authErrorCode)

        val calls = mutableListOf<String>()
        FirebaseAdminUsers { calls += it }.deleteUser("uid-1")
        assertEquals(listOf("uid-1"), calls)
    }
}
