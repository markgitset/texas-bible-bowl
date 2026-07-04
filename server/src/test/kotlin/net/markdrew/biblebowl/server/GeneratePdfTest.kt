package net.markdrew.biblebowl.server

import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import net.markdrew.biblebowl.api.AuthResponse
import net.markdrew.biblebowl.api.ModerateQuestionRequest
import net.markdrew.biblebowl.api.QuestionStatus
import net.markdrew.biblebowl.api.RegisterRequest
import net.markdrew.biblebowl.api.Role
import net.markdrew.biblebowl.api.RoleGrant
import net.markdrew.biblebowl.model.Round
import net.markdrew.biblebowl.api.SubmitQuestionRequest
import net.markdrew.biblebowl.server.data.InMemoryQuestionRepository
import net.markdrew.biblebowl.server.data.InMemoryUserRepository
import net.markdrew.biblebowl.server.security.JwtService
import net.markdrew.biblebowl.server.security.Passwords
import net.markdrew.biblebowl.server.typst.TypstCompiler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Exercises the practice-test PDF endpoint with the real `typst` binary; skips where typst is absent. */
class GeneratePdfTest {

    @Test
    fun generatesPdfFromApprovedQuestions() = testApplication {
        if (!TypstCompiler.isAvailable) { println("typst not on PATH — skipping"); return@testApplication }

        val users = InMemoryUserRepository()
        val questions = InMemoryQuestionRepository()
        val jwt = JwtService(secret = "test-secret")
        users.create("admin@tbb.org", "Admin", null, Passwords.hash("supersecret"), listOf(RoleGrant(Role.ADMIN)))
        application { module(users, questions, jwt) }

        val json = Json { ignoreUnknownKeys = true }
        val api = createClient {
            install(ContentNegotiation) { json(json) }
        }

        // Register a contestant, submit two Fact Finder questions, approve them as admin.
        val kid: AuthResponse = json.decodeFromString(
            api.post("/auth/register") {
                contentType(ContentType.Application.Json)
                setBody(RegisterRequest("kid@tbb.org", "password123", "Timothy", 8))
            }.bodyAsText()
        )
        val admin: AuthResponse = json.decodeFromString(
            api.post("/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(net.markdrew.biblebowl.api.LoginRequest("admin@tbb.org", "supersecret"))
            }.bodyAsText()
        )
        repeat(2) { n ->
            val q = json.decodeFromString<net.markdrew.biblebowl.api.QuestionDto>(
                api.post("/questions") {
                    header(HttpHeaders.Authorization, "Bearer ${kid.token}")
                    contentType(ContentType.Application.Json)
                    setBody(
                        SubmitQuestionRequest(
                            roundType = Round.FACT_FINDER,
                            prompt = "Question number $n about Pentecost?",
                            answer = "Answer $n",
                            choices = listOf("Answer $n", "Wrong", "Also wrong"),
                            chapter = 2,
                        )
                    )
                }.bodyAsText()
            )
            api.post("/questions/${q.id}/moderate") {
                header(HttpHeaders.Authorization, "Bearer ${admin.token}")
                contentType(ContentType.Application.Json)
                setBody(ModerateQuestionRequest(QuestionStatus.APPROVED))
            }
        }

        // Unauthenticated -> 401.
        assertEquals(HttpStatusCode.Unauthorized, api.get("/generate/practice-test.pdf?round=FACT_FINDER").status)

        // Authenticated -> valid PDF bytes.
        val res = api.get("/generate/practice-test.pdf?round=FACT_FINDER&chapter=2") {
            header(HttpHeaders.Authorization, "Bearer ${kid.token}")
        }
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals(ContentType.Application.Pdf, res.contentType())
        val bytes = res.readRawBytes()
        assertTrue(bytes.size > 1000, "PDF should be non-trivial, got ${bytes.size} bytes")
        assertEquals("%PDF", bytes.decodeToString(0, 4), "must start with PDF magic")

        // No matching questions -> 404.
        val none = api.get("/generate/practice-test.pdf?round=POWER") {
            header(HttpHeaders.Authorization, "Bearer ${kid.token}")
        }
        assertEquals(HttpStatusCode.NotFound, none.status)
    }
}
