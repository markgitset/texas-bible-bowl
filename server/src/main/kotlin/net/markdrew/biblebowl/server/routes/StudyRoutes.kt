package net.markdrew.biblebowl.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import net.markdrew.biblebowl.api.ApiError
import net.markdrew.biblebowl.api.StudyScopeParams
import net.markdrew.biblebowl.api.VersePlaceDto
import net.markdrew.biblebowl.api.VerseQuestionDto
import net.markdrew.biblebowl.model.NO_BOOK_FORMAT
import net.markdrew.biblebowl.server.data.SeasonRepository
import net.markdrew.biblebowl.server.esv.EsvUpstreamException
import net.markdrew.biblebowl.server.study.StudyDataRegistry
import net.markdrew.biblebowl.server.study.StudyDataService
import net.markdrew.biblebowl.server.study.toDto
import net.markdrew.biblebowl.server.study.versesByWording

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
        try {
            call.advertiseCanonicalScope(scope, chapterKey = StudyScopeParams.THROUGH_CHAPTER)
            // The scope's own span test — book-aware, and the same for a cumulative or explicit range.
            val headings = svc.studyData().headings.filter { scope.covers(it.chapterRange.start) }
            call.respond(headings.map { it.toDto() })
        } catch (e: EsvUpstreamException) {
            call.respond(HttpStatusCode.BadGateway, ApiError("esv_upstream", e.message ?: "ESV API error"))
        }
    }

    // GET /study/verses?set=acts&book=ACT&fromChapter=2&throughChapter=5&count=20 — a random sample
    // of verse-quiz questions. Public like the rest of the study material: it hands out a few
    // dozen short excerpts per request (at most [MAX_QUIZ_VERSES]), never the running text in bulk.
    get("/study/verses") {
        val scope = call.resolveEsvScopeOrRespond(
            seasons.currentStudySet(), chapterKey = StudyScopeParams.THROUGH_CHAPTER,
        ) ?: return@get
        val count = (call.request.queryParameters["count"]?.toIntOrNull() ?: 20).coerceIn(1, MAX_QUIZ_VERSES)
        val svc = study?.forSet(scope.set)
        if (svc == null || !svc.isConfigured) {
            return@get call.respond(
                HttpStatusCode.ServiceUnavailable,
                ApiError("esv_unconfigured", "ESV service is not configured (set ESV_API_TOKEN)"),
            )
        }
        try {
            call.advertiseCanonicalScope(scope, chapterKey = StudyScopeParams.THROUGH_CHAPTER)
            // One question per wording, asked when any of its places is in scope; each lists every place
            // in the set (a refrain's other places count even outside the requested chapters).
            val verses = svc.studyData().versesByWording()
                .filter { group -> group.any { scope.covers(it.ref.chapterRef) } }
                .shuffled()
                .take(count)
                .map { group ->
                    VerseQuestionDto(
                        text = group.first().text,
                        places = group.map { place ->
                            VersePlaceDto(
                                reference = place.ref.format(NO_BOOK_FORMAT),
                                bookCode = place.ref.book.name,
                                chapter = place.ref.chapter,
                                verse = place.ref.verse,
                                heading = place.heading?.toDto(),
                            )
                        },
                    )
                }
            call.respond(verses)
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

/** The most verses one /study/verses request hands out — the verse quiz's longest run. */
const val MAX_QUIZ_VERSES = 50

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
