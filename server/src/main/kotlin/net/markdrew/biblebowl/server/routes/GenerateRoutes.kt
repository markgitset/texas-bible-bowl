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
import net.markdrew.biblebowl.model.Round
import net.markdrew.biblebowl.generate.practice.PracticeTest
import net.markdrew.biblebowl.generate.practice.eventsTypst
import net.markdrew.biblebowl.generate.practice.findTheVerseTypst
import net.markdrew.biblebowl.generate.practice.quotesTypst
import net.markdrew.biblebowl.generation.typst.Flashcard
import net.markdrew.biblebowl.generation.typst.flashcardsTypst
import net.markdrew.biblebowl.generation.typst.practiceTestTypst
import net.markdrew.biblebowl.generation.typst.toFlashcards
import net.markdrew.biblebowl.model.NO_BOOK_FORMAT
import net.markdrew.biblebowl.server.data.QuestionRepository
import net.markdrew.biblebowl.server.esv.EsvUpstreamException
import net.markdrew.biblebowl.server.study.StudyDataService
import net.markdrew.biblebowl.server.typst.TypstCompiler
import net.markdrew.biblebowl.server.typst.TypstException
import kotlin.random.Random
import kotlin.random.nextInt

fun Route.generateRoutes(questions: QuestionRepository, study: StudyDataService? = null) {
    authenticate {
        // GET /generate/practice-test.pdf?round=FACT_FINDER&chapter=2&limit=40&seed=1234
        //
        // R1/R4/R5 are generated deterministically from the ESV text; R2/R3 come from the approved
        // crowd-sourced question bank. `chapter` is an exact chapter for the bank rounds and a cumulative
        // "through chapter" for the text rounds (matching how the study material scopes cumulative tests).
        get("/generate/practice-test.pdf") {
            val round = call.request.queryParameters["round"]
                ?.let { runCatching { Round.valueOf(it) }.getOrNull() }
                ?: return@get call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("bad_round", "round must be one of ${Round.entries.joinToString()}"),
                )
            val chapter = call.request.queryParameters["chapter"]?.toIntOrNull()

            if (round.textGenerated) {
                val seed = call.request.queryParameters["seed"]?.toIntOrNull()
                return@get respondTextPracticeTest(round, chapter, seed, study)
            }

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
                ?.let { runCatching { Round.valueOf(it) }.getOrNull() }

            // The question bank only holds crowd-sourced rounds. R1/R4/R5 come from the text; R5 has its
            // own deck at /generate/heading-flashcards.pdf.
            if (round != null && round.textGenerated) {
                return@get call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError(
                        "not_crowd_sourced",
                        "${round.displayName} is generated from the text, not the question bank" +
                            (if (round == Round.KNOW_THE_CHAPTER_HEADINGS) " — use /generate/heading-flashcards.pdf" else ""),
                    ),
                )
            }

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

        // GET /generate/heading-flashcards.pdf?throughChapter=5 — Round 5 (chapter headings) deck
        get("/generate/heading-flashcards.pdf") {
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
                val cards = headings.map { h ->
                    Flashcard(
                        front = h.title,
                        back = "Chapter ${h.chapterRange.start.chapter}",
                        note = h.verseRange.format(NO_BOOK_FORMAT),
                        footer = "${h.index} of ${h.maxIndex}",
                    )
                }
                respondPdf(
                    flashcardsTypst(cards),
                    "heading-flashcards${throughChapter?.let { "-through-ch$it" } ?: ""}.pdf",
                )
            } catch (e: EsvUpstreamException) {
                call.respond(HttpStatusCode.BadGateway, ApiError("esv_upstream", e.message ?: "ESV API error"))
            }
        }
    }
}

/**
 * Generates a text-based practice test (R1 Find-the-Verse, R4 Quotations, or R5 Events) from the indexed
 * [StudyData] and responds with the PDF. [chapter], when set, scopes the test cumulatively through that
 * chapter; [seed] makes selection reproducible.
 */
private suspend fun io.ktor.server.routing.RoutingContext.respondTextPracticeTest(
    round: Round,
    chapter: Int?,
    seed: Int?,
    study: StudyDataService?,
) {
    if (study == null || !study.isConfigured) {
        return call.respond(
            HttpStatusCode.ServiceUnavailable,
            ApiError("esv_unconfigured", "ESV service is not configured (set ESV_API_TOKEN)"),
        )
    }
    val studyData = try {
        study.studyData()
    } catch (e: EsvUpstreamException) {
        return call.respond(HttpStatusCode.BadGateway, ApiError("esv_upstream", e.message ?: "ESV API error"))
    }

    val throughChapter = chapter?.let { ch -> studyData.chapterRefs.firstOrNull { it.chapter == ch } }
    if (chapter != null && throughChapter == null) {
        return call.respond(HttpStatusCode.BadRequest, ApiError("bad_chapter", "No chapter $chapter in the study set"))
    }

    val content = studyData.practice(throughChapter)
    val practiceTest = PracticeTest(round, content, randomSeed = seed ?: Random.nextInt(1..9_999))
    val typstSource: String? = when (round) {
        Round.FIND_THE_VERSE -> findTheVerseTypst(practiceTest)
        Round.KNOW_THE_CHAPTER_QUOTES -> quotesTypst(practiceTest)
        Round.KNOW_THE_CHAPTER_HEADINGS -> eventsTypst(practiceTest)
        else -> null // unreachable: guarded by round.textGenerated at the call site
    }
    if (typstSource == null) {
        return call.respond(
            HttpStatusCode.UnprocessableEntity,
            ApiError("not_enough_chapters", "Not enough chapters covered to build a ${round.displayName} test"),
        )
    }
    respondPdf(typstSource, "practice-${round.name.lowercase()}${chapter?.let { "-through-ch$it" } ?: ""}.pdf")
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
