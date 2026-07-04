package net.markdrew.biblebowl.server.esv

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.markdrew.biblebowl.model.ChapterRef
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Matches bible-bowl's INDENT_POETRY_LINES; keeps server-rendered text identical to the CLI's output. */
private const val INDENT_POETRY_LINES = 4

/** Minimal projection of the ESV `v3/passage/text` response (see bible-bowl's PassageText). */
@Serializable
data class EsvPassageText(
    val query: String,
    val canonical: String,
    val passages: List<String>,
)

/** A cached chapter of ESV text. */
data class CachedChapter(val bookCode: String, val chapter: Int, val canonical: String, val text: String)

/** Pluggable chapter cache; Postgres in production, in-memory for tests. */
interface EsvCache {
    fun get(chapterRef: ChapterRef): CachedChapter?
    fun put(chapter: CachedChapter)
}

class InMemoryEsvCache : EsvCache {
    private val map = mutableMapOf<String, CachedChapter>()
    override fun get(chapterRef: ChapterRef): CachedChapter? = map[chapterRef.serialize()]
    override fun put(chapter: CachedChapter) {
        map["${chapter.bookCode}${chapter.chapter}"] = chapter
    }
}

/** Thrown when the upstream ESV API rejects or fails a request. */
class EsvUpstreamException(message: String) : Exception(message)

/**
 * Server-side ESV passage service: fetches chapter text from the ESV API (authorized with Mark's
 * non-profit license token) and caches it. The token and this service never leave the server.
 *
 * Formatting parameters mirror bible-bowl's EsvService defaults exactly, so downstream parsing/generation
 * code sees byte-identical text to the original CLI pipeline.
 */
class EsvPassageService(
    private val client: HttpClient,
    private val cache: EsvCache,
    private val token: String? = System.getenv("ESV_API_TOKEN"),
    private val baseUrl: String = "https://api.esv.org",
    /**
     * Minimum spacing between *live* ESV API calls, mirroring bible-bowl's EsvClient
     * `timeBetweenChapters` so we stay polite and avoid being rate-limited when priming the cache (e.g.
     * all 28 chapters of Acts back-to-back). Cache hits are never throttled. Kept a little above 1s
     * because Crossway's limit is ~60 requests/minute — 1s flat can tip over on jitter.
     */
    private val minFetchInterval: Duration = 1_200.milliseconds,
    /** How many times to retry a single chapter after an HTTP 429 (honoring Retry-After) before giving up. */
    private val maxRetriesOn429: Int = 4,
) {
    private val json = Json { ignoreUnknownKeys = true }

    // Serializes upstream fetches and enforces the minimum interval between them.
    private val fetchMutex = Mutex()
    private var lastFetchAtNanos: Long = 0L

    val isConfigured: Boolean get() = !token.isNullOrBlank()

    /** Returns the full text of [chapterRef], from cache when available. */
    suspend fun chapterText(chapterRef: ChapterRef): CachedChapter {
        cache.get(chapterRef)?.let { return it }
        // Only one live fetch at a time; re-check the cache under the lock in case a concurrent
        // request just populated it while we were waiting, so we don't double-fetch.
        return fetchMutex.withLock {
            cache.get(chapterRef) ?: run {
                throttle()
                fetchFromUpstream(chapterRef).also(cache::put)
            }
        }
    }

    /** Delays as needed so consecutive live fetches are at least [minFetchInterval] apart. */
    private suspend fun throttle() {
        if (lastFetchAtNanos != 0L) {
            val elapsed = System.nanoTime() - lastFetchAtNanos
            val waitMillis = (minFetchInterval.inWholeNanoseconds - elapsed) / 1_000_000
            if (waitMillis > 0) delay(waitMillis)
        }
        lastFetchAtNanos = System.nanoTime()
    }

    private suspend fun fetchFromUpstream(chapterRef: ChapterRef): CachedChapter {
        // Same query form as bible-bowl's EsvClient.chapterRefToQuery: packed absolute-verse range.
        val query = with(chapterRef.verseRange()) { "${start.absoluteVerse}-${endInclusive.absoluteVerse}" }
        var attempt = 0
        while (true) {
            val response = requestChapter(query)
            if (response.status == HttpStatusCode.TooManyRequests && attempt < maxRetriesOn429) {
                // Back off and retry — honor Retry-After if present, else grow the wait each attempt.
                val retryAfterSecs = response.headers["Retry-After"]?.toLongOrNull()
                val waitSecs = (retryAfterSecs ?: (2L * (attempt + 1))).coerceIn(1, 60)
                delay(waitSecs * 1_000)
                attempt++
                continue
            }
            if (!response.status.isSuccess()) {
                throw EsvUpstreamException("ESV API returned ${response.status}")
            }
            val passageText: EsvPassageText = json.decodeFromString(response.body<String>())
            val text = passageText.passages.firstOrNull()
                ?: throw EsvUpstreamException("ESV API returned no passage for $chapterRef")

            return CachedChapter(
                bookCode = chapterRef.book.name,
                chapter = chapterRef.chapter,
                canonical = passageText.canonical,
                text = text,
            )
        }
    }

    private suspend fun requestChapter(query: String) = client.get("$baseUrl/v3/passage/text/") {
        token?.let { header("Authorization", "Token $it") }
        parameter("q", query)
        parameter("include-passage-references", false)
        parameter("include-first-verse-numbers", true)
        parameter("include-verse-numbers", true)
        parameter("include-footnotes", true)
        parameter("include-footnote-body", true)
        parameter("include-short-copyright", false)
        parameter("include-copyright", false)
        parameter("include-passage-horizontal-lines", false)
        parameter("include-heading-horizontal-lines", true)
        parameter("horizontal-line-length", 55)
        parameter("include-headings", true)
        parameter("include-selahs", true)
        parameter("indent-using", "space")
        parameter("indent-paragraphs", 2)
        parameter("indent-poetry", true)
        parameter("indent-poetry-lines", INDENT_POETRY_LINES)
        parameter("indent-declares", 40)
        parameter("indent-psalm-doxology", 30)
        parameter("line-length", 0)
    }
}
