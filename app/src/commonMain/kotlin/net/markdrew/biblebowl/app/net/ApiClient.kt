package net.markdrew.biblebowl.app.net

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import net.markdrew.biblebowl.api.AuthResponse
import net.markdrew.biblebowl.api.LoginRequest
import net.markdrew.biblebowl.api.ModerateQuestionRequest
import net.markdrew.biblebowl.api.QuestionDto
import net.markdrew.biblebowl.api.QuestionStatus
import net.markdrew.biblebowl.api.RegisterRequest
import net.markdrew.biblebowl.api.RoundType
import net.markdrew.biblebowl.api.SubmitQuestionRequest
import net.markdrew.biblebowl.api.UserDto

/**
 * Platform-provided HTTP client, pre-configured with JSON content negotiation.
 * CIO on JVM/desktop/Android, Js on Wasm; iOS adds its own actual later.
 */
expect fun createHttpClient(): HttpClient

/**
 * Thin typed client for the Ktor backend, shared across every platform. Holds the auth token and the
 * signed-in [user] in memory after sign-in so the UI can gate features on [UserDto.permissions].
 */
class TbbApi(private val baseUrl: String = DEFAULT_BASE_URL) {
    private val client: HttpClient = createHttpClient()

    var token: String? = null
        private set
    var user: UserDto? = null
        private set

    val isSignedIn: Boolean get() = token != null

    fun signOut() {
        token = null
        user = null
    }

    private fun HttpRequestBuilder.authorize() {
        token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
    }

    private fun remember(auth: AuthResponse): AuthResponse = auth.also {
        token = it.token
        user = it.user
    }

    suspend fun health(): String = client.get("$baseUrl/health").body()

    suspend fun register(req: RegisterRequest): AuthResponse =
        remember(
            client.post("$baseUrl/auth/register") {
                contentType(ContentType.Application.Json); setBody(req)
            }.body()
        )

    suspend fun login(req: LoginRequest): AuthResponse =
        remember(
            client.post("$baseUrl/auth/login") {
                contentType(ContentType.Application.Json); setBody(req)
            }.body()
        )

    /** Lists questions; the server defaults to APPROVED when [status] is null. */
    suspend fun questions(status: QuestionStatus? = null, chapter: Int? = null): List<QuestionDto> =
        client.get("$baseUrl/questions") {
            authorize()
            if (status != null) parameter("status", status.name)
            if (chapter != null) parameter("chapter", chapter)
        }.body()

    suspend fun submitQuestion(req: SubmitQuestionRequest): QuestionDto =
        client.post("$baseUrl/questions") {
            authorize(); contentType(ContentType.Application.Json); setBody(req)
        }.body()

    suspend fun vote(questionId: String): QuestionDto =
        client.post("$baseUrl/questions/$questionId/vote") { authorize() }.body()

    suspend fun moderate(questionId: String, status: QuestionStatus): QuestionDto =
        client.post("$baseUrl/questions/$questionId/moderate") {
            authorize(); contentType(ContentType.Application.Json); setBody(ModerateQuestionRequest(status))
        }.body()

    /** Fetches a generated practice-test PDF for [round] (optionally chapter-filtered) as raw bytes. */
    suspend fun practiceTestPdf(round: RoundType, chapter: Int? = null): ByteArray =
        client.get("$baseUrl/generate/practice-test.pdf") {
            authorize()
            parameter("round", round.name)
            if (chapter != null) parameter("chapter", chapter)
        }.body()

    companion object {
        // Local dev default; the web build points at the deployed Cloud Run URL at build time.
        const val DEFAULT_BASE_URL = "http://localhost:8080"
    }
}
