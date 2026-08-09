package net.markdrew.biblebowl.api

import net.markdrew.biblebowl.model.Book
import net.markdrew.biblebowl.model.ChapterRef
import net.markdrew.biblebowl.model.StandardStudySet
import net.markdrew.biblebowl.model.StudyScope
import net.markdrew.biblebowl.model.StudySet

/**
 * The single definition of the study-scope URL contract, shared by the server routes, the typed client,
 * and both app UIs, so the parameter names and omission rules can never drift apart.
 *
 * A scope is carried as `set` (strict study-set slug, see StandardStudySet.bySlug), `book` (strict Book
 * enum name or full name, see core bookByCode), and a book-relative `chapter` (or `throughChapter` for
 * cumulative endpoints). [write] emits scopes in their durable form — parameters are only omitted when
 * derivable from the *other parameters*, never from the current season — so an emitted URL means the
 * same thing in any season, which is what lets study links be reused across the 10-year rotation.
 */
object StudyScopeParams {
    const val SET = "set"
    const val BOOK = "book"
    const val CHAPTER = "chapter"
    const val THROUGH_CHAPTER = "throughChapter"

    /** Study-section slug filter on the study-materials listing (see [StudySection.bySlug]). */
    const val SECTION = "section"

    /** The `set` value meaning "no scope at all" (the full multi-season archive); never valid in [write]. */
    const val ALL = "all"

    /** The raw scope parameters of a request, as read by [read] — not yet validated or resolved. */
    data class RawScope(val set: String?, val book: String?, val chapter: Int?) {
        val isEmpty: Boolean get() = set == null && book == null && chapter == null
    }

    /**
     * Reads the scope parameters out of any parameter lookup (Ktor queryParameters, a JS
     * URLSearchParams, …). [chapterKey] is [THROUGH_CHAPTER] on cumulative endpoints.
     */
    fun read(get: (String) -> String?, chapterKey: String = CHAPTER): RawScope =
        RawScope(set = get(SET), book = get(BOOK), chapter = get(chapterKey)?.toIntOrNull())

    /**
     * Query pairs for [scope] in its durable canonical form:
     * - the `set` slug, unless the scope is exactly one whole book (then `book` alone reproduces it);
     * - `book`, unless the set implies it (single-book set);
     * - the chapter under [chapterKey], when present.
     */
    fun write(scope: StudyScope, chapterKey: String = CHAPTER): List<Pair<String, String>> = buildList {
        val book = scope.book
        val bookOnly = book != null && scope.set == StudySet(book)
        if (!bookOnly) add(SET to scope.set.simpleName)
        if (book != null && (bookOnly || !scope.set.isSingleBook)) add(BOOK to book.name)
        scope.chapter?.let { add(chapterKey to it.toString()) }
    }
}

/**
 * The season's study set, resolved strictly from its slug (falling back to the default). Drives
 * chapter pickers: multi-book or partial sets have gaps and span books, so pickers must iterate
 * [StudySet.chapterRefs]/[StudySet.books] — never `1..`[SeasonDto.chapterCount].
 */
val SeasonDto.resolvedStudySet: StudySet
    get() = StandardStudySet.bySlug(studySet) ?: StandardStudySet.DEFAULT

/**
 * A partial canonical scope selection for study filters: null book = the whole study set; a book
 * alone = that whole book's slice of the set; book + chapter = one chapter. Chapter numbers are
 * always book-relative (never a season-relative index), which is what keeps stored questions and
 * emitted URLs valid across the 10-year study rotation. Shared by the web and Compose pickers.
 */
data class ScopeSelection(val book: Book? = null, val chapter: Int? = null) {
    val chapterRef: ChapterRef? get() = if (book != null && chapter != null) book.chapterRef(chapter) else null

    /** Human label: "Acts 2" (brief book name), just the book for a whole-book slice, null for all. */
    fun label(): String? = book?.let { b -> chapter?.let { "${b.briefName} $it" } ?: b.briefName }
}

/**
 * The canonical scope of [selection] within [set] as query parameters (see [StudyScopeParams.write]):
 * durable — never season-relative — so emitted links keep meaning the same material in any season.
 */
fun scopeQueryParams(
    set: StudySet,
    selection: ScopeSelection,
    chapterKey: String = StudyScopeParams.CHAPTER,
): List<Pair<String, String>> =
    StudyScopeParams.write(StudyScope(set, selection.book ?: set.books.singleOrNull(), selection.chapter), chapterKey)

/**
 * A question's scripture badge: "Acts 2" (brief book name + book-relative chapter), just the book
 * for book-wide questions, or [seasonLabel] + chapter when an old server omitted the bookCode.
 */
fun QuestionDto.scopeLabel(seasonLabel: String): String? {
    val book = bookCode?.let { code -> Book.entries.firstOrNull { it.name == code } }
    return when {
        book != null && chapter != null -> "${book.briefName} $chapter"
        book != null -> book.briefName
        chapter != null -> "$seasonLabel $chapter"
        else -> null
    }
}
