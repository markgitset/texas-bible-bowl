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
import net.markdrew.biblebowl.api.AuthResponse
import net.markdrew.biblebowl.api.ModerateQuestionRequest
import net.markdrew.biblebowl.api.Permission
import net.markdrew.biblebowl.api.QuestionDto
import net.markdrew.biblebowl.api.QuestionStatus
import net.markdrew.biblebowl.api.RegisterRequest
import net.markdrew.biblebowl.api.Role
import net.markdrew.biblebowl.api.RoleGrant
import net.markdrew.biblebowl.api.SubmitQuestionRequest
import net.markdrew.biblebowl.api.RoundType
import net.markdrew.biblebowl.server.data.InMemoryQuestionRepository
import net.markdrew.biblebowl.server.data.InMemoryUserRepository
import net.markdrew.biblebowl.server.security.JwtService
import net.markdrew.biblebowl.server.security.Passwords
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApplicationTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun healthReportsSeason() = testApplication {
        val users = InMemoryUserRepository()
        val questions = InMemoryQuestionRepository()
        application { module(users, questions, JwtService(secret = "test-secret")) }

        val res = client.get("/health")
        assertEquals(HttpStatusCode.OK, res.status)
        assertTrue(res.bodyAsText().contains("Acts"))
    }

    @Test
    fun rbacGovernsQuestionModeration() = testApplication {
        val users = InMemoryUserRepository()
        val questions = InMemoryQuestionRepository()
        val jwt = JwtService(secret = "test-secret")
        // Pre-seed a global admin.
        users.create("admin@tbb.org", "Admin", null, Passwords.hash("supersecret"), listOf(RoleGrant(Role.ADMIN)))
        application { module(users, questions, jwt) }

        val api = createClient {
            install(ContentNegotiation) { json(json) }
            defaultRequest { contentType(ContentType.Application.Json) }
        }

        // A contestant registers.
        val reg = api.post("/auth/register") {
            setBody(RegisterRequest("kid@tbb.org", "password123", "Timothy", grade = 8))
        }
        assertEquals(HttpStatusCode.Created, reg.status)
        val contestant: AuthResponse = json.decodeFromString(reg.bodyAsText())
        assertTrue(Permission.QUESTION_SUBMIT in contestant.user.permissions)
        assertTrue(Permission.QUESTION_MODERATE !in contestant.user.permissions)

        // Contestant submits a question.
        val submit = api.post("/questions") {
            header(HttpHeaders.Authorization, "Bearer ${contestant.token}")
            setBody(
                SubmitQuestionRequest(
                    roundType = RoundType.FIND_THE_VERSE,
                    prompt = "\"Repent and be baptized every one of you\"",
                    answer = "Acts 2:38",
                    references = listOf("Acts 2:38"),
                    chapter = 2,
                )
            )
        }
        assertEquals(HttpStatusCode.Created, submit.status)
        val q: QuestionDto = json.decodeFromString(submit.bodyAsText())
        assertEquals(QuestionStatus.PENDING, q.status)

        // Contestant CANNOT moderate -> 403.
        val forbidden = api.post("/questions/${q.id}/moderate") {
            header(HttpHeaders.Authorization, "Bearer ${contestant.token}")
            setBody(ModerateQuestionRequest(QuestionStatus.APPROVED))
        }
        assertEquals(HttpStatusCode.Forbidden, forbidden.status)

        // Admin logs in and approves -> 200.
        val adminLogin = api.post("/auth/login") {
            setBody(net.markdrew.biblebowl.api.LoginRequest("admin@tbb.org", "supersecret"))
        }
        assertEquals(HttpStatusCode.OK, adminLogin.status)
        val admin: AuthResponse = json.decodeFromString(adminLogin.bodyAsText())

        val approve = api.post("/questions/${q.id}/moderate") {
            header(HttpHeaders.Authorization, "Bearer ${admin.token}")
            setBody(ModerateQuestionRequest(QuestionStatus.APPROVED))
        }
        assertEquals(HttpStatusCode.OK, approve.status)
        val approved: QuestionDto = json.decodeFromString(approve.bodyAsText())
        assertEquals(QuestionStatus.APPROVED, approved.status)

        // The approved question now shows up in the default (approved) listing.
        val list = api.get("/questions") {
            header(HttpHeaders.Authorization, "Bearer ${contestant.token}")
        }
        assertEquals(HttpStatusCode.OK, list.status)
        assertTrue(list.bodyAsText().contains("Acts 2:38"))
    }
}
