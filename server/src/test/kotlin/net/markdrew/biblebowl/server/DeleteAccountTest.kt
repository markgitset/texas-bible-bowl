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
import net.markdrew.biblebowl.api.DeleteAccountRequest
import net.markdrew.biblebowl.api.LoginRequest
import net.markdrew.biblebowl.api.RegisterRequest
import net.markdrew.biblebowl.api.Role
import net.markdrew.biblebowl.api.RoleGrant
import net.markdrew.biblebowl.server.data.InMemoryQuestionRepository
import net.markdrew.biblebowl.server.data.InMemoryUserRepository
import net.markdrew.biblebowl.server.security.JwtService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DeleteAccountTest {

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

    private suspend fun HttpClient.delete(token: String, password: String) = post("/auth/delete-account") {
        header(HttpHeaders.Authorization, "Bearer $token")
        setBody(DeleteAccountRequest(password))
    }

    @Test
    fun deletingKillsTheSessionAndFreesTheEmail() = testApplication {
        application {
            module(InMemoryUserRepository(), InMemoryQuestionRepository(), JwtService(secret = "test-secret"))
        }
        val api = apiClient()
        val auth = api.register("gone@example.com", "original-pw")

        assertEquals(HttpStatusCode.OK, api.delete(auth.token, "original-pw").status)

        // The still-unexpired token no longer names anyone, and the password no longer signs in.
        assertEquals(
            HttpStatusCode.Unauthorized,
            api.get("/auth/me") { header(HttpHeaders.Authorization, "Bearer ${auth.token}") }.status,
        )
        assertEquals(
            HttpStatusCode.Unauthorized,
            api.post("/auth/login") { setBody(LoginRequest("gone@example.com", "original-pw")) }.status,
        )
        assertEquals(HttpStatusCode.Unauthorized, api.delete(auth.token, "original-pw").status)

        // Nothing of the old account blocks signing up again with the same email.
        val again = api.register("gone@example.com", "another-pw-1")
        assertTrue(again.user.id != auth.user.id)
    }

    @Test
    fun wrongPasswordDeletesNothing() = testApplication {
        application {
            module(InMemoryUserRepository(), InMemoryQuestionRepository(), JwtService(secret = "test-secret"))
        }
        val api = apiClient()
        val auth = api.register("keep@example.com", "original-pw")

        val res = api.delete(auth.token, "not-my-password")
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertTrue("bad_current_password" in res.bodyAsText())
        assertEquals(
            HttpStatusCode.OK,
            api.post("/auth/login") { setBody(LoginRequest("keep@example.com", "original-pw")) }.status,
        )
    }

    @Test
    fun administratorsMustHandOffTheRoleFirst() = testApplication {
        val users = InMemoryUserRepository()
        application { module(users, InMemoryQuestionRepository(), JwtService(secret = "test-secret")) }
        val api = apiClient()
        val auth = api.register("boss@example.com", "original-pw")
        users.addRoleGrant(auth.user.id, RoleGrant(Role.ADMIN))

        val res = api.delete(auth.token, "original-pw")
        assertEquals(HttpStatusCode.Conflict, res.status)
        assertTrue("admin_account" in res.bodyAsText())
        assertNotNull(users.findById(auth.user.id))
    }

    @Test
    fun deletingRequiresAuth() = testApplication {
        application {
            module(InMemoryUserRepository(), InMemoryQuestionRepository(), JwtService(secret = "test-secret"))
        }
        val res = apiClient().post("/auth/delete-account") { setBody(DeleteAccountRequest("whatever")) }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
    }
}
