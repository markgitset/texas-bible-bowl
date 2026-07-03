package net.markdrew.biblebowl.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
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
import net.markdrew.biblebowl.api.SubmitQuestionRequest
import net.markdrew.biblebowl.server.data.QuestionRepository
import net.markdrew.biblebowl.server.data.UserRepository
import net.markdrew.biblebowl.server.security.currentUser
import net.markdrew.biblebowl.server.security.requirePermission

fun Route.questionRoutes(users: UserRepository, questions: QuestionRepository) {
    authenticate {
        route("/questions") {
            // Browse — defaults to approved questions; filter by chapter of Acts.
            get {
                val status = call.request.queryParameters["status"]?.let { runCatching { QuestionStatus.valueOf(it) }.getOrNull() }
                    ?: QuestionStatus.APPROVED
                val chapter = call.request.queryParameters["chapter"]?.toIntOrNull()
                call.respond(questions.list(status, chapter))
            }

            // Contribute a new question (any contestant).
            post {
                val user = currentUser(users) ?: return@post
                if (!requirePermission(user, Permission.QUESTION_SUBMIT)) return@post
                val req = call.receive<SubmitQuestionRequest>()
                if (req.prompt.isBlank() || req.answer.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, ApiError("invalid", "Prompt and answer are required"))
                    return@post
                }
                call.respond(HttpStatusCode.Created, questions.submit(user.id, user.displayName, req))
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
