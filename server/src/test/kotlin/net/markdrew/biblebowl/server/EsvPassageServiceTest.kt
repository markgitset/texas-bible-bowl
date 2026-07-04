package net.markdrew.biblebowl.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import net.markdrew.biblebowl.api.AuthResponse
import net.markdrew.biblebowl.api.ChapterTextDto
import net.markdrew.biblebowl.api.RegisterRequest
import net.markdrew.biblebowl.model.Book
import net.markdrew.biblebowl.server.data.InMemoryQuestionRepository
import net.markdrew.biblebowl.server.data.InMemoryUserRepository
import net.markdrew.biblebowl.server.esv.EsvPassageService
import net.markdrew.biblebowl.server.esv.InMemoryEsvCache
import net.markdrew.biblebowl.server.security.JwtService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.measureTime

/** Canned ESV v3/passage/text response for Acts 2 (truncated text). */
private const val ACTS_2_RESPONSE = """
{
  "query": "44002001-44002999",
  "canonical": "Acts 2",
  "parsed": [[44002001, 44002047]],
  "passage_meta": [],
  "passages": ["The Coming of the Holy Spirit\n\n  [1] When the day of Pentecost arrived, they were all together in one place."]
}
"""

class EsvPassageServiceTest {

    private fun mockEsvClient(hits: MutableList<String>): HttpClient = HttpClient(MockEngine { request ->
        hits += request.url.parameters["q"] ?: ""
        // Verify the licensed token header goes upstream.
        assertEquals("Token test-esv-token", request.headers["Authorization"])
        respond(
            content = ACTS_2_RESPONSE,
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
        )
    })

    @Test
    fun fetchesOnceThenServesFromCache() = runBlocking {
        val hits = mutableListOf<String>()
        val service = EsvPassageService(
            client = mockEsvClient(hits),
            cache = InMemoryEsvCache(),
            token = "test-esv-token",
            baseUrl = "https://fake.esv",
        )

        val first = service.chapterText(Book.ACT.chapterRef(2))
        val second = service.chapterText(Book.ACT.chapterRef(2))

        assertEquals(1, hits.size, "second call must come from cache")
        assertEquals("44002001-44002999", hits.single(), "query uses packed absolute-verse range")
        assertEquals("Acts 2", first.canonical)
        assertTrue(first.text.contains("Pentecost"))
        assertEquals(first, second)
    }

    @Test
    fun throttlesConsecutiveLiveFetchesButNotCacheHits() = runBlocking {
        val hits = mutableListOf<String>()
        val interval = 150L // ms; keep the test quick while still measurable
        val service = EsvPassageService(
            client = mockEsvClient(hits),
            cache = InMemoryEsvCache(),
            token = "test-esv-token",
            baseUrl = "https://fake.esv",
            minFetchInterval = interval.milliseconds,
        )

        // First fetch has no predecessor to wait on; the second (different chapter) must be spaced out.
        service.chapterText(Book.ACT.chapterRef(1))
        val elapsed = measureTime {
            service.chapterText(Book.ACT.chapterRef(2))
        }.inWholeMilliseconds
        assertEquals(2, hits.size, "both chapters are live fetches")
        assertTrue(elapsed >= interval - 30, "second live fetch was throttled (waited ${elapsed}ms, want >= ${interval}ms)")

        // A cache hit must not be throttled.
        val cachedElapsed = measureTime {
            service.chapterText(Book.ACT.chapterRef(1))
        }.inWholeMilliseconds
        assertEquals(2, hits.size, "cache hit made no upstream call")
        assertTrue(cachedElapsed < interval, "cache hit skipped the throttle (took ${cachedElapsed}ms)")
    }

    @Test
    fun retriesAfterRateLimitThenSucceeds() = runBlocking {
        var calls = 0
        val client = HttpClient(MockEngine { _ ->
            calls++
            if (calls == 1) {
                respond(
                    content = "rate limited",
                    status = HttpStatusCode.TooManyRequests,
                    headers = headersOf(HttpHeaders.RetryAfter, "1"), // 1s keeps the test quick
                )
            } else {
                respond(
                    content = ACTS_2_RESPONSE,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            }
        })
        val service = EsvPassageService(
            client = client,
            cache = InMemoryEsvCache(),
            token = "test-esv-token",
            baseUrl = "https://fake.esv",
            minFetchInterval = 0.milliseconds,
        )

        val chapter = service.chapterText(Book.ACT.chapterRef(2))
        assertEquals(2, calls, "one 429 then a successful retry")
        assertTrue(chapter.text.contains("Pentecost"))
    }

    @Test
    fun bibleRouteServesChapterToAuthenticatedUser() = testApplication {
        val hits = mutableListOf<String>()
        val esv = EsvPassageService(
            client = mockEsvClient(hits),
            cache = InMemoryEsvCache(),
            token = "test-esv-token",
            baseUrl = "https://fake.esv",
        )
        val users = InMemoryUserRepository()
        application {
            module(users, InMemoryQuestionRepository(), JwtService(secret = "test-secret"), esv)
        }
        val json = Json { ignoreUnknownKeys = true }
        val api = createClient {
            install(ContentNegotiation) { json(json) }
        }

        // Unauthenticated -> 401
        assertEquals(HttpStatusCode.Unauthorized, api.get("/bible/ACT/2").status)

        // Register, then fetch Acts 2 by code and by full name.
        val reg = api.post("/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest("reader@tbb.org", "password123", "Reader"))
        }
        val auth: AuthResponse = json.decodeFromString(reg.bodyAsText())

        val res = api.get("/bible/ACT/2") { header(HttpHeaders.Authorization, "Bearer ${auth.token}") }
        assertEquals(HttpStatusCode.OK, res.status)
        val dto: ChapterTextDto = json.decodeFromString(res.bodyAsText())
        assertEquals("ACT", dto.bookCode)
        assertEquals(2, dto.chapter)
        assertEquals("ESV", dto.translation)
        assertTrue(dto.text.contains("Pentecost"))
        assertTrue(dto.copyright.contains("Crossway"))

        val byName = api.get("/bible/Acts/2") { header(HttpHeaders.Authorization, "Bearer ${auth.token}") }
        assertEquals(HttpStatusCode.OK, byName.status)
        assertEquals(1, hits.size, "both requests should share one upstream fetch via the cache")

        // Bad book -> 400
        val bad = api.get("/bible/NOPE/2") { header(HttpHeaders.Authorization, "Bearer ${auth.token}") }
        assertEquals(HttpStatusCode.BadRequest, bad.status)
    }

    @Test
    fun unconfiguredServiceReturns503() = testApplication {
        val users = InMemoryUserRepository()
        application {
            module(users, InMemoryQuestionRepository(), JwtService(secret = "test-secret"), esv = null)
        }
        val json = Json { ignoreUnknownKeys = true }
        val api = createClient { install(ContentNegotiation) { json(json) } }
        val reg = api.post("/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest("reader2@tbb.org", "password123", "Reader"))
        }
        val auth: AuthResponse = json.decodeFromString(reg.bodyAsText())
        val res = api.get("/bible/ACT/2") { header(HttpHeaders.Authorization, "Bearer ${auth.token}") }
        assertEquals(HttpStatusCode.ServiceUnavailable, res.status)
    }
}
