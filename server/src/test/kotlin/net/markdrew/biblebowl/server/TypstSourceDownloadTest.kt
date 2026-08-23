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
import io.ktor.http.withCharset
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import net.markdrew.biblebowl.api.AuthResponse
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
import net.markdrew.biblebowl.server.routes.LayoutRevisions
import net.markdrew.biblebowl.server.security.JwtService
import net.markdrew.biblebowl.server.security.Passwords
import net.markdrew.biblebowl.server.study.StudyDataRegistry
import net.markdrew.biblebowl.server.study.StudyDataService
import net.markdrew.biblebowl.server.typst.TypstCompiler
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

/**
 * `?format=typ` — the Typst markup behind a generated PDF, served instead of the compiled document.
 *
 * Two things matter and are pinned here: sources built from the ESV text are SEASON_MANAGE-only (the
 * PDF is public, but its source is the same words as machine-readable markup), and a source is cached
 * exactly like the PDF it compiles to — same stamp, `.typ` sibling filename, so both invalidate together.
 */
class TypstSourceDownloadTest {

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

    /** Registers a contestant and logs in the seeded admin, returning their bearer tokens. */
    private suspend fun tokens(api: HttpClient): Pair<String, String> {
        val kid: AuthResponse = json.decodeFromString(
            api.post("/auth/register") {
                contentType(ContentType.Application.Json)
                setBody(RegisterRequest("kid@tbb.org", "password123", "Timothy", birthdate = "2013-05-01"))
            }.bodyAsText()
        )
        val admin: AuthResponse = json.decodeFromString(
            api.post("/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(LoginRequest("admin@tbb.org", "supersecret"))
            }.bodyAsText()
        )
        return kid.token to admin.token
    }

    private fun users() = InMemoryUserRepository().apply {
        create(
            "admin@tbb.org", "Admin", null, adult = true,
            passwordHash = Passwords.hash("supersecret"), roles = listOf(RoleGrant(Role.ADMIN)),
        )
    }

    @Test
    fun esvBackedSourceRequiresSeasonManage() = testApplication {
        application {
            module(
                users(), InMemoryQuestionRepository(),
                JwtService(secret = "test-secret"), esv = null, study = StudyDataRegistry.fixed(studyService()),
            )
        }
        val api = createClient { install(ContentNegotiation) { json(json) } }
        val (kidToken, adminToken) = tokens(api)

        // Anonymous → 401, contestant → 403. The PDF at the same URL stays public either way.
        assertEquals(
            HttpStatusCode.Unauthorized, api.get("/generate/names-index.pdf?format=typ").status,
            "anonymous must not get ESV-derived markup",
        )
        val forbidden = api.get("/generate/names-index.pdf?format=typ") {
            header(HttpHeaders.Authorization, "Bearer $kidToken")
        }
        assertEquals(HttpStatusCode.Forbidden, forbidden.status, "a contestant must not get ESV-derived markup")

        // Admin → the markup itself, named as a .typ attachment.
        val ok = api.get("/generate/names-index.pdf?format=typ") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }
        assertEquals(HttpStatusCode.OK, ok.status)
        assertEquals(ContentType.Text.Plain.withCharset(Charsets.UTF_8), ok.contentType())
        assertTrue(
            "acts-names-index.typ" in ok.headers[HttpHeaders.ContentDisposition].orEmpty(),
            "should attach under the .typ sibling of the PDF's filename",
        )
        val body = ok.bodyAsText()
        assertTrue(body.startsWith("#set page("), "should be Typst markup, got: ${body.take(60)}")
        assertFalse(body.startsWith("%PDF"), "should be the source, not the compiled document")
    }

    /** The gate is on the source, not the endpoint: the PDF at the same URL is still anonymous-friendly. */
    @Test
    fun gatingTheSourceLeavesThePdfPublic() = testApplication {
        if (!TypstCompiler.isAvailable) {
            println("typst not on PATH; skipping PDF compile test")
            return@testApplication
        }
        application {
            module(
                users(), InMemoryQuestionRepository(),
                JwtService(secret = "test-secret"), esv = null, study = StudyDataRegistry.fixed(studyService()),
            )
        }
        val res = createClient { }.get("/generate/names-index.pdf")
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals("%PDF", res.bodyAsBytes().decodeToString(0, 4))
    }

    /**
     * A source is cached like the PDF it compiles to: stored under the `.typ` sibling filename at the
     * very same stamp (content stamp + the generator's layout revision), so a season rollover, a
     * word-list edit, or a [LayoutRevisions] bump retires the source and the document together.
     */
    @Test
    fun sourceIsCachedUnderTheSameStampAsItsPdf() = testApplication {
        val service = studyService()
        val cache = RecordingPdfCache()
        application {
            module(
                users(), InMemoryQuestionRepository(), JwtService(secret = "test-secret"),
                esv = null, study = StudyDataRegistry.fixed(service), pdfCache = cache,
            )
        }
        val api = createClient { install(ContentNegotiation) { json(json) } }
        val (_, adminToken) = tokens(api)

        val first = api.get("/generate/names-index.pdf?format=typ") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }
        assertEquals(HttpStatusCode.OK, first.status)
        assertEquals(
            service.contentStamp() + LayoutRevisions.INDEX, cache.storedStamps["acts-names-index.typ"],
            "the source must be stamped exactly like the PDF it compiles to",
        )
        assertEquals(1, cache.puts, "the first request should build and store the source")

        val second = api.get("/generate/names-index.pdf?format=typ") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }
        assertEquals(HttpStatusCode.OK, second.status)
        assertEquals(1, cache.puts, "the second request should not rebuild the source")
        assertEquals(1, cache.hits, "the second request should be served from the cache")
        assertContentEquals(first.bodyAsBytes(), second.bodyAsBytes())
    }

    /**
     * The source and its PDF occupy different cache rows, so asking for one never hands back the other
     * — the `.typ`/`.pdf` filenames are what keep them apart under a shared (study set, stamp) key.
     */
    @Test
    fun sourceAndPdfAreCachedSeparately() = testApplication {
        if (!TypstCompiler.isAvailable) {
            println("typst not on PATH; skipping PDF compile test")
            return@testApplication
        }
        val cache = RecordingPdfCache()
        application {
            module(
                users(), InMemoryQuestionRepository(), JwtService(secret = "test-secret"),
                esv = null, study = StudyDataRegistry.fixed(studyService()), pdfCache = cache,
            )
        }
        val api = createClient { install(ContentNegotiation) { json(json) } }
        val (_, adminToken) = tokens(api)

        val source = api.get("/generate/numbers-index.pdf?format=typ") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }
        val pdf = api.get("/generate/numbers-index.pdf")
        assertEquals(HttpStatusCode.OK, source.status)
        assertEquals(HttpStatusCode.OK, pdf.status)
        assertTrue(source.bodyAsText().startsWith("#set page("))
        assertEquals("%PDF", pdf.bodyAsBytes().decodeToString(0, 4))
        assertEquals(0, cache.hits, "two different artifacts must both miss on a cold cache")
        assertEquals(
            setOf("acts-numbers-index.typ", "acts-numbers-index.pdf"), cache.storedStamps.keys,
            "each artifact should occupy its own cache row",
        )
    }
}
