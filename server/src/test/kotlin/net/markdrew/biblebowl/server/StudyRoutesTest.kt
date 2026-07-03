package net.markdrew.biblebowl.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import net.markdrew.biblebowl.api.AuthResponse
import net.markdrew.biblebowl.api.HeadingDto
import net.markdrew.biblebowl.api.RegisterRequest
import net.markdrew.biblebowl.model.Book
import net.markdrew.biblebowl.model.StudySet
import net.markdrew.biblebowl.server.data.InMemoryQuestionRepository
import net.markdrew.biblebowl.server.data.InMemoryUserRepository
import net.markdrew.biblebowl.server.esv.EsvPassageService
import net.markdrew.biblebowl.server.esv.InMemoryEsvCache
import net.markdrew.biblebowl.server.security.JwtService
import net.markdrew.biblebowl.server.study.StudyDataService
import net.markdrew.biblebowl.server.typst.TypstCompiler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** ESV text-format chapter bodies keyed by packed absolute-verse query, in EsvIndexer's expected shape. */
private val CHAPTER_TEXTS = mapOf(
    "44001001-44001999" to """
        _______________________________________________________
        The Promise of the Holy Spirit

          [1] In the first book, O Theophilus, I have dealt with all that Jesus began to do and teach, [2] until the day when he was taken up.
    """.trimIndent(),
    "44002001-44002999" to """
        _______________________________________________________
        The Coming of the Holy Spirit

          [1] When the day of Pentecost arrived, they were all together in one place. [2] And suddenly there came from heaven a sound.
    """.trimIndent(),
)

class StudyRoutesTest {

    private fun mockEsvClient(): HttpClient = HttpClient(MockEngine { request ->
        val query = request.url.parameters["q"] ?: ""
        val text = CHAPTER_TEXTS.getValue(query)
        val chapter = query.substring(4, 7).trimStart('0')
        respond(
            content = """
                {
                  "query": "$query",
                  "canonical": "Acts $chapter",
                  "passages": [${Json.encodeToString(text)}]
                }
            """.trimIndent(),
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
        )
    })

    private fun studyService() = StudyDataService(
        esv = EsvPassageService(
            client = mockEsvClient(),
            cache = InMemoryEsvCache(),
            token = "test-esv-token",
            baseUrl = "https://fake.esv",
        ),
        studySet = StudySet("Acts 1-2", "acts-test", Book.ACT.chapterRange(1, 2)),
    )

    @Test
    fun headingsEndpointServesParsedHeadings() = testApplication {
        application {
            module(
                InMemoryUserRepository(), InMemoryQuestionRepository(),
                JwtService(secret = "test-secret"), esv = null, study = studyService(),
            )
        }
        val json = Json { ignoreUnknownKeys = true }
        val api = createClient { install(ContentNegotiation) { json(json) } }

        assertEquals(HttpStatusCode.Unauthorized, api.get("/study/headings").status)

        val reg = api.post("/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest("student@tbb.org", "password123", "Student"))
        }
        val auth: AuthResponse = json.decodeFromString(reg.bodyAsText())

        val res = api.get("/study/headings") { header(HttpHeaders.Authorization, "Bearer ${auth.token}") }
        assertEquals(HttpStatusCode.OK, res.status)
        val headings: List<HeadingDto> = json.decodeFromString(res.bodyAsText())
        assertEquals(
            listOf("The Promise of the Holy Spirit", "The Coming of the Holy Spirit"),
            headings.map { it.title },
        )
        assertEquals(listOf(1, 2), headings.map { it.chapter })
        assertEquals(listOf(1, 2), headings.map { it.index })
        assertEquals(listOf(2, 2), headings.map { it.total })
        assertEquals("1:1-2", headings.first().reference)

        // throughChapter filter
        val filtered = api.get("/study/headings?throughChapter=1") {
            header(HttpHeaders.Authorization, "Bearer ${auth.token}")
        }
        val filteredHeadings: List<HeadingDto> = json.decodeFromString(filtered.bodyAsText())
        assertEquals(listOf("The Promise of the Holy Spirit"), filteredHeadings.map { it.title })
    }

    @Test
    fun headingFlashcardsPdfEndpointCompiles() = testApplication {
        if (!TypstCompiler.isAvailable) {
            println("typst not on PATH; skipping PDF compile test")
            return@testApplication
        }
        application {
            module(
                InMemoryUserRepository(), InMemoryQuestionRepository(),
                JwtService(secret = "test-secret"), esv = null, study = studyService(),
            )
        }
        val json = Json { ignoreUnknownKeys = true }
        val api = createClient { install(ContentNegotiation) { json(json) } }
        val reg = api.post("/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest("printer@tbb.org", "password123", "Printer"))
        }
        val auth: AuthResponse = json.decodeFromString(reg.bodyAsText())

        val res = api.get("/generate/heading-flashcards.pdf") {
            header(HttpHeaders.Authorization, "Bearer ${auth.token}")
        }
        assertEquals(HttpStatusCode.OK, res.status)
        val bytes = res.bodyAsBytes()
        assertTrue(bytes.size > 4 && bytes.decodeToString(0, 4) == "%PDF", "response should be a PDF")
    }

    @Test
    fun headingsEndpointReturns503WithoutStudyService() = testApplication {
        application {
            module(
                InMemoryUserRepository(), InMemoryQuestionRepository(),
                JwtService(secret = "test-secret"), esv = null, study = null,
            )
        }
        val json = Json { ignoreUnknownKeys = true }
        val api = createClient { install(ContentNegotiation) { json(json) } }
        val reg = api.post("/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest("nobody@tbb.org", "password123", "Nobody"))
        }
        val auth: AuthResponse = json.decodeFromString(reg.bodyAsText())
        val res = api.get("/study/headings") { header(HttpHeaders.Authorization, "Bearer ${auth.token}") }
        assertEquals(HttpStatusCode.ServiceUnavailable, res.status)
    }
}
