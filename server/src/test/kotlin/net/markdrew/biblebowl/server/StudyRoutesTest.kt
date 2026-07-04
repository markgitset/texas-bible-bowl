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
import net.markdrew.biblebowl.api.IndexEntryDto
import net.markdrew.biblebowl.api.RegisterRequest
import kotlinx.coroutines.runBlocking
import net.markdrew.biblebowl.model.Book
import net.markdrew.biblebowl.model.StandardStudySet
import net.markdrew.biblebowl.model.StudySet
import kotlin.time.Duration.Companion.milliseconds
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
    fun defaultActsSetFetchesExactlyItsChapterCountNotTheSentinel() = runBlocking {
        // Regression: the DEFAULT Acts set uses an open-ended sentinel chapter range (to end of book). Build
        // must clamp to Book.ACT.chapterCount (28) — not fire ~999 ESV calls for chapters that don't exist.
        val queries = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            val query = request.url.parameters["q"] ?: ""
            queries += query
            respond(
                content = """
                    {
                      "query": "$query",
                      "canonical": "Acts",
                      "passages": [${Json.encodeToString("_______________________________________________________\nA Heading\n\n  [1] Some verse text.")}]
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        })
        val service = StudyDataService(
            esv = EsvPassageService(
                client = client,
                cache = InMemoryEsvCache(),
                token = "test-esv-token",
                baseUrl = "https://fake.esv",
                minFetchInterval = 0.milliseconds,
            ),
            studySet = StandardStudySet.DEFAULT, // Acts, sentinel "to end of book" range
        )

        service.studyData()
        assertEquals(Book.ACT.chapterCount, queries.size, "one ESV call per real chapter of Acts, no more")
        assertEquals(28, queries.size)
        assertEquals(28L, service.esvCallCount)
    }

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
    fun numbersEndpointServesTheNumbersIndex() = testApplication {
        application {
            module(
                InMemoryUserRepository(), InMemoryQuestionRepository(),
                JwtService(secret = "test-secret"), esv = null, study = studyService(),
            )
        }
        val json = Json { ignoreUnknownKeys = true }
        val api = createClient { install(ContentNegotiation) { json(json) } }

        assertEquals(HttpStatusCode.Unauthorized, api.get("/study/numbers").status)

        val reg = api.post("/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest("counter@tbb.org", "password123", "Counter"))
        }
        val auth: AuthResponse = json.decodeFromString(reg.bodyAsText())

        val res = api.get("/study/numbers") { header(HttpHeaders.Authorization, "Bearer ${auth.token}") }
        assertEquals(HttpStatusCode.OK, res.status)
        val entries: List<IndexEntryDto> = json.decodeFromString(res.bodyAsText())
        // The Acts 1-2 fixture contains "one" ("in one place") and "first" ("the first book").
        val keys = entries.map { it.key }
        assertTrue(entries.isNotEmpty(), "expected some numbers, got $keys")
        assertTrue(keys.any { it == "one" || it == "first" }, "expected 'one'/'first' among $keys")
        entries.forEach { e -> assertEquals(e.total, e.references.sumOf { it.count }) }
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
