package net.markdrew.biblebowl.server

import io.ktor.client.HttpClient
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
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import net.markdrew.biblebowl.api.AuthResponse
import net.markdrew.biblebowl.api.ChangePasswordRequest
import net.markdrew.biblebowl.api.LoginRequest
import net.markdrew.biblebowl.api.RegisterRequest
import net.markdrew.biblebowl.server.data.InMemoryQuestionRepository
import net.markdrew.biblebowl.server.data.InMemoryUserRepository
import net.markdrew.biblebowl.server.security.JwtService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChangePasswordTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun ApplicationTestBuilder.apiClient(): HttpClient = createClient {
        install(ContentNegotiation) { json(json) }
        defaultRequest { contentType(ContentType.Application.Json) }
    }

    private suspend fun HttpClient.register(email: String, password: String): AuthResponse {
        val res = post("/auth/register") {
            setBody(RegisterRequest(email, password, "Test User", adult = true))
        }
        assertEquals(HttpStatusCode.Created, res.status)
        return json.decodeFromString<AuthResponse>(res.bodyAsText())
    }

    @Test
    fun changePasswordSwapsOldForNew() = testApplication {
        application {
            module(InMemoryUserRepository(), InMemoryQuestionRepository(), JwtService(secret = "test-secret"))
        }
        val api = apiClient()
        val auth = api.register("change@example.com", "original-pw")

        val res = api.post("/auth/change-password") {
            header(HttpHeaders.Authorization, "Bearer ${auth.token}")
            setBody(ChangePasswordRequest("original-pw", "new-password-1"))
        }
        assertEquals(HttpStatusCode.OK, res.status)

        // Old password out, new password in; the existing token still works.
        assertEquals(
            HttpStatusCode.Unauthorized,
            api.post("/auth/login") { setBody(LoginRequest("change@example.com", "original-pw")) }.status,
        )
        assertEquals(
            HttpStatusCode.OK,
            api.post("/auth/login") { setBody(LoginRequest("change@example.com", "new-password-1")) }.status,
        )
        assertEquals(
            HttpStatusCode.OK,
            api.get("/auth/me") { header(HttpHeaders.Authorization, "Bearer ${auth.token}") }.status,
        )
    }

    @Test
    fun wrongCurrentPasswordIsRejected() = testApplication {
        application {
            module(InMemoryUserRepository(), InMemoryQuestionRepository(), JwtService(secret = "test-secret"))
        }
        val api = apiClient()
        val auth = api.register("wrong@example.com", "original-pw")

        val res = api.post("/auth/change-password") {
            header(HttpHeaders.Authorization, "Bearer ${auth.token}")
            setBody(ChangePasswordRequest("not-the-password", "new-password-1"))
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertTrue(res.bodyAsText().contains("bad_current_password"))

        // Password unchanged.
        assertEquals(
            HttpStatusCode.OK,
            api.post("/auth/login") { setBody(LoginRequest("wrong@example.com", "original-pw")) }.status,
        )
    }

    @Test
    fun shortNewPasswordIsRejected() = testApplication {
        application {
            module(InMemoryUserRepository(), InMemoryQuestionRepository(), JwtService(secret = "test-secret"))
        }
        val api = apiClient()
        val auth = api.register("short@example.com", "original-pw")

        val res = api.post("/auth/change-password") {
            header(HttpHeaders.Authorization, "Bearer ${auth.token}")
            setBody(ChangePasswordRequest("original-pw", "short"))
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
    }

    @Test
    fun changePasswordRequiresAuth() = testApplication {
        application {
            module(InMemoryUserRepository(), InMemoryQuestionRepository(), JwtService(secret = "test-secret"))
        }
        val api = apiClient()
        val res = api.post("/auth/change-password") {
            setBody(ChangePasswordRequest("whatever-pw", "new-password-1"))
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
    }
}
