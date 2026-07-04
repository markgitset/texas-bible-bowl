package net.markdrew.biblebowl.server

import net.markdrew.biblebowl.model.Book
import net.markdrew.biblebowl.server.esv.CachedChapter
import net.markdrew.biblebowl.server.esv.FileEsvCache
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FileEsvCacheTest {

    private val acts2 = CachedChapter("ACT", 2, "Acts 2", "The Coming of the Holy Spirit ...")

    @Test
    fun missReturnsNullAndStoredChapterRoundTrips() {
        val dir = createTempDirectory("esv-cache-test")
        val cache = FileEsvCache(dir)

        assertNull(cache.get(Book.ACT.chapterRef(2)), "empty cache is a miss (first run)")
        cache.put(acts2)
        assertEquals(acts2, cache.get(Book.ACT.chapterRef(2)), "stored chapter round-trips from disk")
    }

    @Test
    fun persistsAcrossInstances() {
        val dir = createTempDirectory("esv-cache-test")
        FileEsvCache(dir).put(acts2)

        // A fresh instance over the same dir (simulating a server restart) still serves from disk —
        // no ESV call needed.
        assertEquals(acts2, FileEsvCache(dir).get(Book.ACT.chapterRef(2)))
    }

    @Test
    fun refreshModeIgnoresExistingFilesSoTheChapterIsReFetched() {
        val dir = createTempDirectory("esv-cache-test")
        FileEsvCache(dir).put(acts2)

        // Developer requested a refresh: get() reports a miss even though the file exists, forcing a
        // re-fetch (which will then overwrite the file).
        assertNull(FileEsvCache(dir, refresh = true).get(Book.ACT.chapterRef(2)))
        // Non-refresh instances still see it.
        assertEquals(acts2, FileEsvCache(dir).get(Book.ACT.chapterRef(2)))
    }
}
