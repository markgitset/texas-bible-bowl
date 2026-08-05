package net.markdrew.biblebowl.server.routes

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLParameter
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respond
import net.markdrew.biblebowl.api.ApiError
import net.markdrew.biblebowl.api.StudyScopeParams
import net.markdrew.biblebowl.model.ScopeResolution
import net.markdrew.biblebowl.model.StandardStudySet
import net.markdrew.biblebowl.model.StudyScope
import net.markdrew.biblebowl.model.StudySet
import net.markdrew.biblebowl.model.resolveStudyScope
import net.markdrew.biblebowl.server.data.QuestionScope
import net.markdrew.biblebowl.server.data.SeasonRepository

/** The current season's study set, resolved strictly; [StandardStudySet.DEFAULT] when unresolvable. */
fun SeasonRepository.currentStudySet(): StudySet =
    StandardStudySet.bySlug(current().studySet) ?: StandardStudySet.DEFAULT

/**
 * Resolves this request's `set`/`book`/`chapter` (or `throughChapter`) parameters to a canonical
 * [StudyScope], defaulting to [seasonSet] when no scope parameters are present — the pre-scoping URL
 * contract (`?chapter=N` alone) keeps working forever for old bookmarks.
 *
 * Responds 400 (with the [net.markdrew.biblebowl.model.ScopeError] code/message) and returns null on
 * an unresolvable scope.
 */
suspend fun ApplicationCall.resolveScopeOrRespond(
    seasonSet: StudySet,
    chapterKey: String = StudyScopeParams.CHAPTER,
): StudyScope? {
    val raw = StudyScopeParams.read({ request.queryParameters[it] }, chapterKey)
    return when (val res = resolveStudyScope(raw.set, raw.book, raw.chapter, seasonSet)) {
        is ScopeResolution.Resolved -> res.scope
        is ScopeResolution.Invalid -> {
            respond(HttpStatusCode.BadRequest, ApiError(res.error.code, res.error.message))
            null
        }
    }
}

/**
 * [resolveScopeOrRespond], additionally restricted to the [StandardStudySet] allowlist — for the
 * ESV-text-backed endpoints, where an arbitrary set (e.g. a bare `book=` of a never-studied book)
 * would let anonymous requests spend the ESV licence budget on uncached text.
 */
suspend fun ApplicationCall.resolveEsvScopeOrRespond(
    seasonSet: StudySet,
    chapterKey: String = StudyScopeParams.CHAPTER,
): StudyScope? {
    val scope = resolveScopeOrRespond(seasonSet, chapterKey) ?: return null
    if (StandardStudySet.bySlug(scope.set.simpleName) != scope.set) {
        respond(
            HttpStatusCode.BadRequest,
            ApiError("unknown_set", "'${scope.set.simpleName}' is not an available study set"),
        )
        return null
    }
    return scope
}

/**
 * Advertises the durable form of a scoped URL via a `Link: <…>; rel="canonical"` response header when
 * this request's scope parameters weren't already canonical (legacy `?chapter=N`, or an implied
 * season scope). Non-scope parameters (round, fontSize, …) are preserved. Old bookmarks keep working
 * by interpretation; this is how they learn the season-proof spelling.
 */
fun ApplicationCall.advertiseCanonicalScope(scope: StudyScope, chapterKey: String = StudyScopeParams.CHAPTER) {
    val scopeKeys = setOf(StudyScopeParams.SET, StudyScopeParams.BOOK, chapterKey)
    val canonical = StudyScopeParams.write(scope, chapterKey)
    val actual = request.queryParameters.entries()
        .filter { it.key in scopeKeys }
        .flatMap { (key, values) -> values.map { key to it } }
    if (actual.toSet() == canonical.toSet()) return
    val others = request.queryParameters.entries()
        .filter { it.key !in scopeKeys }
        .flatMap { (key, values) -> values.map { key to it } }
    val query = (canonical + others).joinToString("&") { (key, value) -> "$key=${value.encodeURLParameter()}" }
    response.header(HttpHeaders.Link, "<${request.path()}?$query>; rel=\"canonical\"")
}

/** This scope as a question-bank query: one exact chapter, or the OR of the scope's ranges. */
fun StudyScope.toQuestionScope(): QuestionScope =
    chapterRef?.let { QuestionScope.Chapter(it) } ?: QuestionScope.Ranges(ranges())

/**
 * Filename fragment pinning this scope's chapter: today's `-ch2` for single-book sets, book-qualified
 * `-num14` for multi-book sets (a bare number would be ambiguous); empty for whole-set scopes.
 */
fun StudyScope.chapterSuffix(cumulative: Boolean = false): String {
    val ref = chapterRef ?: return ""
    val core = if (set.isSingleBook) "ch${ref.chapter}" else ref.serialize().lowercase()
    return (if (cumulative) "-through-" else "-") + core
}
