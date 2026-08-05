package net.markdrew.biblebowl.api

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
