package net.markdrew.biblebowl.model

/**
 * A resolved, canonical scripture scope for study materials: a study set, optionally narrowed to one of
 * its books, and optionally to a single (book-relative) chapter.
 *
 * This is the app-wide answer to "which part of the Bible is this URL/question/PDF about?": scopes are
 * expressed in permanent scripture coordinates (book + chapter), never in season-relative ones, so
 * study-material links and community questions stay valid across the 10-year study rotation. Build one
 * from URL parameters with [resolveStudyScope].
 */
data class StudyScope(val set: StudySet, val book: Book?, val chapter: Int?) {

    /** Non-null exactly when both [book] and [chapter] are set — the canonical single-chapter scope. */
    val chapterRef: ChapterRef? get() = if (book != null && chapter != null) ChapterRef(book, chapter) else null

    /** Chapter ranges this scope covers: one chapter, one book's slice of the set, or the whole set. */
    fun ranges(): List<ChapterRange> {
        val ref = chapterRef
        return when {
            ref != null -> listOf(ref..ref)
            book != null -> set.rangesIn(book)
            else -> set.chapterRanges
        }
    }

    /**
     * Cumulative form of this scope: the set's ranges from its first chapter through [chapterRef],
     * skipping any gaps the set doesn't cover. Unlike [StudySet.toChapter] (one contiguous range), this
     * never includes out-of-set chapters, so it is safe for filtering as well as text generation.
     *
     * @throws IllegalStateException if this scope has no [chapterRef]
     */
    fun through(): List<ChapterRange> {
        val ref = checkNotNull(chapterRef) { "A cumulative scope needs a book and a chapter, but was: $this" }
        return set.chapterRanges.mapNotNull { range ->
            when {
                range.endInclusive <= ref -> range
                range.start <= ref -> range.start..ref
                else -> null
            }
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
 */
fun resolveStudyScope(
    setParam: String?,
    bookParam: String?,
    chapter: Int?,
    currentSeasonSet: StudySet,
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
        chapter != null -> return ScopeResolution.Invalid(ScopeError.BookRequired(set))
        else -> null
    }
    if (chapter != null) {
        // book is non-null on every path that reaches here with a chapter (BookRequired otherwise)
        val b = checkNotNull(book)
        if (chapter !in 1..b.chapterCount || b.chapterRef(chapter) !in set) {
            return ScopeResolution.Invalid(ScopeError.ChapterNotInSet(b, chapter, set))
        }
    }
    return ScopeResolution.Resolved(StudyScope(set, book, chapter))
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
