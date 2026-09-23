package app.menosan.export

import app.menosan.common.WeekCalc
import app.menosan.plugins.principal
import app.menosan.reports.ReportService
import io.ktor.http.ContentDisposition
import io.ktor.http.HttpHeaders
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.time.Clock

private val log = LoggerFactory.getLogger("app.menosan.export")

/** `GET /v1/export` (NFR16). Mount inside `authenticated { }`. */
fun Route.exportRoutes(exporter: DataExporter, reports: ReportService, clock: Clock) {
    get("/export") {
        val userId = call.principal.userId
        // Include every report the user is due, like GET /v1/reports does. A failure here still exports the rest.
        try {
            reports.catchUp(userId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("Report catch-up before export failed: {}", e.javaClass.name)
        }
        val document = exporter.export(userId)
        val date = clock.instant().atZone(WeekCalc.ZONE).toLocalDate()
        call.response.header(
            HttpHeaders.ContentDisposition,
            ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, "menosan-export-$date.json").toString(),
        )
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respond(document)
    }
}
