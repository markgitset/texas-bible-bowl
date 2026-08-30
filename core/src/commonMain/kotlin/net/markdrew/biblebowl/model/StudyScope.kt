package net.markdrew.biblebowl.model

/**
 * A resolved, canonical scripture scope for study materials: a study set, optionally narrowed to one of
 * its books, and optionally to a contiguous span of (book-relative) chapters.
 *
 * This is the app-wide answer to "which part of the Bible is this URL/question/PDF about?": scopes are
 * expressed in permanent scripture coordinates (book + chapter), never in season-relative ones, so
 * study-material links and community questions stay valid across the 10-year study rotation. Build one
 * from URL parameters with [resolveStudyScope].
 *
 * [chapters] is the one representation of every chapter narrowing there is: a single chapter is a range
 * whose ends match, a cumulative "through chapter 5" starts at the set's first chapter, and an explicit
 * "chapters 3-7" is just both ends given. Like [StudySet.toChapter] it is *contiguous* — it may span
 * books, and it may cover chapters a partial set skips — so it is a selection, not an enumeration. Run
 * it through [ranges] to get the gap-free, per-book ranges the set actually covers.
 */
data class StudyScope(val set: StudySet, val book: Book?, val chapters: ChapterRange?) {

    /**
     * Non-null only for a scope of exactly one chapter — the shape single-chapter consumers (file-name
     * suffixes, the text-generated practice tests) need. A multi-chapter scope is null here on purpose:
     * these callers have no meaning for a span, so they must not silently see one end of it.
     */
    val singleChapterRef: ChapterRef?
        get() = chapters?.takeIf { it.start == it.endInclusive }?.start

    /** Whether [ref] falls inside this scope — the scope's own filter, honoring book and chapter span. */
    fun covers(ref: ChapterRef): Boolean = when {
        chapters != null -> ref in chapters
        book != null -> ref.book == book
        else -> true
    }

    /**
     * The chapter ranges this scope actually covers, in Biblical order: [chapters] clipped to the set's
     * own ranges (so the gaps of a partial set are never included), one book's slice of the set, or the
     * whole set. Safe for filtering and for text generation alike.
     */
    fun ranges(): List<ChapterRange> {
        val span = chapters ?: return if (book != null) set.rangesIn(book) else set.chapterRanges
        return set.chapterRanges.mapNotNull { range ->
            val start = maxOf(range.start, span.start)
            val end = minOf(range.endInclusive, span.endInclusive)
            if (start <= end) start..end else null
        }
    }
}

/** Why a (set, book, chapter) URL triple could not be resolved to a [StudyScope]. */
sealed interface ScopeError {
    /** Stable machine-readable code (e.g. "book_required") suitable for an API error payload. */
    val code: String

    /** Human-readable explanation of the rejection. */
    val message: String

    data class UnknownSet(val slug: String) : ScopeError {
        override val code: String get() = "unknown_set"
        override val message: String get() = "Unknown study set '$slug'"
    }

    data class UnknownBook(val bookParam: String) : ScopeError {
        override val code: String get() = "unknown_book"
        override val message: String get() = "Unknown book '$bookParam'"
    }

    data class BookNotInSet(val book: Book, val set: StudySet) : ScopeError {
        override val code: String get() = "book_not_in_set"
        override val message: String get() = "${book.fullName} is not part of the ${set.name} study set"
    }

    data class BookRequired(val set: StudySet) : ScopeError {
        override val code: String get() = "book_required"
        override val message: String
            get() = "The ${set.name} study set spans multiple books " +
                "(${set.books.joinToString { it.fullName }}), so a chapter number needs an explicit book"
    }

    data class ChapterNotInSet(val book: Book, val chapter: Int, val set: StudySet) : ScopeError {
        override val code: String get() = "chapter_not_in_set"
        override val message: String get() = "${book.fullName} $chapter is not part of the ${set.name} study set"
    }

    data class BackwardsChapterRange(val book: Book, val from: Int, val through: Int) : ScopeError {
        override val code: String get() = "backwards_chapter_range"
        override val message: String
            get() = "${book.fullName} $from-$through runs backwards — the first chapter must not follow the last"
    }
}

/** Result of [resolveStudyScope]: a canonical [StudyScope], or the [ScopeError] explaining the rejection. */
sealed interface ScopeResolution {
    data class Resolved(val scope: StudyScope) : ScopeResolution
    data class Invalid(val error: ScopeError) : ScopeResolution
}

/**
 * Strict [Book] lookup for URL/wire values: the exact enum name (e.g. "ACT") or the exact full name
 * ("Acts"), case-insensitive. Unlike [Book.parse], this never prefix-matches and never falls back to a
 * default (Book.parse defaults unrecognized input to Matthew — a footgun for URLs).
 */
