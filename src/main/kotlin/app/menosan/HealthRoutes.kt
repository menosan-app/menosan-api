package app.menosan

import app.menosan.db.DbHealthCheck
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable

@Serializable
data class HealthResponse(val status: String, val db: String)

/** `GET /health`: liveness plus a DB check. No auth. 503 when the DB is unreachable. */
fun Route.healthRoutes(dbHealth: DbHealthCheck) {
    get("/health") {
        if (dbHealth.isHealthy()) {
            call.respond(HealthResponse(status = "ok", db = "ok"))
        } else {
            call.respond(HttpStatusCode.ServiceUnavailable, HealthResponse(status = "degraded", db = "down"))
        }
    }
}
