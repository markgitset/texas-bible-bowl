package net.markdrew.biblebowl.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import net.markdrew.biblebowl.api.ApiError
import net.markdrew.biblebowl.api.StudyScopeParams
import net.markdrew.biblebowl.server.data.SeasonRepository
import net.markdrew.biblebowl.server.esv.EsvUpstreamException
import net.markdrew.biblebowl.server.study.StudyDataRegistry
import net.markdrew.biblebowl.server.study.StudyDataService
import net.markdrew.biblebowl.server.study.toDto

fun Route.studyRoutes(study: StudyDataRegistry?, seasons: SeasonRepository) {
    // Public: study material never requires sign-in (signing in only *adds* capabilities).
    // Every endpoint accepts ?set=<slug> (allowlisted, defaulting to the current season) so in-app
    // study views can reference any rotation cycle's material, not just this season's.

    // GET /study/headings?set=acts&book=ACT&throughChapter=5  (scope optional, cumulative)
    get("/study/headings") {
        val scope = call.resolveEsvScopeOrRespond(
            seasons.currentStudySet(), chapterKey = StudyScopeParams.THROUGH_CHAPTER,
        ) ?: return@get
        val svc = study?.forSet(scope.set)
        if (svc == null || !svc.isConfigured) {
            return@get call.respond(
                HttpStatusCode.ServiceUnavailable,
                ApiError("esv_unconfigured", "ESV service is not configured (set ESV_API_TOKEN)"),
            )
        }
        val throughRef = scope.chapterRef

        try {
            call.advertiseCanonicalScope(scope, chapterKey = StudyScopeParams.THROUGH_CHAPTER)
            // Book-aware cumulative filter (ChapterRef compares by absoluteChapter across books).
            val headings = svc.studyData().headings
                .filter { throughRef == null || it.chapterRange.start <= throughRef }
            call.respond(headings.map { it.toDto() })
        } catch (e: EsvUpstreamException) {
            call.respond(HttpStatusCode.BadGateway, ApiError("esv_upstream", e.message ?: "ESV API error"))
        }
    }

    // GET /study/numbers?set=acts — the set's numbers index (alphabetical), for the in-app study view.
    get("/study/numbers") {
        respondIndex(study, seasons) { it.numbers() }
    }

    // GET /study/names?set=acts — the set's names index (all proper names), for the in-app study view.
    get("/study/names") {
        respondIndex(study, seasons) { it.names() }
    }
}

/** Responds with an index (numbers/names) or the standard 503/502 when the study service is unavailable. */
private suspend fun io.ktor.server.routing.RoutingContext.respondIndex(
    study: StudyDataRegistry?,
    seasons: SeasonRepository,
    index: suspend (StudyDataService) -> Any,
) {
    val scope = call.resolveEsvScopeOrRespond(seasons.currentStudySet()) ?: return
    val svc = study?.forSet(scope.set)
    if (svc == null || !svc.isConfigured) {
        return call.respond(
            HttpStatusCode.ServiceUnavailable,
            ApiError("esv_unconfigured", "ESV service is not configured (set ESV_API_TOKEN)"),
        )
    }
    try {
        call.advertiseCanonicalScope(scope)
        call.respond(index(svc))
    } catch (e: EsvUpstreamException) {
        call.respond(HttpStatusCode.BadGateway, ApiError("esv_upstream", e.message ?: "ESV API error"))
    }
}
