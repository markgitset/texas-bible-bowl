package net.markdrew.biblebowl.server.study

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.markdrew.biblebowl.analysis.AnnotationStore
import net.markdrew.biblebowl.analysis.WordIndexEntryC
import net.markdrew.biblebowl.analysis.numbersIndex
import net.markdrew.biblebowl.api.HeadingDto
import net.markdrew.biblebowl.api.IndexEntryDto
import net.markdrew.biblebowl.api.IndexRefDto
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

    /** Cumulative live ESV API calls made by the backing service this process (for usage auditing). */
    val esvCallCount: Long get() = esv.liveCallCount

    suspend fun studyData(): StudyData = cached ?: mutex.withLock {
        cached ?: build().also { cached = it }
    }

    /** The season's numbers index (every numeral/cardinal/ordinal/fraction → its verses) as wire DTOs. */
    suspend fun numbers(): List<IndexEntryDto> = numbersIndex(studyData()).map { it.toDto() }

    @Volatile private var annotationStore: AnnotationStore? = null

    /**
     * The memoized text-annotation store for this study set (word-list category resolution + curated
     * overrides). Computed once per process; its resolution is what drives name/number highlighting.
     */
    suspend fun annotations(): AnnotationStore = annotationStore ?: mutex.withLock {
        annotationStore ?: AnnotationStore(studyData(), cacheDir = null).also { annotationStore = it }
    }

    private suspend fun build(): StudyData {
        val chapterRefs: List<ChapterRef> = studySet.chapterRanges.flatMap { range ->
            require(range.start.book == range.endInclusive.book) {
                "Chapter ranges spanning books are not supported: $range"
            }
            // StudySet ranges can use an open-ended sentinel upper bound (Book.lastChapterRef ≈ chapter 999)
            // to mean "to end of book". Clamp to the book's real chapter count so we never fire ESV calls
            // for chapters that don't exist — every live ESV call costs against the licence budget.
            val lastChapter = minOf(range.endInclusive.chapter, range.start.book.chapterCount)
            (range.start.chapter..lastChapter).map { range.start.book.chapterRef(it) }
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

/** Maps a count-carrying word/number index entry to its wire form (references formatted within-book). */
fun WordIndexEntryC.toDto(): IndexEntryDto = IndexEntryDto(
    key = key,
    total = values.sumOf { it.count },
    references = values.map { IndexRefDto(reference = it.item.format(NO_BOOK_FORMAT), count = it.count) },
)

/** Maps a domain [Heading] to its wire form. */
fun Heading.toDto(): HeadingDto = HeadingDto(
    title = title,
    reference = verseRange.format(NO_BOOK_FORMAT),
    chapter = chapterRange.start.chapter,
    index = index,
    total = maxIndex,
)
