package net.markdrew.biblebowl.app.net

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import net.markdrew.biblebowl.api.ApiError
import net.markdrew.biblebowl.api.AuthResponse
import net.markdrew.biblebowl.api.HeadingDto
import net.markdrew.biblebowl.api.IndexEntryDto
import net.markdrew.biblebowl.api.LoginRequest
import net.markdrew.biblebowl.api.ModerateQuestionRequest
import net.markdrew.biblebowl.api.QuestionDto
import net.markdrew.biblebowl.api.QuestionStatus
import net.markdrew.biblebowl.api.RegisterRequest
import net.markdrew.biblebowl.model.Round
import net.markdrew.biblebowl.api.SubmitQuestionRequest
import net.markdrew.biblebowl.api.UserDto

/**
 * Platform-provided HTTP client, pre-configured with JSON content negotiation.
 * CIO on JVM/desktop/Android, Js on Wasm; iOS adds its own actual later.
 */
expect fun createHttpClient(): HttpClient

/**
 * Per-request timeout for the shared client. Generous on purpose: the Fly backend scales to zero, so the
 * first request after idle waits out a JVM cold start (+ Postgres connect) that can take ~10-15s.
 */
internal const val BACKEND_REQUEST_TIMEOUT_MS: Long = 30_000L

/**
 * The backend base URL for this platform. Web reads it from a `window.TBB_BACKEND_URL` global (injected
 * into the published page), so the same Wasm bundle runs against localhost in dev and the deployed
 * backend once served from GitHub Pages. Desktop/Android default to localhost for now.
 */
expect fun defaultBaseUrl(): String

/**
 * Thin typed client for the Ktor backend, shared across every platform. Holds the auth token and the
 * signed-in [user] in memory after sign-in so the UI can gate features on [UserDto.permissions].
 */
class TbbApi(private val baseUrl: String = defaultBaseUrl()) {
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
    suspend fun practiceTestPdf(round: Round, chapter: Int? = null): ByteArray =
        client.get("$baseUrl/generate/practice-test.pdf") {
            authorize()
            parameter("round", round.name)
            if (chapter != null) parameter("chapter", chapter)
        }.pdfBytesOrThrow()

    /** Fetches a duplex flashcard deck PDF built from approved questions (filters optional). */
    suspend fun flashcardsPdf(chapter: Int? = null, round: Round? = null): ByteArray =
        client.get("$baseUrl/generate/flashcards.pdf") {
            authorize()
            if (chapter != null) parameter("chapter", chapter)
            if (round != null) parameter("round", round.name)
        }.pdfBytesOrThrow()

    /** Lists the season book's ESV section headings (Round 5 material), optionally through a chapter. */
    suspend fun headings(throughChapter: Int? = null): List<HeadingDto> =
        client.get("$baseUrl/study/headings") {
            authorize()
            if (throughChapter != null) parameter("throughChapter", throughChapter)
        }.body()

    /** Fetches a chapter-headings flashcard deck PDF, optionally limited through a chapter. */
    suspend fun headingFlashcardsPdf(throughChapter: Int? = null): ByteArray =
        client.get("$baseUrl/generate/heading-flashcards.pdf") {
            authorize()
            if (throughChapter != null) parameter("throughChapter", throughChapter)
        }.pdfBytesOrThrow()

    /** Lists the season's numbers index (every numeral/cardinal/ordinal/fraction and the verses it occurs in). */
    suspend fun numbersIndex(): List<IndexEntryDto> =
        client.get("$baseUrl/study/numbers") { authorize() }.body()

    /** Fetches the numbers-index PDF (alphabetical + by-frequency sections). */
    suspend fun numbersIndexPdf(): ByteArray =
        client.get("$baseUrl/generate/numbers-index.pdf") { authorize() }.pdfBytesOrThrow()

    /**
     * Returns the response body as PDF bytes, or throws [PdfException] with the server's error message on a
     * non-2xx status. Without this the client would happily "download" a JSON error body as a `.pdf`, which
     * the browser then reports as a corrupt/failed PDF with no clue what actually went wrong.
     */
    private suspend fun HttpResponse.pdfBytesOrThrow(): ByteArray {
        if (status.isSuccess()) return body()
        val raw = runCatching { bodyAsText() }.getOrNull()
        val message = raw
            ?.let { runCatching { errorJson.decodeFromString<ApiError>(it).message }.getOrNull() }
            ?: raw?.takeIf { it.isNotBlank() }
            ?: "Server returned $status"
        throw PdfException(message)
    }

    companion object {
        private val errorJson = Json { ignoreUnknownKeys = true }

        // Local dev default; the web build points at the deployed Cloud Run URL at build time.
        const val DEFAULT_BASE_URL = "http://localhost:8080"
    }
}

/** Thrown when a PDF request fails; [message] carries the server's human-readable reason. */
class PdfException(message: String) : Exception(message)
