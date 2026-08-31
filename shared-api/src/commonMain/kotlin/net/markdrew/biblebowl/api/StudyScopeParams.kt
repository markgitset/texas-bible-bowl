package net.markdrew.biblebowl.api

import net.markdrew.biblebowl.model.Book
import net.markdrew.biblebowl.model.ChapterRef
import net.markdrew.biblebowl.model.ScopeResolution
import net.markdrew.biblebowl.model.StandardStudySet
import net.markdrew.biblebowl.model.StudyScope
import net.markdrew.biblebowl.model.StudySet
import net.markdrew.biblebowl.model.resolveStudyScope

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

    /** First chapter of an explicit range; pairs with [THROUGH_CHAPTER] (or runs to the set's end). */
    const val FROM_CHAPTER = "fromChapter"

    /** Study-section slug filter on the study-materials listing (see [StudySection.bySlug]). */
    const val SECTION = "section"

    /** The `set` value meaning "no scope at all" (the full multi-season archive); never valid in [write]. */
    const val ALL = "all"

    /** Every key that carries part of a scope — what a canonical rewrite has to replace wholesale. */
    fun scopeKeys(chapterKey: String = CHAPTER): Set<String> = setOf(SET, BOOK, FROM_CHAPTER, chapterKey)

    /** The raw scope parameters of a request, as read by [read] — not yet validated or resolved. */
    data class RawScope(val set: String?, val book: String?, val chapter: Int?, val fromChapter: Int? = null) {
        val isEmpty: Boolean get() = set == null && book == null && chapter == null && fromChapter == null
    }

    /**
     * Reads the scope parameters out of any parameter lookup (Ktor queryParameters, a JS
     * URLSearchParams, …). [chapterKey] is [THROUGH_CHAPTER] on cumulative endpoints; [FROM_CHAPTER]
     * is read the same way everywhere, since narrowing the start means the same thing on both.
     */
    fun read(get: (String) -> String?, chapterKey: String = CHAPTER): RawScope = RawScope(
        set = get(SET),
        book = get(BOOK),
        chapter = get(chapterKey)?.toIntOrNull(),
        fromChapter = get(FROM_CHAPTER)?.toIntOrNull(),
    )

    /**
     * Query pairs for [scope] in its durable canonical form:
     * - the `set` slug, unless the scope is exactly one whole book (then `book` alone reproduces it);
     * - `book`, unless the set implies it (single-book set);
     * - the chapter span, spelled the shortest way that reproduces it.
     *
     * That last spelling is *derived* from [StudyScope.chapters] rather than tracked alongside it, so
     * there is only ever one way to write a given scope: ends equal spells `chapterKey=N`, a span
     * starting at the set's own first chapter drops the redundant start, and anything else needs both
     * [FROM_CHAPTER] and [chapterKey]. A pre-range link therefore emits exactly what it did before.
     */
    fun write(scope: StudyScope, chapterKey: String = CHAPTER): List<Pair<String, String>> = buildList {
        val book = scope.book
        val bookOnly = book != null && scope.set == StudySet(book)
        if (!bookOnly) add(SET to scope.set.simpleName)
        if (book != null && (bookOnly || !scope.set.isSingleBook)) add(BOOK to book.name)
        val span = scope.chapters ?: return@buildList
        val setStart = scope.set.chapterRanges.first().start
        val runsToSetEnd = span.endInclusive == scope.set.chapterRanges.last().endInclusive
        // A start is only dropped where the spelling re-derives it. On a cumulative key a missing
        // start always means the set's first chapter — so anything else must be spelled, including a
        // single chapter (`throughChapter=7` alone reads back as 1-7, so "just 7" needs its start).
        // On an exact key a single chapter or a span running to the set's end re-derives its own
        // start (so the whole set still spells as nothing at all); a named endpoint doesn't —
        // `chapter=5` alone reads back as chapter 5.
        val needsStart =
            if (chapterKey == THROUGH_CHAPTER) span.start != setStart
            else span.start != span.endInclusive && !(runsToSetEnd && span.start == setStart)
        if (needsStart) add(FROM_CHAPTER to span.start.chapter.toString())
        // A span running to the set's end has no endpoint of its own to name — `fromChapter` alone
        // already says "from here on", and naming the last chapter would only go stale if the set grew.
        if (span.endInclusive != scope.set.chapterRanges.last().endInclusive || span.start == span.endInclusive) {
            add(chapterKey to span.endInclusive.chapter.toString())
        }
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
 * alone = that whole book's slice of the set; both chapters set = that explicit span. Chapter numbers
 * are always book-relative (never a season-relative index), which is what keeps stored questions and
 * emitted URLs valid across the 10-year study rotation. Shared by the web and Compose pickers.
 *
 * On a range picker both ends are always explicit (the first [tap] fills the start in), and [chapter]
 * holds the most recent tap — the anchor the next tap pairs with — so it may sit *below*
 * [fromChapter]; everything that interprets the selection reads it through [normalized]. On a
 * single-select picker [fromChapter] stays null and [chapter] means exactly that chapter.
 */
data class ScopeSelection(
    val book: Book? = null,
    val chapter: Int? = null,
    val fromChapter: Int? = null,
) {
    val chapterRef: ChapterRef? get() = if (book != null && chapter != null) book.chapterRef(chapter) else null

    /** The other end the user picked, if any — the tap before the one [chapterRef] holds. */
    val fromChapterRef: ChapterRef?
        get() = if (book != null && fromChapter != null) book.chapterRef(fromChapter) else null

    /**
     * The same selection with its ends in ascending order — the only span order labels, chips, and
     * URLs may spell, while the raw field order keeps the tap anchor (see the class doc).
     */
    fun normalized(): ScopeSelection =
        if (chapter != null && fromChapter != null && fromChapter > chapter) {
            copy(chapter = fromChapter, fromChapter = chapter)
        } else this

    /**
     * Human label: "Acts 2", "Acts 1-5"/"Acts 3-7" for a span, just the book for a whole-book slice,
     * null for all. The dash form matches [ChapterRange.format], which is what the server labels
     * ranges with.
     */
    fun label(): String? {
        val n = normalized()
        return n.book?.let { b ->
            when {
                n.chapter != null && n.fromChapter != null && n.fromChapter != n.chapter ->
                    "${b.briefName} ${n.fromChapter}-${n.chapter}"
                n.chapter != null -> "${b.briefName} ${n.chapter}"
                n.fromChapter != null -> "${b.briefName} ${n.fromChapter}-"
                else -> b.briefName
            }
        }
    }
}

/**
 * Whether the chapter chip for [ref] should read as selected — the whole span this selection covers,
 * not just the end the user last tapped. Shared by both pickers so the chips can't disagree with
 * what the emitted URL asks for.
 */
fun ScopeSelection.lights(ref: ChapterRef, set: StudySet): Boolean {
    val n = normalized()
    if (n.chapter == null && n.fromChapter == null) return false
    val start = n.fromChapterRef ?: n.chapterRef
    val end = n.chapterRef ?: set.chapterRanges.last().endInclusive
    return start != null && ref in start..end
}

/**
 * The next selection after tapping the chip for [chapter] of [book] — the one shared gesture behind
 * both pickers (Compose and web), so their behavior can't drift.
 *
 * A single-select picker ([range] = false) just toggles: tapping selects the chapter, tapping the
 * selected chapter clears it.
 *
 * A range picker pairs every tap with the tap before it. The first tap reaches back to the book's
 * first in-set chapter (tap 5 = chapters 1-5), and each later tap spans from the previous tap to the
 * tapped chapter in whichever order they come — 3, 7, 9 reads 1-3, 3-7, 7-9. Re-tapping the
 * last-tapped chapter steps down to just that chapter, and one more tap clears. The anchor lives in
 * [ScopeSelection.chapter], possibly below fromChapter — see [ScopeSelection.normalized].
 */
fun ScopeSelection.tap(set: StudySet, book: Book, chapter: Int, range: Boolean = false): ScopeSelection {
    val sameBook = this.book == book
    if (!range) return ScopeSelection(book, chapter.takeIf { !sameBook || it != this.chapter })
    val previous = this.chapter.takeIf { sameBook }
    return when {
        previous == null ->
            ScopeSelection(book, chapter, fromChapter = set.chapterRefs.first { it.book == book }.chapter)
        // Re-tapping a lone selected chapter clears it (the end of the through-N -> just-N cycle) —
        // to the same state as the All chip, so a single-book set's label goes fully quiet.
        chapter == previous && chapter == fromChapter ->
            if (set.isSingleBook) ScopeSelection() else ScopeSelection(book)
        else -> ScopeSelection(book, chapter, fromChapter = previous)
    }
}

/**
 * [this] selection resolved to the canonical scope it names within [set] — the same resolution the
 * server performs on the emitted parameters, so a client-side file name or label always matches what
 * the server will actually generate. [chapterKey] decides what a lone endpoint means, exactly as it
 * does server-side. An unresolvable selection falls back to the unchaptered scope (the server would
 * reject the bad half anyway, with a message the client can't improve on).
 */
fun ScopeSelection.resolveWithin(set: StudySet, chapterKey: String = StudyScopeParams.CHAPTER): StudyScope {
    val n = normalized()
    val resolution = resolveStudyScope(
        setParam = set.simpleName,
        bookParam = (n.book ?: set.books.singleOrNull())?.name,
        chapter = n.chapter,
        currentSeasonSet = set,
        fromChapter = n.fromChapter,
        cumulative = chapterKey == StudyScopeParams.THROUGH_CHAPTER,
    )
    return (resolution as? ScopeResolution.Resolved)?.scope
        ?: StudyScope(set, n.book ?: set.books.singleOrNull(), chapters = null)
}

/**
 * The canonical scope of [selection] within [set] as query parameters (see [StudyScopeParams.write]):
 * durable — never season-relative — so emitted links keep meaning the same material in any season.
 * [chapterKey] decides what a lone endpoint means, exactly as it does server-side.
 */
fun scopeQueryParams(
    set: StudySet,
    selection: ScopeSelection,
    chapterKey: String = StudyScopeParams.CHAPTER,
): List<Pair<String, String>> = StudyScopeParams.write(selection.resolveWithin(set, chapterKey), chapterKey)

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
