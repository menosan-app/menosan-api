package app.menosan.taxonomy

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** `GET /v1/taxonomy`: public, no auth (§8.1). Same content as the bundled `taxonomy.json`. */
fun Route.taxonomyRoutes(taxonomy: Taxonomy) {
    get("/taxonomy") {
        call.respond(taxonomy)
    }
}
