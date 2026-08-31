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
        val runsToSetEnd = span.endInclusive == scope.set.chapterRanges.last().endInclusive
        // A start is only dropped where the spelling re-derives it: a cumulative endpoint reaches
        // back to the set's first chapter, and a span running to the set's end reads a missing start
        // the same way (so the whole set still spells as nothing at all). On an exact-chapter key
        // with a named endpoint it must be spelled out — `chapter=5` alone reads back as chapter 5.
        val startRederived = chapterKey == THROUGH_CHAPTER || runsToSetEnd
        if (span.start != span.endInclusive &&
            !(startRederived && span.start == scope.set.chapterRanges.first().start)
        ) {
            add(FROM_CHAPTER to span.start.chapter.toString())
        }
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
 * alone = that whole book's slice of the set; book + [chapter] = through that chapter, narrowed at the
 * front by an optional [fromChapter]. Chapter numbers are always book-relative (never a season-relative
 * index), which is what keeps stored questions and emitted URLs valid across the 10-year study
 * rotation. Shared by the web and Compose pickers.
 *
 * The picker holds the two ends the user actually clicked and leaves the rest to [resolveStudyScope]:
 * a null [fromChapter] means "wherever this scope naturally starts", which the exact and cumulative
 * endpoints then read differently. That keeps the selection honest about what was chosen — filling in
 * a start here would make "through chapter 5" and "chapters 1-5" indistinguishable.
 */
data class ScopeSelection(
    val book: Book? = null,
    val chapter: Int? = null,
    val fromChapter: Int? = null,
) {
    val chapterRef: ChapterRef? get() = if (book != null && chapter != null) book.chapterRef(chapter) else null

    /** The start the user picked, if any — the other end of [chapterRef]. */
    val fromChapterRef: ChapterRef?
        get() = if (book != null && fromChapter != null) book.chapterRef(fromChapter) else null

    /** True when this selection names a span rather than a single chapter or a whole book/set. */
    val isRange: Boolean get() = fromChapter != null && fromChapter != chapter

    /**
     * Human label: "Acts 2", "Acts 3-7" for a span, just the book for a whole-book slice, null for all.
     * The dash form matches [ChapterRange.format], which is what the server labels ranges with.
     */
    fun label(): String? = book?.let { b ->
        when {
            chapter != null && fromChapter != null && fromChapter != chapter -> "${b.briefName} $fromChapter-$chapter"
            chapter != null -> "${b.briefName} $chapter"
            fromChapter != null -> "${b.briefName} $fromChapter-"
            else -> b.briefName
        }
    }
}

/**
 * Whether the chapter chip for [ref] should read as selected — the whole span this selection covers,
 * not just the end the user last clicked. On a [cumulative] picker an endpoint alone reaches back to
 * the start of [set]; with both ends given it is the range between them. Shared by both pickers so the
 * chips can't disagree with what the emitted URL asks for.
 */
fun ScopeSelection.lights(ref: ChapterRef, cumulative: Boolean, set: StudySet): Boolean {
    if (chapter == null && fromChapter == null) return false
    val start = fromChapterRef ?: if (cumulative) set.chapterRanges.first().start else chapterRef
    val end = chapterRef ?: set.chapterRanges.last().endInclusive
    return start != null && ref in start..end
}

/**
 * The next selection after tapping the chip for [chapter] of [book] — the one shared gesture behind
 * both pickers (Compose and web), so their behavior can't drift.
 *
 * A single-select picker ([range] = false) just toggles: tapping selects the chapter, tapping the
 * selected chapter clears it. A range picker builds a span from two taps: the first tap picks one
 * chapter, a tap on a second chip spans between them (in either order), and any further tap starts
 * over at the tapped chapter. Tapping a lone selected chapter still clears it. On a [cumulative]
 * picker a lone endpoint already reads as "through N", so a tap past it extends that reach — keeping
 * the implied start — rather than anchoring a two-chapter span.
 *
 * The result always keeps fromChapter <= chapter, which is the only span order resolveStudyScope
 * accepts.
 */
fun ScopeSelection.tap(book: Book, chapter: Int, range: Boolean = false, cumulative: Boolean = false): ScopeSelection {
    val sameBook = this.book == book
    val start = fromChapter.takeIf { sameBook }
    val end = this.chapter.takeIf { sameBook }
    return when {
        !range -> ScopeSelection(book, chapter.takeIf { it != end })
        start != null || end == null -> ScopeSelection(book, chapter)
        chapter == end -> ScopeSelection(book)
        chapter < end -> ScopeSelection(book, end, fromChapter = chapter)
        cumulative -> ScopeSelection(book, chapter)
        else -> ScopeSelection(book, chapter, fromChapter = end)
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
    val resolution = resolveStudyScope(
        setParam = set.simpleName,
        bookParam = (book ?: set.books.singleOrNull())?.name,
        chapter = chapter,
        currentSeasonSet = set,
        fromChapter = fromChapter,
        cumulative = chapterKey == StudyScopeParams.THROUGH_CHAPTER,
    )
    return (resolution as? ScopeResolution.Resolved)?.scope
        ?: StudyScope(set, book ?: set.books.singleOrNull(), chapters = null)
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
