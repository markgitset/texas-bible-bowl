package net.markdrew.biblebowl.server.esv

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.markdrew.biblebowl.model.ChapterRef

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
) {
    private val json = Json { ignoreUnknownKeys = true }

    val isConfigured: Boolean get() = !token.isNullOrBlank()

    /** Returns the full text of [chapterRef], from cache when available. */
    suspend fun chapterText(chapterRef: ChapterRef): CachedChapter {
        cache.get(chapterRef)?.let { return it }

        // Same query form as bible-bowl's EsvClient.chapterRefToQuery: packed absolute-verse range.
        val query = with(chapterRef.verseRange()) { "${start.absoluteVerse}-${endInclusive.absoluteVerse}" }
        val response = client.get("$baseUrl/v3/passage/text/") {
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
        ).also(cache::put)
    }
}
