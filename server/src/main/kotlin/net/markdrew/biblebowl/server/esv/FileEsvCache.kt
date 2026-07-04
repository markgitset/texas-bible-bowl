package net.markdrew.biblebowl.server.esv

import kotlinx.serialization.json.Json
import net.markdrew.biblebowl.model.ChapterRef
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * On-disk ESV chapter cache for local development (used when no Postgres/DATABASE_URL is configured).
 *
 * Persists each fetched chapter as a JSON file under [dir], so a developer running the server calls the ESV
 * API at most once per chapter, *ever* — every subsequent run reads the text back from disk. This mirrors
 * the original bible-bowl on-disk chapter cache and keeps the licensed ESV budget intact across restarts.
 *
 * The cache is refreshed only on the "first run" (a cache miss for a chapter) or when the developer sets
 * [refresh] (env `ESV_CACHE_REFRESH=true`), which makes [get] ignore existing files so the next access
 * re-fetches and overwrites them.
 *
 * Note: cached ESV text is copyrighted, so [dir] lives outside the repo (default
 * `~/.cache/texas-bible-bowl/esv`) and is never committed.
 */
class FileEsvCache(private val dir: Path, private val refresh: Boolean = false) : EsvCache {
    private val log = LoggerFactory.getLogger(FileEsvCache::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    init {
        dir.createDirectories()
        log.info("ESV chapter cache on disk at {}{}", dir.toAbsolutePath(), if (refresh) " (refresh mode: will re-fetch)" else "")
    }

    private fun fileFor(bookCode: String, chapter: Int): Path = dir.resolve("$bookCode-$chapter.json")

    override fun get(chapterRef: ChapterRef): CachedChapter? {
        if (refresh) return null // developer requested a refresh: force a re-fetch + overwrite
        val file = fileFor(chapterRef.book.name, chapterRef.chapter)
        if (!file.exists()) return null
        return runCatching { json.decodeFromString<CachedChapter>(file.readText()) }
            .onFailure { log.warn("Ignoring corrupt ESV cache file {}: {}", file, it.message) }
            .getOrNull()
    }

    override fun put(chapter: CachedChapter) {
        fileFor(chapter.bookCode, chapter.chapter).writeText(json.encodeToString(chapter))
    }
}
