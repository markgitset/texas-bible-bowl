package net.markdrew.biblebowl.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import net.markdrew.biblebowl.api.ApiError
import net.markdrew.biblebowl.model.ScopeResolution
import net.markdrew.biblebowl.model.StandardStudySet
import net.markdrew.biblebowl.model.StudySet
import net.markdrew.biblebowl.model.resolveStudyScope
import net.markdrew.biblebowl.server.data.QuestionScope
import net.markdrew.biblebowl.server.data.SeasonRepository

/** The current season's study set, resolved strictly; [StandardStudySet.DEFAULT] when unresolvable. */
fun SeasonRepository.currentStudySet(): StudySet =
    StandardStudySet.bySlug(current().studySet) ?: StandardStudySet.DEFAULT

/**
 * Interprets a bare legacy `chapter` query parameter against the current season's study set (the
 * pre-scoping URL contract, kept working forever for old bookmarks). Null chapter = no restriction.
 *
 * Responds 400 and returns null when the chapter can't be resolved — a multi-book season without a
 * book, or a chapter outside the set.
 */
suspend fun ApplicationCall.legacyQuestionScope(chapter: Int?, seasonSet: StudySet): QuestionScope? {
    if (chapter == null) return QuestionScope.All
    return when (val res = resolveStudyScope(setParam = null, bookParam = null, chapter, seasonSet)) {
        is ScopeResolution.Resolved -> QuestionScope.Chapter(checkNotNull(res.scope.chapterRef))
        is ScopeResolution.Invalid -> {
            respond(HttpStatusCode.BadRequest, ApiError(res.error.code, res.error.message))
            null
        }
    }
}
