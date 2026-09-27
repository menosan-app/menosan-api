package app.menosan.taxonomy

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.taxonomyRoutes(taxonomy: Taxonomy) {
    get("/taxonomy") {
        call.respond(taxonomy)
    }
}
