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
import net.markdrew.biblebowl.api.ForgotPasswordRequest
import net.markdrew.biblebowl.api.LoginRequest
import net.markdrew.biblebowl.api.RegisterRequest
import net.markdrew.biblebowl.api.ResetPasswordRequest
import net.markdrew.biblebowl.server.data.InMemoryPasswordResetRepository
import net.markdrew.biblebowl.server.data.InMemoryQuestionRepository
import net.markdrew.biblebowl.server.data.InMemoryUserRepository
import net.markdrew.biblebowl.server.data.ResetCode
import net.markdrew.biblebowl.server.email.EmailService
import net.markdrew.biblebowl.server.security.JwtService
import net.markdrew.biblebowl.server.security.Passwords
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Captures outbound mail so tests can read the emailed reset code. */
private class RecordingEmailService : EmailService {
    val sent = mutableListOf<Triple<String, String, String>>() // (to, subject, body)
    override fun send(to: String, subject: String, body: String) {
        sent += Triple(to, subject, body)
    }

    /** The 6-digit code inside the most recent email. */
    fun lastCode(): String = Regex("\\d{6}").find(sent.last().third)!!.value
}

class PasswordResetTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun ApplicationTestBuilder.apiClient(): HttpClient = createClient {
        install(ContentNegotiation) { json(json) }
        defaultRequest { contentType(ContentType.Application.Json) }
    }

    private suspend fun HttpClient.register(email: String, password: String) {
        val res = post("/auth/register") {
            setBody(RegisterRequest(email, password, "Test User", adult = true))
        }
        assertEquals(HttpStatusCode.Created, res.status)
    }

    @Test
    fun fullResetFlow() = testApplication {
        val users = InMemoryUserRepository()
        val resets = InMemoryPasswordResetRepository()
        val email = RecordingEmailService()
        application {
            module(users, InMemoryQuestionRepository(), JwtService(secret = "test-secret"), resets = resets, email = email)
        }
        val api = apiClient()
        api.register("reset@example.com", "original-pw")

        // Request a code (case/whitespace-insensitive email match).
        val forgot = api.post("/auth/forgot-password") { setBody(ForgotPasswordRequest(" Reset@Example.com ")) }
        assertEquals(HttpStatusCode.OK, forgot.status)
        assertEquals(1, email.sent.size)
        assertEquals("reset@example.com", email.sent.single().first)
        val code = email.lastCode()

        // A wrong code is rejected with the generic error.
        val wrong = api.post("/auth/reset-password") {
            setBody(ResetPasswordRequest("reset@example.com", "000000".takeUnless { it == code } ?: "000001", "new-password-1"))
        }
        assertEquals(HttpStatusCode.BadRequest, wrong.status)
        assertTrue(wrong.bodyAsText().contains("invalid_code"))

        // The right code resets the password and signs the user in.
        val reset = api.post("/auth/reset-password") {
            setBody(ResetPasswordRequest("reset@example.com", code, "new-password-1"))
        }
        assertEquals(HttpStatusCode.OK, reset.status)
        val auth = json.decodeFromString<AuthResponse>(reset.bodyAsText())
        val me = api.get("/auth/me") { header(HttpHeaders.Authorization, "Bearer ${auth.token}") }
        assertEquals(HttpStatusCode.OK, me.status)

        // Old password out, new password in, code single-use.
        assertEquals(
            HttpStatusCode.Unauthorized,
            api.post("/auth/login") { setBody(LoginRequest("reset@example.com", "original-pw")) }.status,
        )
        assertEquals(
            HttpStatusCode.OK,
            api.post("/auth/login") { setBody(LoginRequest("reset@example.com", "new-password-1")) }.status,
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            api.post("/auth/reset-password") {
                setBody(ResetPasswordRequest("reset@example.com", code, "new-password-2"))
            }.status,
        )
    }

    @Test
    fun unknownEmailAnswersOkWithoutSending() = testApplication {
        val email = RecordingEmailService()
        application {
            module(InMemoryUserRepository(), InMemoryQuestionRepository(), JwtService(secret = "test-secret"), email = email)
        }
        val api = apiClient()
        val res = api.post("/auth/forgot-password") { setBody(ForgotPasswordRequest("nobody@example.com")) }
        assertEquals(HttpStatusCode.OK, res.status) // indistinguishable from a real account
        assertEquals(0, email.sent.size)
    }

    @Test
    fun expiredCodeIsRejected() = testApplication {
        val users = InMemoryUserRepository()
        val resets = InMemoryPasswordResetRepository()
        application {
            module(users, InMemoryQuestionRepository(), JwtService(secret = "test-secret"), resets = resets)
        }
        val api = apiClient()
        api.register("expired@example.com", "original-pw")
        val user = assertNotNull(users.findByEmail("expired@example.com"))
        resets.save(ResetCode(user.id, Passwords.hash("123456"), expiresAtEpochMs = System.currentTimeMillis() - 1))

        val res = api.post("/auth/reset-password") {
            setBody(ResetPasswordRequest("expired@example.com", "123456", "new-password-1"))
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
    }

    @Test
    fun fiveWrongGuessesInvalidateTheCode() = testApplication {
        val email = RecordingEmailService()
        application {
            module(InMemoryUserRepository(), InMemoryQuestionRepository(), JwtService(secret = "test-secret"), email = email)
        }
        val api = apiClient()
        api.register("bruteforce@example.com", "original-pw")
        api.post("/auth/forgot-password") { setBody(ForgotPasswordRequest("bruteforce@example.com")) }
        val code = email.lastCode()

        repeat(5) {
            val wrong = "%06d".format(if ("%06d".format(it) == code) 999_990 + it else it)
            val res = api.post("/auth/reset-password") {
                setBody(ResetPasswordRequest("bruteforce@example.com", wrong, "new-password-1"))
            }
            assertEquals(HttpStatusCode.BadRequest, res.status)
        }
        // Even the CORRECT code is dead now.
        val res = api.post("/auth/reset-password") {
            setBody(ResetPasswordRequest("bruteforce@example.com", code, "new-password-1"))
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
    }

    @Test
    fun shortNewPasswordIsRejected() = testApplication {
        application {
            module(InMemoryUserRepository(), InMemoryQuestionRepository(), JwtService(secret = "test-secret"))
        }
        val api = apiClient()
        val res = api.post("/auth/reset-password") {
            setBody(ResetPasswordRequest("x@example.com", "123456", "short"))
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
    }
}
