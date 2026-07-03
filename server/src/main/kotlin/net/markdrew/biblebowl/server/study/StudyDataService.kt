package net.markdrew.biblebowl.server.study

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.markdrew.biblebowl.api.HeadingDto
import net.markdrew.biblebowl.model.ChapterRef
import net.markdrew.biblebowl.model.Heading
import net.markdrew.biblebowl.model.NO_BOOK_FORMAT
import net.markdrew.biblebowl.model.StandardStudySet
import net.markdrew.biblebowl.model.StudyData
import net.markdrew.biblebowl.model.StudySet
import net.markdrew.biblebowl.server.esv.EsvPassageService
import net.markdrew.biblebowl.ws.EsvIndexer
import net.markdrew.biblebowl.ws.Passage
import net.markdrew.biblebowl.ws.PassageMeta

/**
 * Builds and memoizes the season's fully-indexed [StudyData] from the ESV proxy's chapter cache.
 *
 * Chapters come through [EsvPassageService] (cache-first, so after the first build every request is
 * served from Postgres) and are parsed by the ported bible-bowl [EsvIndexer]. The result is immutable
 * for a given study set, so one in-memory instance serves the process's lifetime.
 */
class StudyDataService(
    private val esv: EsvPassageService,
    val studySet: StudySet = StandardStudySet.DEFAULT,
) {
    private val mutex = Mutex()
    @Volatile private var cached: StudyData? = null

    val isConfigured: Boolean get() = esv.isConfigured

    suspend fun studyData(): StudyData = cached ?: mutex.withLock {
        cached ?: build().also { cached = it }
    }

    private suspend fun build(): StudyData {
        val chapterRefs: List<ChapterRef> = studySet.chapterRanges.flatMap { range ->
            require(range.start.book == range.endInclusive.book) {
                "Chapter ranges spanning books are not supported: $range"
            }
            (range.start.chapter..range.endInclusive.chapter).map { range.start.book.chapterRef(it) }
        }
        val passages: List<Passage> = chapterRefs.map { ref ->
            val chapter = esv.chapterText(ref)
            val verseRange = ref.verseRange()
            val absRange = verseRange.start.absoluteVerse..verseRange.endInclusive.absoluteVerse
            Passage(
                canonical = chapter.canonical,
                range = absRange,
                meta = PassageMeta(
                    canonical = chapter.canonical,
                    chapterStart = listOf(absRange.first, absRange.last),
                    chapterEnd = listOf(absRange.first, absRange.last),
                    prevVerse = null, nextVerse = null, prevChapter = null, nextChapter = null,
                ),
                text = chapter.text,
            )
        }
        return EsvIndexer(studySet).indexBook(passages.asSequence())
    }
}

/** Maps a domain [Heading] to its wire form. */
fun Heading.toDto(): HeadingDto = HeadingDto(
    title = title,
    reference = verseRange.format(NO_BOOK_FORMAT),
    chapter = chapterRange.start.chapter,
    index = index,
    total = maxIndex,
)
