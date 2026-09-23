package app.menosan.common

import app.menosan.plugins.toApiString
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import java.time.Clock

@Serializable
data class CurrentWeekResponse(val weekStart: String, val weekEnd: String, val timezone: String, val serverNow: String)

/** `GET /v1/weeks/current` (SFR10.3). Mount inside `authenticated { }`. */
fun Route.weekRoutes(clock: Clock) {
    get("/weeks/current") {
        val now = clock.instant()
        val weekStart = WeekCalc.weekStart(now)
        call.respond(
            CurrentWeekResponse(
                weekStart = weekStart.toString(),
                weekEnd = WeekCalc.weekEnd(weekStart).toString(),
                timezone = WeekCalc.TIMEZONE_ID,
                serverNow = now.toApiString(),
            ),
        )
    }
}
