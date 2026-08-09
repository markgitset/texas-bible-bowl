package net.markdrew.biblebowl.server

import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.delete
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import net.markdrew.biblebowl.api.ApiError
import net.markdrew.biblebowl.api.AuthResponse
import net.markdrew.biblebowl.api.LoginRequest
import net.markdrew.biblebowl.api.RegisterRequest
import net.markdrew.biblebowl.api.Role
import net.markdrew.biblebowl.api.RoleGrant
import net.markdrew.biblebowl.api.StudyMaterialDto
import net.markdrew.biblebowl.api.StudyMaterialType
import net.markdrew.biblebowl.api.StudyMaterialsResponse
import net.markdrew.biblebowl.api.StudySection
import net.markdrew.biblebowl.api.UpsertStudyMaterialRequest
import net.markdrew.biblebowl.server.data.InMemoryQuestionRepository
import net.markdrew.biblebowl.server.data.InMemoryUserRepository
import net.markdrew.biblebowl.server.data.UserRepository
import net.markdrew.biblebowl.server.security.JwtService
import net.markdrew.biblebowl.server.security.Passwords
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StudyMaterialRoutesTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun ApplicationTestBuilder.jsonClient(): HttpClient = createClient {
        install(ContentNegotiation) { json(json) }
        defaultRequest { contentType(ContentType.Application.Json) }
    }

    /** No default content type — multipart bodies carry their own boundary type. */
    private fun ApplicationTestBuilder.multipartClient(): HttpClient = createClient {
        install(ContentNegotiation) { json(json) }
    }

    private suspend fun HttpClient.loginSeededAdmin(users: UserRepository): AuthResponse {
        users.create(
            "admin@tbb.org", "Admin", null, adult = true,
            passwordHash = Passwords.hash("supersecret"), roles = listOf(RoleGrant(Role.ADMIN)),
        )
        return json.decodeFromString(
            post("/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(LoginRequest("admin@tbb.org", "supersecret"))
            }.bodyAsText()
        )
    }

    private suspend fun HttpClient.signUp(email: String, name: String): AuthResponse =
        json.decodeFromString(
            post("/auth/register") {
                contentType(ContentType.Application.Json)
                setBody(RegisterRequest(email, "password123", name, adult = true))
            }.bodyAsText()
        )

    private suspend inline fun <reified T> HttpResponse.body(): T = json.decodeFromString<T>(bodyAsText())

    private fun link(
        title: String,
        section: StudySection = StudySection.PRACTICE_TESTS,
        url: String = "https://quizlet.com/some-set",
        set: String = "acts",
    ) = UpsertStudyMaterialRequest(
        studySet = set, section = section, type = StudyMaterialType.LINK, title = title, url = url,
    )

    private fun documentUpload(
        metadata: UpsertStudyMaterialRequest,
        fileName: String,
        contentType: String,
        bytes: ByteArray,
    ) = MultiPartFormDataContent(formData {
        append("metadata", json.encodeToString(UpsertStudyMaterialRequest.serializer(), metadata))
        append(
            "file", bytes,
            Headers.build {
                append(HttpHeaders.ContentType, contentType)
                append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
            },
        )
    })

    @Test
    fun listingIsPublicAndMutationsAreAdminGated() = testApplication {
        val users = InMemoryUserRepository()
        application { module(users, InMemoryQuestionRepository(), JwtService(secret = "test-secret")) }
        val api = jsonClient()

        // Public list, valid empty; strict slug validation on both filters.
        assertEquals(emptyList(), api.get("/study-materials?set=acts").body<List<StudyMaterialDto>>())
        assertEquals("unknown_set", api.get("/study-materials?set=bogus").body<ApiError>().code)
        assertEquals(
            "unknown_section",
            api.get("/study-materials?set=acts&section=bogus").body<ApiError>().code,
        )

        // Anonymous and non-admin mutations are rejected; nothing is created.
        assertEquals(HttpStatusCode.Unauthorized, api.post("/study-materials") { setBody(link("Kahoot")) }.status)
        val user = api.signUp("student@tbb.org", "Stu Dent")
        val forbidden = api.post("/study-materials") {
            header(HttpHeaders.Authorization, "Bearer ${user.token}")
            setBody(link("Kahoot"))
        }
        assertEquals(HttpStatusCode.Forbidden, forbidden.status)
        assertEquals(emptyList(), api.get("/study-materials?set=acts").body<List<StudyMaterialDto>>())
    }

    @Test
    fun linkLifecycleWithValidation() = testApplication {
        val users = InMemoryUserRepository()
        application { module(users, InMemoryQuestionRepository(), JwtService(secret = "test-secret")) }
        val api = jsonClient()
        val admin = api.loginSeededAdmin(users)
        suspend fun post(req: UpsertStudyMaterialRequest): HttpResponse = api.post("/study-materials") {
            header(HttpHeaders.Authorization, "Bearer ${admin.token}"); setBody(req)
        }

        assertEquals("invalid_material", post(link(title = "  ")).body<ApiError>().code)
        assertEquals("unknown_set", post(link("Kahoot", set = "bogus")).body<ApiError>().code)
        assertEquals("invalid_url", post(link("Kahoot", url = "javascript:alert(1)")).body<ApiError>().code)
        assertEquals("invalid_url", post(link("Kahoot", url = "not a url")).body<ApiError>().code)
        // An unknown section slug fails enum decoding before the handler ever sees it.
        val badSection = api.post("/study-materials") {
            header(HttpHeaders.Authorization, "Bearer ${admin.token}")
            setBody("""{"studySet":"acts","section":"nope","type":"LINK","title":"X","url":"https://x.org"}""")
        }
        assertTrue(badSection.status.value >= 400, "expected 4xx, got ${badSection.status}")

        val created = post(link("Quizlet flashcards")).body<StudyMaterialsResponse>()
        val material = created.materials.single()
        assertEquals(StudyMaterialType.LINK, material.type)
        assertEquals("https://quizlet.com/some-set", material.url)
        assertNull(material.fileName)

        // The public listing carries it, filtered by section.
        val listed = api.get("/study-materials?set=acts&section=practice-tests").body<List<StudyMaterialDto>>()
        assertEquals(listOf(material.id), listed.map { it.id })
        assertEquals(
            emptyList(),
            api.get("/study-materials?set=acts&section=the-text").body<List<StudyMaterialDto>>(),
        )

        // Metadata edit sticks; the type is immutable.
        val edited = api.put("/study-materials/${material.id}") {
            header(HttpHeaders.Authorization, "Bearer ${admin.token}")
            setBody(link("Quizlet (updated)", section = StudySection.GENERAL))
        }.body<StudyMaterialsResponse>()
        assertEquals("Quizlet (updated)", edited.materials.single().title)
        assertEquals(StudySection.GENERAL, edited.materials.single().section)
        val typeChange = api.put("/study-materials/${material.id}") {
            header(HttpHeaders.Authorization, "Bearer ${admin.token}")
            setBody(link("X").copy(type = StudyMaterialType.DOCUMENT))
        }
        assertEquals("type_immutable", typeChange.body<ApiError>().code)

        val afterDelete = api.delete("/study-materials/${material.id}") {
            header(HttpHeaders.Authorization, "Bearer ${admin.token}")
        }.body<StudyMaterialsResponse>()
        assertEquals(emptyList(), afterDelete.materials)
        assertEquals(
            HttpStatusCode.NotFound,
            api.delete("/study-materials/${material.id}") {
                header(HttpHeaders.Authorization, "Bearer ${admin.token}")
            }.status,
        )
    }

    @Test
    fun documentUploadRoundTripsByteExact() = testApplication {
        val users = InMemoryUserRepository()
        application { module(users, InMemoryQuestionRepository(), JwtService(secret = "test-secret")) }
        val api = multipartClient()
        val admin = api.loginSeededAdmin(users)

        // Not a real PDF — the server must not care: bytes are stored and served exactly as sent.
        val bytes = ByteArray(4096) { (it % 251).toByte() }
        val metadata = UpsertStudyMaterialRequest(
            studySet = "acts",
            section = StudySection.PRACTICE_TESTS,
            type = StudyMaterialType.DOCUMENT,
            title = "2026 Round 1 test",
            description = "The real thing, as administered.",
        )
        val created = api.post("/study-materials") {
            header(HttpHeaders.Authorization, "Bearer ${admin.token}")
            setBody(documentUpload(metadata, "2026-round-1.pdf", "application/pdf", bytes))
        }.body<StudyMaterialsResponse>()
        val material = created.materials.single()
        assertEquals("2026-round-1.pdf", material.fileName)
        assertEquals("application/pdf", material.contentType)
        assertEquals(bytes.size.toLong(), material.fileSize)

        // Unauthenticated download: byte-identical, original content type + filename.
        val download = api.get("/study-materials/${material.id}/file")
        assertEquals(HttpStatusCode.OK, download.status)
        assertContentEquals(bytes, download.readRawBytes())
        assertEquals("application/pdf", download.headers[HttpHeaders.ContentType])
        val disposition = download.headers[HttpHeaders.ContentDisposition].orEmpty()
        assertTrue("attachment" in disposition && "2026-round-1.pdf" in disposition, disposition)

        // Uploads without a metadata or file part are rejected.
        val noFile = api.post("/study-materials") {
            header(HttpHeaders.Authorization, "Bearer ${admin.token}")
            setBody(MultiPartFormDataContent(formData {
                append("metadata", json.encodeToString(UpsertStudyMaterialRequest.serializer(), metadata))
            }))
        }
        assertEquals(HttpStatusCode.BadRequest, noFile.status)

        // Deleting the material takes the download with it.
        api.delete("/study-materials/${material.id}") { header(HttpHeaders.Authorization, "Bearer ${admin.token}") }
        assertEquals(HttpStatusCode.NotFound, api.get("/study-materials/${material.id}/file").status)
    }

    @Test
    fun oversizedUploadIsRejected() = testApplication {
        val users = InMemoryUserRepository()
        application { module(users, InMemoryQuestionRepository(), JwtService(secret = "test-secret")) }
        val api = multipartClient()
        val admin = api.loginSeededAdmin(users)

        val oversized = ByteArray(25 * 1024 * 1024 + 1)
        val metadata = UpsertStudyMaterialRequest(
            studySet = "acts", section = StudySection.PRACTICE_TESTS,
            type = StudyMaterialType.DOCUMENT, title = "Too big",
        )
        val response = api.post("/study-materials") {
            header(HttpHeaders.Authorization, "Bearer ${admin.token}")
            setBody(documentUpload(metadata, "big.pdf", "application/pdf", oversized))
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        assertEquals(emptyList(), api.get("/study-materials?set=acts").body<List<StudyMaterialDto>>())
    }

    @Test
    fun manualReorderRewritesPositions() = testApplication {
        val users = InMemoryUserRepository()
        application { module(users, InMemoryQuestionRepository(), JwtService(secret = "test-secret")) }
        val api = jsonClient()
        val admin = api.loginSeededAdmin(users)
        suspend fun post(req: UpsertStudyMaterialRequest): StudyMaterialsResponse = api.post("/study-materials") {
            header(HttpHeaders.Authorization, "Bearer ${admin.token}"); setBody(req)
        }.body()

        post(link("Alpha"))
        post(link("Beta"))
        val ids = post(link("Gamma")).materials.map { it.id }
        assertEquals(3, ids.size)

        // Creates append in order…
        assertEquals(
            listOf("Alpha", "Beta", "Gamma"),
            api.get("/study-materials?set=acts").body<List<StudyMaterialDto>>().map { it.title },
        )

        // …and a reorder (proving /order never binds as an {id}) rewrites the display order.
        val reversed = api.put("/study-materials/order") {
            header(HttpHeaders.Authorization, "Bearer ${admin.token}")
            setBody("""{"orderedIds":${ids.reversed().map { "\"$it\"" }}}""")
        }.body<StudyMaterialsResponse>()
        assertEquals(listOf("Gamma", "Beta", "Alpha"), reversed.materials.map { it.title })

        val unknown = api.put("/study-materials/order") {
            header(HttpHeaders.Authorization, "Bearer ${admin.token}")
            setBody("""{"orderedIds":["nope"]}""")
        }
        assertEquals("unknown_material", unknown.body<ApiError>().code)
    }
}
