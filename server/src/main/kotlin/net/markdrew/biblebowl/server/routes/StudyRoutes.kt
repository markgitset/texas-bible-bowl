package net.markdrew.biblebowl.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import net.markdrew.biblebowl.api.ApiError
import net.markdrew.biblebowl.server.esv.EsvUpstreamException
import net.markdrew.biblebowl.server.study.StudyDataService
import net.markdrew.biblebowl.server.study.toDto

fun Route.studyRoutes(study: StudyDataService?) {
    authenticate {
        // GET /study/headings?throughChapter=5  (chapter filter optional)
        get("/study/headings") {
            if (study == null || !study.isConfigured) {
                return@get call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    ApiError("esv_unconfigured", "ESV service is not configured (set ESV_API_TOKEN)"),
                )
            }
            val throughChapter = call.request.queryParameters["throughChapter"]?.toIntOrNull()

            try {
                val headings = study.studyData().headings
                    .filter { throughChapter == null || it.chapterRange.start.chapter <= throughChapter }
                call.respond(headings.map { it.toDto() })
            } catch (e: EsvUpstreamException) {
                call.respond(HttpStatusCode.BadGateway, ApiError("esv_upstream", e.message ?: "ESV API error"))
            }
        }

        // GET /study/numbers — the season's numbers index (alphabetical), for the in-app study view.
        get("/study/numbers") {
            respondIndex(study) { it.numbers() }
        }

        // GET /study/names — the season's names index (all proper names), for the in-app study view.
        get("/study/names") {
            respondIndex(study) { it.names() }
        }
    }
}

/** Responds with an index (numbers/names) or the standard 503/502 when the study service is unavailable. */
private suspend fun io.ktor.server.routing.RoutingContext.respondIndex(
    study: StudyDataService?,
    index: suspend (StudyDataService) -> Any,
) {
    if (study == null || !study.isConfigured) {
        return call.respond(
            HttpStatusCode.ServiceUnavailable,
            ApiError("esv_unconfigured", "ESV service is not configured (set ESV_API_TOKEN)"),
        )
    }
    try {
        call.respond(index(study))
    } catch (e: EsvUpstreamException) {
        call.respond(HttpStatusCode.BadGateway, ApiError("esv_upstream", e.message ?: "ESV API error"))
    }
}
