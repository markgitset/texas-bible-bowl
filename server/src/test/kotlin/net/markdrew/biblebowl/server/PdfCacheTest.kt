package net.markdrew.biblebowl.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
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
import net.markdrew.biblebowl.api.ClearPdfCacheResponse
import net.markdrew.biblebowl.api.LoginRequest
import net.markdrew.biblebowl.api.RegisterRequest
import net.markdrew.biblebowl.api.Role
import net.markdrew.biblebowl.api.RoleGrant
import net.markdrew.biblebowl.model.Book
import net.markdrew.biblebowl.model.StudySet
import net.markdrew.biblebowl.server.data.InMemoryQuestionRepository
import net.markdrew.biblebowl.server.data.InMemoryUserRepository
import net.markdrew.biblebowl.server.esv.EsvPassageService
import net.markdrew.biblebowl.server.esv.InMemoryEsvCache
import net.markdrew.biblebowl.server.security.JwtService
import net.markdrew.biblebowl.server.security.Passwords
import net.markdrew.biblebowl.server.study.InMemoryPdfCache
import net.markdrew.biblebowl.server.study.PdfCache
import net.markdrew.biblebowl.server.study.StudyDataService
import net.markdrew.biblebowl.server.typst.TypstCompiler
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ESV text-format chapter bodies keyed by packed absolute-verse query (see StudyRoutesTest). */
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

/** Wraps a [PdfCache] and counts hits (non-null gets) and puts, to prove when Typst was skipped. */
private class RecordingPdfCache(private val delegate: PdfCache = InMemoryPdfCache()) : PdfCache {
    var hits = 0
    var puts = 0

    override fun get(studySet: String, fileName: String, contentStamp: Int): ByteArray? =
        delegate.get(studySet, fileName, contentStamp)?.also { hits++ }

    override fun put(studySet: String, fileName: String, contentStamp: Int, pdf: ByteArray) {
        puts++
        delegate.put(studySet, fileName, contentStamp, pdf)
    }

    override fun clear(): Int = delegate.clear()
}

class PdfCacheTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

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
    fun cacheHitServesStoredBytesWithoutTypst() = testApplication {
        // No typst guard on purpose: a hit must never compile, so this passes even without typst.
        val service = studyService()
        val cache = InMemoryPdfCache()
        val seeded = "%PDF-cached-fake".toByteArray()
        cache.put("acts-test", "heading-flashcards.pdf", service.contentStamp(), seeded)
        application {
            module(
                InMemoryUserRepository(), InMemoryQuestionRepository(),
                JwtService(secret = "test-secret"), esv = null, study = service, pdfCache = cache,
            )
        }
        val api = createClient { }

        val res = api.get("/generate/heading-flashcards.pdf")
        assertEquals(HttpStatusCode.OK, res.status)
        assertContentEquals(seeded, res.bodyAsBytes(), "a cache hit must serve the stored bytes verbatim")
        assertTrue("heading-flashcards.pdf" in res.headers[HttpHeaders.ContentDisposition].orEmpty())
    }

    @Test
    fun missCompilesOncePutsOnceThenHits() = testApplication {
        if (!TypstCompiler.isAvailable) {
            println("typst not on PATH; skipping PDF compile test")
            return@testApplication
        }
        val cache = RecordingPdfCache()
        application {
            module(
                InMemoryUserRepository(), InMemoryQuestionRepository(),
                JwtService(secret = "test-secret"), esv = null, study = studyService(), pdfCache = cache,
            )
        }
        val api = createClient { }

        val first = api.get("/generate/numbers-index.pdf")
        assertEquals(HttpStatusCode.OK, first.status)
        val firstBytes = first.bodyAsBytes()
        assertEquals("%PDF", firstBytes.decodeToString(0, 4))

        val second = api.get("/generate/numbers-index.pdf")
        assertEquals(HttpStatusCode.OK, second.status)

        // The miss path always compiles-then-puts, so a single put proves the second request never compiled.
        assertEquals(1, cache.puts, "only the first request should compile and store")
        assertEquals(1, cache.hits, "the second request should be served from the cache")
        assertContentEquals(firstBytes, second.bodyAsBytes())
    }

    @Test
    fun staleContentStampMisses() {
        val cache = InMemoryPdfCache()
        cache.put("acts-test", "names-index.pdf", 1, byteArrayOf(1, 2, 3))
        assertNull(cache.get("acts-test", "names-index.pdf", 2), "a changed stamp must invalidate the row")
        assertContentEquals(byteArrayOf(1, 2, 3), cache.get("acts-test", "names-index.pdf", 1))
    }

    @Test
    fun inMemoryCacheEvictsEldestBeyondCapacity() {
        val cache = InMemoryPdfCache(maxEntries = 2)
        cache.put("s", "a.pdf", 1, byteArrayOf(1))
        cache.put("s", "b.pdf", 1, byteArrayOf(2))
        cache.put("s", "c.pdf", 1, byteArrayOf(3))
        assertNull(cache.get("s", "a.pdf", 1), "eldest entry should be evicted at capacity")
        assertContentEquals(byteArrayOf(3), cache.get("s", "c.pdf", 1))
        assertEquals(2, cache.clear())
    }

    @Test
    fun clearEndpointRequiresSeasonManage() = testApplication {
        val users = InMemoryUserRepository()
        users.create("admin@tbb.org", "Admin", null, Passwords.hash("supersecret"), listOf(RoleGrant(Role.ADMIN)))
        val cache = InMemoryPdfCache()
        cache.put("acts-test", "names-index.pdf", 1, byteArrayOf(1, 2, 3))
        application {
            module(
                users, InMemoryQuestionRepository(),
                JwtService(secret = "test-secret"), esv = null, study = null, pdfCache = cache,
            )
        }
        val api = createClient {
            install(ContentNegotiation) { json(json) }
        }

        // Anonymous → 401.
        assertEquals(HttpStatusCode.Unauthorized, api.delete("/generate/cache").status)

        // Contestant → 403 (and the cache is untouched).
        val kid: AuthResponse = json.decodeFromString(
            api.post("/auth/register") {
                contentType(ContentType.Application.Json)
                setBody(RegisterRequest("kid@tbb.org", "password123", "Timothy"))
            }.bodyAsText()
        )
        val forbidden = api.delete("/generate/cache") {
            header(HttpHeaders.Authorization, "Bearer ${kid.token}")
        }
        assertEquals(HttpStatusCode.Forbidden, forbidden.status)

        // Admin → 200 with the cleared count; a repeat clear reports zero.
        val admin: AuthResponse = json.decodeFromString(
            api.post("/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(LoginRequest("admin@tbb.org", "supersecret"))
            }.bodyAsText()
        )
        val cleared = api.delete("/generate/cache") {
            header(HttpHeaders.Authorization, "Bearer ${admin.token}")
        }
        assertEquals(HttpStatusCode.OK, cleared.status)
        assertEquals(ClearPdfCacheResponse(1), json.decodeFromString(cleared.bodyAsText()))
        val again = api.delete("/generate/cache") {
            header(HttpHeaders.Authorization, "Bearer ${admin.token}")
        }
        assertEquals(ClearPdfCacheResponse(0), json.decodeFromString(again.bodyAsText()))
    }
}
