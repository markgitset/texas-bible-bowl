package net.markdrew.biblebowl.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import net.markdrew.biblebowl.api.ApiError
import net.markdrew.biblebowl.api.StudyScopeParams
import net.markdrew.biblebowl.api.VerseDto
import net.markdrew.biblebowl.generate.normalizeWS
import net.markdrew.biblebowl.model.NO_BOOK_FORMAT
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
    // of verses for the verse quiz. Public like the rest of the study material: it hands out a few
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
            val sd = svc.studyData()
            // Each heading paired with the text it covers, to find the one a verse sits under.
            val headingAt = sd.headingCharRanges.entries.map { it.key }.zip(sd.headings).toMap()
            val inScope = sd.verses.entries
                .filter { (_, verseRef) -> scope.covers(verseRef.chapterRef) }
                .map { (range, verseRef) -> Triple(range, verseRef, sd.text.substring(range).normalizeWS()) }
            val verses = distinctlyWorded(inScope, sd.verses.entries.map { sd.text.substring(it.key).normalizeWS() }) { it.third }
                .shuffled()
                .take(count)
                .map { (range, verseRef, text) ->
                    VerseDto(
                        reference = verseRef.format(NO_BOOK_FORMAT),
                        bookCode = verseRef.book.name,
                        chapter = verseRef.chapter,
                        verse = verseRef.verse,
                        text = text,
                        // Null for a verse that straddles a heading boundary (or precedes the first).
                        heading = sd.headingCharRanges.keyEnclosing(range)?.let { headingAt[it] }?.toDto(),
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

/** The shortest verse worth quizzing — the practice rounds' 15-character clue floor. */
private const val MIN_QUIZ_VERSE_LENGTH = 15

/**
 * The [candidates] whose [text] can be placed: long enough to recognize, and worded unlike every
 * other verse in [allTexts] (the whole study set), so a repeated refrain never has two right answers.
 */
internal fun <T> distinctlyWorded(candidates: List<T>, allTexts: List<String>, text: (T) -> String): List<T> {
    val occurrences = allTexts.groupingBy { it.lowercase() }.eachCount()
    return candidates.filter { c ->
        val t = text(c)
        t.length >= MIN_QUIZ_VERSE_LENGTH && occurrences[t.lowercase()] == 1
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