fun bookByCode(code: String): Book? = Book.entries.firstOrNull {
    it.name.equals(code, ignoreCase = true) || it.fullName.equals(code, ignoreCase = true)
}

/**
 * Resolves URL-style scope parameters to a canonical [StudyScope].
 *
 * [setParam] is a strict study-set slug (see [StandardStudySet.bySlug]); [bookParam] is a strict book
 * code or full name (see [bookByCode]); [chapter] is always book-relative. The book resolves as:
 * explicit [bookParam] → the set's only book (when single-book) → null for a whole-(multi-book)-set
 * scope. A multi-book set with a [chapter] but no book is [ScopeError.BookRequired]: "chapter N of a
 * multi-book set" is not a real study unit (which book's N?) and has no cumulative meaning.
 *
 * A bare [bookParam] with no [setParam] scopes to that whole book (as a single-book [StudySet]),
 * independent of [currentSeasonSet] — that's what keeps off-year links like `?book=JOH&chapter=3`
 * durable. Callers wanting an unscoped everything-query (e.g. `set=all`) special-case that value
 * before calling this.
 *
 * [chapter] is the endpoint the caller's own parameter spelled, and [cumulative] says what a bare one
 * means: `false` (a `chapter=N` route) narrows to that chapter alone, `true` (a `throughChapter=N`
 * route) reaches back to the set's first chapter. [fromChapter] overrides that start in either case,
 * which is what turns the pair into an explicit "chapters 3-7"; on its own it runs to the set's end.
 */
fun resolveStudyScope(
    setParam: String?,
    bookParam: String?,
    chapter: Int?,
    currentSeasonSet: StudySet,
    fromChapter: Int? = null,
    cumulative: Boolean = false,
): ScopeResolution {
    val explicitBook: Book? = bookParam?.let {
        bookByCode(it) ?: return ScopeResolution.Invalid(ScopeError.UnknownBook(it))
    }
    val set: StudySet = when {
        setParam != null ->
            StandardStudySet.bySlug(setParam) ?: return ScopeResolution.Invalid(ScopeError.UnknownSet(setParam))
        explicitBook != null -> StudySet(explicitBook)
        else -> currentSeasonSet
    }
    val book: Book? = when {
        explicitBook != null ->
            if (explicitBook in set.books) explicitBook
            else return ScopeResolution.Invalid(ScopeError.BookNotInSet(explicitBook, set))
        set.isSingleBook -> set.books.single()
        chapter != null || fromChapter != null -> return ScopeResolution.Invalid(ScopeError.BookRequired(set))
        else -> null
    }
    // book is non-null on every path that reaches here with a chapter (BookRequired otherwise)
    listOfNotNull(chapter, fromChapter).forEach { ch ->
        val b = checkNotNull(book)
        if (ch !in 1..b.chapterCount || b.chapterRef(ch) !in set) {
            return ScopeResolution.Invalid(ScopeError.ChapterNotInSet(b, ch, set))
        }
    }
    if (chapter != null && fromChapter != null && fromChapter > chapter) {
        return ScopeResolution.Invalid(ScopeError.BackwardsChapterRange(checkNotNull(book), fromChapter, chapter))
    }
    val chapters: ChapterRange? = when {
        book == null -> null
        chapter == null && fromChapter == null -> null
        // An explicit start always wins; otherwise a cumulative endpoint reaches back to the set's
        // first chapter (which may sit in an earlier book) and an exact one starts where it ends.
        else -> {
            val end = chapter?.let { book.chapterRef(it) } ?: set.chapterRanges.last().endInclusive
            val start = fromChapter?.let { book.chapterRef(it) }
                ?: if (cumulative) set.chapterRanges.first().start else end
            start..end
        }
    }
    return ScopeResolution.Resolved(StudyScope(set, book, chapters))
}

/** Books ordered longest-full-name-first, so "Judges 5" can never match "Jude" before "Judges". */
private val booksByNameLength: List<Book> by lazy { Book.entries.sortedByDescending { it.fullName.length } }

/**
 * The book a question is about, inferred from its stored verse references; null when nothing parses.
 *
 * Handles both reference shapes that occur in the wild: the printed form ("Acts 1:1", "1 Samuel 3:4" —
 * full book name followed by a space) and the serialized form ("ACT2:38" — [Book] enum name followed
 * directly by a digit). The first parseable reference wins, so a rare multi-book question is scoped to
 * its primary (first-listed) book.
 */
fun bookFromRefs(refs: List<String>): Book? = refs.firstNotNullOfOrNull { ref ->
    val trimmed = ref.trim()
    val serialized = Book.entries.firstOrNull { book ->
        trimmed.startsWith(book.name, ignoreCase = true) && trimmed.getOrNull(book.name.length)?.isDigit() == true
    }
    serialized ?: booksByNameLength.firstOrNull { trimmed.startsWith(it.fullName + " ", ignoreCase = true) }
}
