package net.markdrew.biblebowl.server

import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import net.markdrew.biblebowl.api.ApiError
import net.markdrew.biblebowl.api.AuthResponse
import net.markdrew.biblebowl.api.QuestionDto
import net.markdrew.biblebowl.api.QuestionStatus
import net.markdrew.biblebowl.api.RegisterRequest
import net.markdrew.biblebowl.api.SubmitQuestionRequest
import net.markdrew.biblebowl.model.Book
import net.markdrew.biblebowl.model.Round
import net.markdrew.biblebowl.server.data.InMemoryQuestionRepository
import net.markdrew.biblebowl.server.data.InMemoryUserRepository
import net.markdrew.biblebowl.server.security.JwtService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The canonical set/book/chapter scope contract on /questions and the generate endpoints — what
 * makes study URLs durable across the 10-year rotation (see StudyScopeSupport).
 */
class ScopeRoutesTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** An approved Acts 2 question, an approved John 3 question, and a pending John 3 question. */
    private fun seededQuestions(): InMemoryQuestionRepository {
        val questions = InMemoryQuestionRepository()
        fun submit(book: Book, chapter: Int, prompt: String, status: QuestionStatus) {
            val q = questions.submit(
                "author-1", "Author",
                SubmitQuestionRequest(Round.FACT_FINDER, prompt, "yes", chapter = chapter),
                book = book,
            )
            questions.setStatus(q.id, status)
        }
        submit(Book.ACT, 2, "This season's question?", QuestionStatus.APPROVED)
        submit(Book.JOH, 3, "An off-season John question?", QuestionStatus.APPROVED)
        submit(Book.JOH, 3, "A pending John question?", QuestionStatus.PENDING)
        return questions
    }

    private suspend fun io.ktor.client.HttpClient.prompts(url: String): List<String> {
        val res = get(url)
        assertEquals(HttpStatusCode.OK, res.status, "for $url")
        return json.decodeFromString<List<QuestionDto>>(res.bodyAsText()).map { it.prompt }
    }

    @Test
    fun bareQuestionListDefaultsToTheSeasonSetAndSetAllIsTheArchive() = testApplication {
        application {
            module(InMemoryUserRepository(), seededQuestions(), JwtService(secret = "test-secret"))
        }
        val api = createClient { install(ContentNegotiation) { json(json) } }

        // No scope params -> the current (Acts) season's material only: the browser is a study tool.
        assertEquals(listOf("This season's question?"), api.prompts("/questions"))
        // ?set=all -> the whole approved archive, every season's books.
        assertEquals(
            setOf("This season's question?", "An off-season John question?"),
            api.prompts("/questions?set=all").toSet(),
        )
        // A bare book scopes to that book regardless of the current season — the off-year study case.
        assertEquals(listOf("An off-season John question?"), api.prompts("/questions?book=JOH"))
        assertEquals(listOf("An off-season John question?"), api.prompts("/questions?book=JOH&chapter=3"))
        assertEquals(emptyList(), api.prompts("/questions?book=JOH&chapter=4"))
    }

    @Test
    fun legacyChapterParamStillResolvesAgainstTheSeasonAndAdvertisesTheCanonicalForm() = testApplication {
        application {
            module(InMemoryUserRepository(), seededQuestions(), JwtService(secret = "test-secret"))
        }
        val api = createClient { install(ContentNegotiation) { json(json) } }

        // Pre-scoping bookmarks keep working: chapter alone means the season's (single) book.
        assertEquals(listOf("This season's question?"), api.prompts("/questions?chapter=2"))
        assertEquals(api.prompts("/questions?chapter=2"), api.prompts("/questions?book=ACT&chapter=2"))

        // ...and the response advertises the durable spelling.
        val legacy = api.get("/questions?chapter=2")
        val link = legacy.headers[HttpHeaders.Link].orEmpty()
        assertTrue("book=ACT" in link && "chapter=2" in link && "canonical" in link, "got Link: $link")
        // An already-canonical request needs no correction.
        val canonical = api.get("/questions?book=ACT&chapter=2")
        assertEquals(null, canonical.headers[HttpHeaders.Link])
    }

    @Test
    fun moderationQueueIsBankWideByDefault() = testApplication {
        val users = InMemoryUserRepository()
        application { module(users, seededQuestions(), JwtService(secret = "test-secret")) }
        val api = createClient {
            install(ContentNegotiation) { json(json) }
            defaultRequest { contentType(ContentType.Application.Json) }
        }
        val auth: AuthResponse = json.decodeFromString(
            api.post("/auth/register") {
                setBody(RegisterRequest("mod@tbb.org", "password123", "Mod", birthdate = null, adult = true))
            }.bodyAsText(),
        )

        // A pending John question must not hide from moderators just because the season is Acts.
        val res = api.get("/questions?status=PENDING") {
            header(HttpHeaders.Authorization, "Bearer ${auth.token}")
        }
        assertEquals(HttpStatusCode.OK, res.status)
        val prompts = json.decodeFromString<List<QuestionDto>>(res.bodyAsText()).map { it.prompt }
        assertEquals(listOf("A pending John question?"), prompts)
    }

    @Test
    fun unresolvableScopesAreRejectedWithTypedErrors() = testApplication {
        application {
            module(InMemoryUserRepository(), seededQuestions(), JwtService(secret = "test-secret"))
        }
        val api = createClient { install(ContentNegotiation) { json(json) } }

        suspend fun errorCode(url: String): String {
            val res = api.get(url)
            assertEquals(HttpStatusCode.BadRequest, res.status, "for $url")
            return json.decodeFromString<ApiError>(res.bodyAsText()).code
        }

        // "Chapter 3 of Life of Moses" is not a study unit — which book's chapter 3?
        assertEquals("book_required", errorCode("/questions?set=moses&chapter=3"))
        assertEquals("book_not_in_set", errorCode("/questions?set=moses&book=JOH"))
        assertEquals("chapter_not_in_set", errorCode("/questions?set=moses&book=NUM&chapter=15"))
        assertEquals("chapter_not_in_set", errorCode("/questions?chapter=99"))
        assertEquals("unknown_set", errorCode("/questions?set=zzznotaset"))
        // Strict slugs: no prefix matching ("jos" must not silently mean Joshua/Judges/Ruth).
        assertEquals("unknown_set", errorCode("/questions?set=jos"))
        assertEquals("unknown_book", errorCode("/questions?book=zzz"))
    }

    @Test
    fun esvEndpointsAllowlistTheStandardSets() = testApplication {
        application {
            // No ESV configured: allowlist rejection (400) must still beat the 503.
            module(InMemoryUserRepository(), InMemoryQuestionRepository(), JwtService(secret = "test-secret"))
        }
        val api = createClient { install(ContentNegotiation) { json(json) } }

        assertEquals(HttpStatusCode.BadRequest, api.get("/generate/bible-text.pdf?set=zzznope").status)
        // A whole book that is a standard set is allowlisted; one that isn't (no Mark set) is not.
        assertEquals(HttpStatusCode.BadRequest, api.get("/generate/bible-text.pdf?book=MAR").status)
        assertEquals(HttpStatusCode.ServiceUnavailable, api.get("/generate/bible-text.pdf?set=john").status)
        assertEquals(HttpStatusCode.ServiceUnavailable, api.get("/generate/bible-text.pdf?book=JOH").status)
        assertEquals(HttpStatusCode.ServiceUnavailable, api.get("/study/headings?set=john").status)
        assertEquals(HttpStatusCode.BadRequest, api.get("/study/headings?set=zzznope").status)
    }

    @Test
    fun bankGeneratorFilenamesAreSetPrefixedAndBookQualified() = testApplication {
        application {
            module(InMemoryUserRepository(), seededQuestions(), JwtService(secret = "test-secret"))
        }
        val api = createClient { install(ContentNegotiation) { json(json) } }

        // Question-bank exports need no ESV/Typst: assert the durable names end-to-end.
        val acts = api.get("/generate/questions.tsv?chapter=2")
        assertEquals(HttpStatusCode.OK, acts.status)
        assertTrue("quizlet-acts-questions-ch2.tsv" in acts.headers[HttpHeaders.ContentDisposition].orEmpty())

        // A bare-book scope names by the book's own slug; single-book sets keep the -chN suffix.
        val john = api.get("/generate/questions.tsv?book=JOH&chapter=3")
        assertEquals(HttpStatusCode.OK, john.status)
        assertTrue("quizlet-john-questions-ch3.tsv" in john.headers[HttpHeaders.ContentDisposition].orEmpty())
    }
}
