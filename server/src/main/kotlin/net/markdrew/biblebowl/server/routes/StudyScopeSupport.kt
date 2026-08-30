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
import net.markdrew.biblebowl.model.ChapterRef
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
 * Whether [ref] is material this scope selects: the scoped chapter, else anywhere in the scoped book,
 * else anywhere in the set. [cumulative] widens the chapter case to "everything studied so far" —
 * through that chapter rather than that chapter alone — which is what the headings material and the
 * `throughChapter` endpoints mean by a chapter.
 *
 * The chapter comparison is book-aware ([ChapterRef] orders by absolute chapter), so a cumulative scope
 * reaches back through earlier books of a multi-book set. The book-only case is the picker's "All of
 * Num": on a multi-book set it must narrow to that book, not fall through to the whole set.
 */
fun StudyScope.covers(ref: ChapterRef, cumulative: Boolean = false): Boolean {
    val scopedRef = chapterRef
    val scopedBook = book
    return when {
        scopedRef != null -> if (cumulative) ref <= scopedRef else ref == scopedRef
        scopedBook != null -> ref.book == scopedBook
        else -> true
    }
}
