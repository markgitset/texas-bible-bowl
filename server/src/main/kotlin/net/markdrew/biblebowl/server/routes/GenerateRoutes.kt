package net.markdrew.biblebowl.server.routes

import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.markdrew.biblebowl.api.ApiError
import net.markdrew.biblebowl.api.QuestionStatus
import net.markdrew.biblebowl.api.RoundType
import net.markdrew.biblebowl.generation.typst.flashcardsTypst
import net.markdrew.biblebowl.generation.typst.practiceTestTypst
import net.markdrew.biblebowl.generation.typst.toFlashcards
import net.markdrew.biblebowl.server.data.QuestionRepository
import net.markdrew.biblebowl.server.typst.TypstCompiler
import net.markdrew.biblebowl.server.typst.TypstException

fun Route.generateRoutes(questions: QuestionRepository) {
    authenticate {
        // GET /generate/practice-test.pdf?round=FACT_FINDER&chapter=2&limit=40
        get("/generate/practice-test.pdf") {
            val round = call.request.queryParameters["round"]
                ?.let { runCatching { RoundType.valueOf(it) }.getOrNull() }
                ?: return@get call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("bad_round", "round must be one of ${RoundType.entries.joinToString()}"),
                )
            val chapter = call.request.queryParameters["chapter"]?.toIntOrNull()
            val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 40).coerceIn(1, 100)

            val pool = questions.list(QuestionStatus.APPROVED, chapter)
                .filter { it.roundType == round }
                .take(limit)
            if (pool.isEmpty()) {
                return@get call.respond(
                    HttpStatusCode.NotFound,
                    ApiError("no_questions", "No approved ${round.displayName} questions" +
                        (chapter?.let { " for chapter $it" } ?: "")),
                )
            }

            val typstSource = practiceTestTypst(round, pool)
            respondPdf(typstSource, "practice-${round.name.lowercase()}${chapter?.let { "-ch$it" } ?: ""}.pdf")
        }

        // GET /generate/flashcards.pdf?chapter=2&round=IDENTIFICATION (both filters optional)
        get("/generate/flashcards.pdf") {
            val chapter = call.request.queryParameters["chapter"]?.toIntOrNull()
            val round = call.request.queryParameters["round"]
                ?.let { runCatching { RoundType.valueOf(it) }.getOrNull() }

            val pool = questions.list(QuestionStatus.APPROVED, chapter)
                .filter { round == null || it.roundType == round }
                .take(200)
            if (pool.isEmpty()) {
                return@get call.respond(
                    HttpStatusCode.NotFound,
                    ApiError("no_questions", "No approved questions" + (chapter?.let { " for chapter $it" } ?: "")),
                )
            }
            respondPdf(flashcardsTypst(pool.toFlashcards()), "flashcards${chapter?.let { "-ch$it" } ?: ""}.pdf")
        }
    }
}

/** Compiles [typstSource] off the event loop and responds with PDF bytes as a named attachment. */
private suspend fun io.ktor.server.routing.RoutingContext.respondPdf(typstSource: String, fileName: String) {
    try {
        val pdf = withContext(Dispatchers.IO) { TypstCompiler.compile(typstSource) }
        call.response.header(
            HttpHeaders.ContentDisposition,
            ContentDisposition.Attachment.withParameter(
                ContentDisposition.Parameters.FileName, fileName,
            ).toString(),
        )
        call.respondBytes(pdf, ContentType.Application.Pdf)
    } catch (e: TypstException) {
        call.respond(HttpStatusCode.ServiceUnavailable, ApiError("typst_failed", e.message ?: "PDF generation failed"))
    }
}
