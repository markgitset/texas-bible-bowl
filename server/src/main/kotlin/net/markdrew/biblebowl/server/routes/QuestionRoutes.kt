package net.markdrew.biblebowl.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import net.markdrew.biblebowl.api.ApiError
import net.markdrew.biblebowl.api.ModerateQuestionRequest
import net.markdrew.biblebowl.api.Permission
import net.markdrew.biblebowl.api.QuestionStatus
import net.markdrew.biblebowl.model.Round
import net.markdrew.biblebowl.api.SubmitQuestionRequest
import net.markdrew.biblebowl.model.bookByCode
import net.markdrew.biblebowl.model.bookFromRefs
import net.markdrew.biblebowl.server.data.QuestionRepository
import net.markdrew.biblebowl.server.data.SeasonRepository
import net.markdrew.biblebowl.server.data.UserRepository
import net.markdrew.biblebowl.server.security.currentUser
import net.markdrew.biblebowl.server.security.requirePermission

fun Route.questionRoutes(users: UserRepository, questions: QuestionRepository, seasons: SeasonRepository) {
    route("/questions") {
        // Browse — public. Anonymous visitors always see the approved list (the community bank is
        // study material); signed-in users may also request other statuses (the moderation queue).
        authenticate(optional = true) {
            get {
                val requested = call.request.queryParameters["status"]
                    ?.let { runCatching { QuestionStatus.valueOf(it) }.getOrNull() }
                val signedIn = call.principal<JWTPrincipal>() != null
                val status = if (signedIn) requested ?: QuestionStatus.APPROVED else QuestionStatus.APPROVED
                val chapter = call.request.queryParameters["chapter"]?.toIntOrNull()
                val scope = call.legacyQuestionScope(chapter, seasons.currentStudySet()) ?: return@get
                call.respond(questions.list(status, scope))
            }
        }

        authenticate {
            // Contribute a new question (any contestant).
            post {
                val user = currentUser(users) ?: return@post
                if (!requirePermission(user, Permission.QUESTION_SUBMIT)) return@post
                val req = call.receive<SubmitQuestionRequest>()
                if (req.prompt.isBlank() || req.answer.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, ApiError("invalid", "Prompt and answer are required"))
                    return@post
                }
                // Only Fact Finder (R2) and Identification (R3) are crowd-sourced; the other rounds are
                // generated from the ESV text and must not enter the question bank.
                if (!req.roundType.crowdSourced) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ApiError(
                            "not_crowd_sourced",
                            "${req.roundType.displayName} is generated from the text, not crowd-sourced. " +
                                "Only ${Round.crowdSourcedRounds.joinToString { it.displayName }} accept submissions.",
                        ),
                    )
                    return@post
                }
                // The question's permanent scope is a canonical book (+ chapter) — resolved from the
                // explicit bookCode, else inferred from the references, else the season's single book.
                val book = req.bookCode?.let {
                    bookByCode(it) ?: return@post call.respond(
                        HttpStatusCode.BadRequest,
                        ApiError("unknown_book", "Unknown book '${it}'"),
                    )
                }
                    ?: bookFromRefs(req.references)
                    ?: seasons.currentStudySet().books.singleOrNull()
                    ?: return@post call.respond(
                        HttpStatusCode.BadRequest,
                        ApiError(
                            "book_required",
                            "The current study set spans multiple books — supply bookCode or a book-qualified reference",
                        ),
                    )
                val chapter = req.chapter
                if (chapter != null && chapter !in 1..book.chapterCount) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        ApiError("chapter_not_in_set", "${book.fullName} has no chapter $chapter"),
                    )
                }
                call.respond(HttpStatusCode.Created, questions.submit(user.id, user.displayName, req, book))
            }

            // Upvote a question.
            post("/{id}/vote") {
                val user = currentUser(users) ?: return@post
                if (!requirePermission(user, Permission.QUESTION_VOTE)) return@post
                val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                val updated = questions.vote(id, user.id)
                    ?: return@post call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such question"))
                call.respond(updated)
            }

            // Moderate (admin only): approve/reject a pending question.
            post("/{id}/moderate") {
                val user = currentUser(users) ?: return@post
                if (!requirePermission(user, Permission.QUESTION_MODERATE)) return@post
                val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                val req = call.receive<ModerateQuestionRequest>()
                val updated = questions.setStatus(id, req.status)
                    ?: return@post call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such question"))
                call.respond(updated)
            }
        }
    }
}
