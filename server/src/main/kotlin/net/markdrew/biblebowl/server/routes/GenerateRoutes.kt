package net.markdrew.biblebowl.server.routes

import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.markdrew.biblebowl.api.ApiError
import net.markdrew.biblebowl.api.ClearPdfCacheResponse
import net.markdrew.biblebowl.api.HeadingSize
import net.markdrew.biblebowl.api.PdfFileNames
import net.markdrew.biblebowl.api.Permission
import net.markdrew.biblebowl.api.QuestionStatus
import net.markdrew.biblebowl.model.Round
import net.markdrew.biblebowl.generate.practice.PracticeTest
import net.markdrew.biblebowl.generate.practice.eventsTypst
import net.markdrew.biblebowl.generate.practice.findTheVerseTypst
import net.markdrew.biblebowl.generate.practice.quotesTypst
import net.markdrew.biblebowl.analysis.WordList
import net.markdrew.biblebowl.analysis.fullIndex
import net.markdrew.biblebowl.generate.LayoutRevisions
import net.markdrew.biblebowl.generate.normalizeWS
import net.markdrew.biblebowl.generate.stampLayoutRevision
import net.markdrew.biblebowl.analysis.namesIndex
import net.markdrew.biblebowl.analysis.oneTimeWordCards
import net.markdrew.biblebowl.analysis.wordListIndex
import net.markdrew.biblebowl.generate.indices.indexTypst
import net.markdrew.biblebowl.generate.indices.numbersIndexTypst
import net.markdrew.biblebowl.generate.indices.oneTimeWordsIndexTypst
import net.markdrew.biblebowl.generate.studyguide.STUDY_GUIDE_LOGO_FILE
import net.markdrew.biblebowl.generate.studyguide.studyGuideTypst
import net.markdrew.biblebowl.generate.studyguide.tbbLogoBytes
import net.markdrew.biblebowl.model.StudyGuideParser
import net.markdrew.biblebowl.generate.text.TextOptions
import net.markdrew.biblebowl.generate.text.highlightedBibleTextTypst
import net.markdrew.biblebowl.generate.text.typst.bibleTextTypst
import net.markdrew.biblebowl.generation.typst.CardText
import net.markdrew.biblebowl.generation.typst.ChapterHeadingBook
import net.markdrew.biblebowl.generation.typst.ChapterHeadingChapter
import net.markdrew.biblebowl.generation.typst.ChapterHeadingRow
import net.markdrew.biblebowl.generation.typst.Flashcard
import net.markdrew.biblebowl.generation.typst.chapterHeadingsTypst
import net.markdrew.biblebowl.generation.typst.flashcardsTypst
import net.markdrew.biblebowl.generation.typst.markdownEscape
import net.markdrew.biblebowl.generation.typst.practiceTestTypst
import net.markdrew.biblebowl.generation.typst.toFlashcards
import net.markdrew.biblebowl.api.StudyScopeParams
import net.markdrew.biblebowl.model.BRIEF_BOOK_FORMAT
import net.markdrew.biblebowl.model.ChapterRef
import net.markdrew.biblebowl.model.format
import net.markdrew.biblebowl.model.FULL_BOOK_FORMAT
import net.markdrew.biblebowl.model.NO_BOOK_FORMAT
import net.markdrew.biblebowl.model.StudyScope
import net.markdrew.biblebowl.model.StudySet
import net.markdrew.biblebowl.model.VerseRange
import net.markdrew.biblebowl.server.data.QuestionRepository
import net.markdrew.biblebowl.server.data.SeasonRepository
import net.markdrew.biblebowl.server.data.UserRepository
import net.markdrew.biblebowl.server.esv.EsvUpstreamException
import net.markdrew.biblebowl.server.export.KahootQuestion
import net.markdrew.biblebowl.server.export.kahootXlsx
import net.markdrew.biblebowl.server.export.quizletTabbed
import net.markdrew.biblebowl.server.export.quizletTsv
import net.markdrew.biblebowl.server.export.spaceCsv
import net.markdrew.biblebowl.server.export.tsvToCsv
import net.markdrew.biblebowl.server.security.currentUser
import net.markdrew.biblebowl.server.security.requirePermission
import net.markdrew.biblebowl.server.study.PdfCache
import net.markdrew.biblebowl.server.study.StudyDataRegistry
import net.markdrew.biblebowl.server.study.StudyDataService
import net.markdrew.biblebowl.server.typst.TypstCompiler
import net.markdrew.biblebowl.server.typst.TypstException
import kotlin.random.Random
import kotlin.random.nextInt

/** Name of the per-client rate limit applied to the generate endpoints (Typst compiles are CPU-bound). */
val GENERATE_RATE_LIMIT = RateLimitName("generate")

/**
 * Most cards one question-flashcard PDF carries — a paper limit, not a data one. The deck prints 10 cards
 * to a page (2×5) with a mirrored backs page for duplex, so this is 40 sheets to print and cut; the whole
 * Acts bank (1,646 approved questions) would be 165. The pool is the top-voted questions in scope, and the
 * way to reach the rest is to narrow the scope to a chapter, which the UI says on the card.
 */
private const val FLASHCARD_DECK_MAX = 400

/**
 * `?format=typ` on any generated-PDF endpoint serves the Typst source behind the document
 * instead of the document Typst compiled from it — same route, same params, same cache stamp, so the
 * source you get is exactly the source behind the PDF those params would hand you: the generator's
 * markup wrapped with the same corner layout-revision stamp the compiler applies, so recompiling the
 * download reproduces the served PDF.
 *
 * It rides the existing `.pdf` routes rather than getting `.typ` siblings so that every generator gets
 * it for free from the three `respond*Pdf` helpers below: a new endpoint can't forget to offer it, and
 * the options a PDF understands can never drift from the ones its source understands.
 */
private const val TYPST_SOURCE_FORMAT = "typ"

/** Downloaded Typst source is plain UTF-8 text; the `.typ` extension carries the meaning. */
private val TYPST_CONTENT_TYPE = ContentType.Text.Plain.withCharset(Charsets.UTF_8)

/** True when the caller asked for [TYPST_SOURCE_FORMAT] markup rather than a compiled PDF. */
private fun ApplicationCall.wantsTypstSource(): Boolean =
    request.queryParameters["format"].equals(TYPST_SOURCE_FORMAT, ignoreCase = true)

/** The `.typ` sibling of a `PdfFileNames` filename — same param encoding, so it caches alongside it. */
private fun String.asTypstFileName(): String = removeSuffix(".pdf") + ".typ"

/**
 * Gate for the study text's Typst source, the one document whose markup reproduces the season's ESV
 * text end to end. The compiled PDF is public, but its source is those same words machine-readable —
 * a materially easier thing to redistribute or scrape than a typeset PDF — and our ESV licence is a
 * non-profit one that keeps the text server-side, so that one is SEASON_MANAGE-only.
 *
 * Nothing else needs it: the indices are word lists and verse references, the flashcard decks and
 * practice rounds are short excerpts, and the study guide and question bank are our own material. Their
 * markup is as public as their PDFs.
 *
 * Responds 401/403 itself and returns false when the caller doesn't qualify.
 */
private suspend fun io.ktor.server.routing.RoutingContext.allowStudyTextSource(users: UserRepository): Boolean {
    val user = currentUser(users) ?: return false
    return requirePermission(user, Permission.SEASON_MANAGE)
}

/**
 * Gate for the verse flashcard decks. Unlike the other decks — word lists and short excerpts — a card
 * per verse is the season's ESV text end to end in a plain-text file, the same concern that keeps the
 * study text's Typst source restricted. Rather than lock students out of their own memory work, this
 * one only requires *a* signed-in user (no permission): enough to keep the running text away from
 * anonymous scrapers and bulk download, which is what our non-profit licence is protecting.
 *
 * Responds 401 itself and returns false when nobody is signed in.
 */
private suspend fun io.ktor.server.routing.RoutingContext.allowVerseDeck(users: UserRepository): Boolean =
    currentUser(users) != null

fun Route.generateRoutes(
    users: UserRepository,
    questions: QuestionRepository,
    seasons: SeasonRepository,
    study: StudyDataRegistry? = null,
    pdfCache: PdfCache? = null,
) {
    // Public (study material never requires sign-in), but rate-limited per client: each request
    // shells out to Typst, so an anonymous hot loop must not be able to pin the CPU.
    //
    // Scope: every endpoint accepts ?set=<slug> (and ?book=/?chapter= where chapters apply) in
    // canonical scripture coordinates, defaulting to the current season — so a study link means the
    // same material in any season and can be reused across the 10-year rotation. ESV-text-backed
    // endpoints restrict set= to the StandardStudySet allowlist (the ESV licence budget); question-bank
    // endpoints take any canonical scope. Filenames are set-prefixed (acts-bible-text.pdf) with
    // book-qualified chapter suffixes for multi-book sets (-num14).
    //
    // `authenticate(optional = true)` keeps every endpoint anonymous-friendly while still parsing a JWT
    // when one is sent. Only one thing in here needs that — `?format=typ` on the study text, whose markup
    // reproduces the running ESV text — but it wraps the whole group rather than splitting that endpoint
    // out of it. An absent or bad token is simply no principal; nothing else ever asks for one.
    authenticate(optional = true) {
        rateLimit(GENERATE_RATE_LIMIT) {
            // GET /generate/practice-test.pdf?round=FACT_FINDER&set=acts&book=ACT&chapter=2&limit=40&seed=1234
            //
            // R1/R4/R5 are generated deterministically from the ESV text; R2/R3 come from the approved
            // crowd-sourced question bank. `chapter` is an exact chapter for the bank rounds and a cumulative
            // "through chapter" for the text rounds (matching how the study material scopes cumulative tests).
            get("/generate/practice-test.pdf") {
                val round = call.request.queryParameters["round"]
                    ?.let { runCatching { Round.valueOf(it) }.getOrNull() }
                    ?: return@get call.respond(
                        HttpStatusCode.BadRequest,
                        ApiError("bad_round", "round must be one of ${Round.entries.joinToString()}"),
                    )
                val seasonSet = seasons.currentStudySet()

                if (round.textGenerated) {
                    // Spelled `chapter` but meant cumulatively — a legacy pairing this route has to keep,
                    // so the flag is passed explicitly rather than inferred from the parameter's name.
                    val scope = call.resolveEsvScopeOrRespond(seasonSet, cumulative = true) ?: return@get
                    val seed = call.request.queryParameters["seed"]?.toIntOrNull()
                    return@get respondTextPracticeTest(round, scope, seed, study?.forSet(scope.set))
                }

                val scope = call.resolveScopeOrRespond(seasonSet) ?: return@get
                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 40).coerceIn(1, 100)
                val pool = questions.list(QuestionStatus.APPROVED, scope.toQuestionScope(), roundType = round, limit = limit)
                if (pool.isEmpty()) {
                    return@get call.respond(
                        HttpStatusCode.NotFound,
                        ApiError("no_questions", "No approved ${round.displayName} questions" +
                            (scope.chapters?.let { " for ${it.format()}" } ?: "")),
                    )
                }

                call.advertiseCanonicalScope(scope)
                val typstSource = practiceTestTypst(round, pool)
                val fileName = "practice-${round.name.lowercase()}${scope.chapterSuffix()}.pdf"
                respondPdf(typstSource, PdfFileNames.withSet(scope.set.simpleName, fileName), LayoutRevisions.PRACTICE_TEST)
            }

            // GET /generate/flashcards.pdf?set=acts&book=ACT&chapter=2&round=IDENTIFICATION (all optional)
            get("/generate/flashcards.pdf") {
                val round = call.request.queryParameters["round"]
                    ?.let { runCatching { Round.valueOf(it) }.getOrNull() }

                // The question bank only holds crowd-sourced rounds. R1/R4/R5 come from the text; R5 has its
                // own deck at /generate/heading-flashcards.pdf.
                if (round != null && round.textGenerated) {
                    return@get call.respond(
                        HttpStatusCode.BadRequest,
                        ApiError(
                            "not_crowd_sourced",
                            "${round.displayName} is generated from the text, not the question bank" +
                                (if (round == Round.EVENTS) " — use /generate/heading-flashcards.pdf" else ""),
                        ),
                    )
                }

                val scope = call.resolveScopeOrRespond(seasons.currentStudySet()) ?: return@get
                val pool =
                    questions.list(QuestionStatus.APPROVED, scope.toQuestionScope(), roundType = round, limit = FLASHCARD_DECK_MAX)
                if (pool.isEmpty()) {
                    return@get call.respond(
                        HttpStatusCode.NotFound,
                        ApiError("no_questions", "No approved questions" +
                            (scope.chapters?.let { " for ${it.format()}" } ?: "")),
                    )
                }
                call.advertiseCanonicalScope(scope)
                val fileName = PdfFileNames.withSet(scope.set.simpleName, "flashcards${scope.chapterSuffix()}.pdf")
                respondPdf(flashcardsTypst(pool.toFlashcards()), fileName, LayoutRevisions.FLASHCARDS)
            }

            // GET /generate/bible-text.pdf?set=acts&fontSize=11&twoColumns=false&justified=false&chapterBreaksPage=false
            //     &useHeadingsForChapters=false&chapterEndLines=false&verseOnNewLine=false&underlineUniqueWords=false
            //     &chapterHeadingSize=medium&sectionHeadingSize=small
            // A formatted PDF of the covered text (verse numbers, headings, poetry, footnotes) with categorized
            // name/number highlighting (highlight=true by default) and optional underlining of hapax words
            // (underlineUniqueWords) — words that appear exactly once in the study set. The footer stamps the
            // season's event dates (e.g. "April 2–4, 2027") rather than the generation date.
            get("/generate/bible-text.pdf") {
                val scope = call.resolveEsvScopeOrRespond(seasons.currentStudySet()) ?: return@get
                val svc = study?.forSet(scope.set)
                if (svc == null || !svc.isConfigured) {
                    return@get call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        ApiError("esv_unconfigured", "ESV service is not configured (set ESV_API_TOKEN)"),
                    )
                }
                val qp = call.request.queryParameters
                val season = seasons.current()
                // Unrecognized slugs fall back to the defaults rather than 400 — a stale link should still
                // render a sensible PDF, and the name it resolves to is what gets cached.
                val chapterHeading = HeadingSize.bySlug(qp["chapterHeadingSize"]) ?: HeadingSize.DEFAULT_CHAPTER
                val sectionHeading = HeadingSize.bySlug(qp["sectionHeadingSize"]) ?: HeadingSize.DEFAULT_SECTION
                val options = TextOptions(
                    dateLine = "${season.eventDateRange}, ${season.eventYear}",
                    fontSize = qp["fontSize"]?.toIntOrNull()?.coerceIn(6, 24) ?: 11,
                    twoColumns = qp["twoColumns"]?.toBooleanStrictOrNull() ?: false,
                    justified = qp["justified"]?.toBooleanStrictOrNull() ?: false,
                    chapterBreaksPage = qp["chapterBreaksPage"]?.toBooleanStrictOrNull() ?: false,
                    useHeadingsForChapters = qp["useHeadingsForChapters"]?.toBooleanStrictOrNull() ?: false,
                    chapterEndLines = qp["chapterEndLines"]?.toBooleanStrictOrNull() ?: false,
                    verseOnNewLine = qp["verseOnNewLine"]?.toBooleanStrictOrNull() ?: false,
                    underlineUniqueWords = qp["underlineUniqueWords"]?.toBooleanStrictOrNull() ?: false,
                    chapterHeadingScale = chapterHeading.scale,
                    sectionHeadingScale = sectionHeading.scale,
                )
                // Categorized name/number highlighting is the point of the download, so it's on by default.
                val highlight = qp["highlight"]?.toBooleanStrictOrNull() ?: true
                try {
                    // Named from the coerced options, so out-of-range requests share the row they resolve to.
                    val fileName = PdfFileNames.withSet(scope.set.simpleName, PdfFileNames.bibleText(
                        highlight = highlight,
                        twoColumns = options.twoColumns,
                        justified = options.justified,
                        chapterBreaksPage = options.chapterBreaksPage,
                        useHeadingsForChapters = options.useHeadingsForChapters,
                        chapterEndLines = options.chapterEndLines,
                        verseOnNewLine = options.verseOnNewLine,
                        underlineUniqueWords = options.underlineUniqueWords,
                        fontSize = options.fontSize,
                        chapterHeading = chapterHeading,
                        sectionHeading = sectionHeading,
                    ))
                    call.advertiseCanonicalScope(scope)
                    // The footer date comes from the season params, which the content stamp doesn't cover —
                    // salt the stamp with it so editing the event dates refreshes cached study texts.
                    respondCachedPdf(
                        svc, pdfCache, fileName,
                        layoutRevision = LayoutRevisions.BIBLE_TEXT,
                        extraStampSalt = options.dateLine.hashCode(),
                        gatedSourceUsers = users,
                    ) {
                        if (highlight) {
                            highlightedBibleTextTypst(svc.studyData(), svc.categoryResolution(), options)
                        } else {
                            bibleTextTypst(svc.studyData(), options)
                        }
                    }
                } catch (e: EsvUpstreamException) {
                    call.respond(HttpStatusCode.BadGateway, ApiError("esv_upstream", e.message ?: "ESV API error"))
                }
            }

            // GET /generate/numbers-index.pdf?set=acts — the set's numbers index (alphabetical + by frequency)
            get("/generate/numbers-index.pdf") {
                respondIndexPdf(study, seasons, pdfCache, PdfFileNames.numbersIndex(), LayoutRevisions.INDEX) { s ->
                    numbersIndexTypst(s.studyData())
                }
            }

            // GET /generate/names-index.pdf?set=acts — the set's names index (alphabetical + by frequency)
            get("/generate/names-index.pdf") {
                respondIndexPdf(study, seasons, pdfCache, PdfFileNames.namesIndex(), LayoutRevisions.INDEX) { s ->
                    indexTypst(s.studyData(), namesIndex(s.studyData(), s.categoryResolution()), "Name")
                }
            }

            // GET /generate/men-index.pdf?set=acts — the men named in the set (alphabetical + by frequency)
            get("/generate/men-index.pdf") {
                respondIndexPdf(study, seasons, pdfCache, PdfFileNames.menIndex(), LayoutRevisions.INDEX) { s ->
                    val sd = s.studyData()
                    indexTypst(
                        sd, wordListIndex(sd, s.categoryResolution(), WordList.MEN),
                        "Man", plural = "Men", title = "${sd.studySet.name} Men Index",
                    )
                }
            }

            // GET /generate/women-index.pdf?set=acts
            get("/generate/women-index.pdf") {
                respondIndexPdf(study, seasons, pdfCache, PdfFileNames.womenIndex(), LayoutRevisions.INDEX) { s ->
                    val sd = s.studyData()
                    indexTypst(
                        sd, wordListIndex(sd, s.categoryResolution(), WordList.WOMEN),
                        "Woman", plural = "Women", title = "${sd.studySet.name} Women Index",
                    )
                }
            }

            // GET /generate/places-index.pdf?set=acts
            get("/generate/places-index.pdf") {
                respondIndexPdf(study, seasons, pdfCache, PdfFileNames.placesIndex(), LayoutRevisions.INDEX) { s ->
                    val sd = s.studyData()
                    indexTypst(
                        sd, wordListIndex(sd, s.categoryResolution(), WordList.PLACES),
                        "Place", title = "${sd.studySet.name} Places Index",
                    )
                }
            }

            // GET /generate/full-index.pdf?set=acts — a complete word concordance for the set
            get("/generate/full-index.pdf") {
                respondIndexPdf(study, seasons, pdfCache, PdfFileNames.fullIndex(), LayoutRevisions.INDEX) { s ->
                    val sd = s.studyData()
                    indexTypst(sd, fullIndex(sd), "Word", title = "${sd.studySet.name} Complete Word Index")
                }
            }

            // GET /generate/unique-words-index.pdf?set=acts — the hapax index (alphabetical + by appearance)
            get("/generate/unique-words-index.pdf") {
                respondIndexPdf(study, seasons, pdfCache, PdfFileNames.uniqueWordsIndex(), LayoutRevisions.INDEX) { s ->
                    oneTimeWordsIndexTypst(s.studyData())
                }
            }

            // GET /generate/unique-word-flashcards.pdf?set=acts — one card per one-time word: the word up
            // front, its verse (with the verse text as context, the word underlined) on the back. Cards run
            // in the word's order of appearance.
            get("/generate/unique-word-flashcards.pdf") {
                respondIndexPdf(
                    study, seasons, pdfCache, PdfFileNames.uniqueWordFlashcards(), LayoutRevisions.FLASHCARDS,
                ) { s ->
                    val sd = s.studyData()
                    val cards = oneTimeWordCards(sd)
                    flashcardsTypst(
                        cards.mapIndexed { i, c ->
                            Flashcard(
                                front = CardText.Plain(c.word),
                                back = CardText.Plain(sd.verseRefFormat(c.verseRef)),
                                note = CardText.Markdown(
                                    markdownEscape(c.versePrefix) + "**<u>" + markdownEscape(c.word) + "</u>**" +
                                        markdownEscape(c.verseSuffix)
                                ),
                                footer = "${i + 1} of ${cards.size}",
                            )
                        },
                    )
                }
            }

            // GET /generate/unique-word-flashcards.csv?set=acts — the same deck as a Space-importable
            // CSV (getspace.app): the word up front; the heading, verse reference, and verse text behind
            // it, on real lines with Markdown emphasis, which is what Space's cards render (import-tested
            // 2026-08: its "basic HTML" support does NOT cover this shape). A blank line separates the
            // heading from the reference — a lone newline is only a Markdown soft break.
            get("/generate/unique-word-flashcards.csv") {
                respondUniqueWordDeck(study, seasons, forSpace = true)
            }

            // GET /generate/unique-word-flashcards.txt?set=acts — the same deck as a Quizlet paste file:
            // TAB between term and definition, a blank line between cards (on Quizlet's import screen
            // choose Tab, and a custom "\n\n" between cards) so definitions keep their single line
            // breaks. Emphasis is Quizlet's own markup, and only the *bold* part: import-tested
            // 2026-08, _underline_ does not survive its importer.
            get("/generate/unique-word-flashcards.txt") {
                respondUniqueWordDeck(study, seasons, forSpace = false)
            }

            // GET /generate/space-verses.csv?set=acts&book=ACT&chapter=2 (or &throughChapter=2) — one
            // card per verse in scope, as a Space-importable CSV: the verse text up front, its heading
            // and reference behind it. Same Markdown shape as the unique-words deck (blank line after
            // the heading, bold reference). Signed-in only — see [allowVerseDeck].
            get("/generate/space-verses.csv") {
                if (!allowVerseDeck(users)) return@get
                respondVerseDeck(study, seasons, forSpace = true)
            }

            // GET /generate/quizlet-verses.txt?… (scope params as above) — the same deck as a Quizlet
            // paste file. The back runs to two lines, so this is the tabbed/blank-line-between-cards
            // shape (choose Tab and a custom "\n\n" between cards), not the default one-card-per-line
            // one. Signed-in only.
            get("/generate/quizlet-verses.txt") {
                if (!allowVerseDeck(users)) return@get
                respondVerseDeck(study, seasons, forSpace = false)
            }

            // GET /generate/space-questions.csv?source=questions|headings&round=FACT_FINDER&chapter=2
            // Term/definition pairs as a Space-importable CSV (getspace.app): the Front,Back header row
            // its importer requires, RFC 4180 quoted. `source=questions` (default) exports the approved
            // bank (prompt -> answer); `source=headings` exports the R5 headings (title -> chapter), with
            // `chapter` meaning "through chapter" as usual for headings. The endpoints name their target
            // app rather than trusting an extension: there is no one CSV every importer reads.
            get("/generate/space-questions.csv") {
                respondExport(questions, seasons, study, format = ExportFormat.SPACE_CSV)
            }

            // GET /generate/quizlet-questions.txt?… (params as above) — the same pairs as a Quizlet paste
            // file in its import screen's default shape: TAB between term and definition, one card per
            // line. Unlike the unique-words deck there are no in-card line breaks to protect, so no
            // custom between-cards separator to type in — the defaults read it as-is.
            get("/generate/quizlet-questions.txt") {
                respondExport(questions, seasons, study, format = ExportFormat.QUIZLET_TXT)
            }

            // GET /generate/kahoot-questions.xlsx?source=questions|headings&round=FACT_FINDER&chapter=2
            // A Kahoot-import spreadsheet (their template layout). Only multiple-choice material can go
            // to Kahoot, so `source=questions` keeps just questions whose choices contain the answer;
            // `source=headings` builds which-chapter questions with in-scope distractor chapters.
            get("/generate/kahoot-questions.xlsx") {
                respondExport(questions, seasons, study, format = ExportFormat.KAHOOT_XLSX)
            }

            // GET /generate/heading-flashcards.pdf?set=acts&book=ACT&throughChapter=5 — Round 5 deck,
            // cumulatively scoped (headings whose chapter starts at or before the through-chapter).
            get("/generate/heading-flashcards.pdf") {
                val scope = call.resolveEsvScopeOrRespond(
                    seasons.currentStudySet(), chapterKey = StudyScopeParams.THROUGH_CHAPTER,
                ) ?: return@get
                val svc = study?.forSet(scope.set)
                if (svc == null || !svc.isConfigured) {
                    return@get call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        ApiError("esv_unconfigured", "ESV service is not configured (set ESV_API_TOKEN)"),
                    )
                }
                try {
                    call.advertiseCanonicalScope(scope, chapterKey = StudyScopeParams.THROUGH_CHAPTER)
                    val fileName = PdfFileNames.withSet(
                        scope.set.simpleName, "heading-flashcards${scope.chapterSuffix()}.pdf",
                    )
                    respondCachedPdf(svc, pdfCache, fileName, LayoutRevisions.FLASHCARDS) {
                        // The scope's own span test — book-aware, so it stays correct across the books of
                        // a multi-book set whether it was spelled cumulatively or as an explicit range.
                        val headings = svc.studyData().headings
                            .filter { scope.covers(it.chapterRange.start) }
                        val cards = headings.map { h ->
                            Flashcard(
                                front = h.title,
                                back = chapterLabel(scope.set, h.chapterRange.start),
                                note = h.verseRange.format(NO_BOOK_FORMAT),
                                footer = "${h.index} of ${h.maxIndex}",
                            )
                        }
                        flashcardsTypst(cards)
                    }
                } catch (e: EsvUpstreamException) {
                    call.respond(HttpStatusCode.BadGateway, ApiError("esv_upstream", e.message ?: "ESV API error"))
                }
            }

            // GET /generate/chapter-headings.pdf?set=acts — every ESV section heading in the set on one
            // page, in scripture order with the verses it covers. Whole-set only (no chapter scoping):
            // it's a wall-chart/reference sheet, and the fit search needs the full list to size itself.
            get("/generate/chapter-headings.pdf") {
                respondIndexPdf(
                    study, seasons, pdfCache, PdfFileNames.chapterHeadings(), LayoutRevisions.CHAPTER_HEADINGS,
                ) { s ->
                    val sd = s.studyData()
                    // A heading never spans books, so grouping keeps scripture order and lets each row's
                    // reference stay book-less — the band above it names the book. Single-book sets get no
                    // bands at all (the sheet title already says the book).
                    val multiBook = !sd.studySet.isSingleBook
                    val books = sd.headings.groupBy { it.verseRange.start.book }.map { (book, headings) ->
                        ChapterHeadingBook(
                            book = book.fullName.takeIf { multiBook },
                            // Grouped by the chapter each heading *starts* in, so one that runs on into the
                            // next chapter shades with the chapter it belongs to. `headings` is already in
                            // scripture order and groupBy keeps encounter order, so the chapters stay in order.
                            chapters = headings.groupBy { it.verseRange.start.chapter }.map { (chapter, hs) ->
                                ChapterHeadingChapter(
                                    chapter,
                                    hs.map { ChapterHeadingRow(it.title, it.verseRange.headingReference()) },
                                )
                            },
                        )
                    }
                    chapterHeadingsTypst(sd.studySet.name, books)
                }
            }

            // GET /generate/study-guide.pdf?set=acts — the multiple-choice study guide (student copy, key at the end).
            get("/generate/study-guide.pdf") { respondStudyGuidePdf(study, pdfCache, seasons, markAnswers = false) }

            // GET /generate/study-guide-answers.pdf?set=acts — the answer copy: correct choices starred inline, no key.
            get("/generate/study-guide-answers.pdf") { respondStudyGuidePdf(study, pdfCache, seasons, markAnswers = true) }

            // GET /generate/study-guide.csv?set=acts — the raw curated source, for other creators (Data & Source Files)
            get("/generate/study-guide.csv") {
                val scope = call.resolveEsvScopeOrRespond(seasons.currentStudySet()) ?: return@get
                val svc = study?.forSet(scope.set) ?: return@get call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    ApiError("esv_unconfigured", "Study set is not configured"),
                )
                val tsv = StudyGuideParser.rawTsvOrNull(svc.studySet) ?: return@get call.respond(
                    HttpStatusCode.NotFound,
                    ApiError("no_study_guide", "This study set has no study guide"),
                )
                respondAttachment(
                    tsvToCsv(tsv), PdfFileNames.withSet(scope.set.simpleName, "study-guide.csv"), CSV_CONTENT_TYPE,
                )
            }
        }
    }

    authenticate {
        // DELETE /generate/cache — admin: drop every cached PDF (each regenerates on its next request).
        // For when the generation code changes; season/word-list changes invalidate automatically via
        // the content stamp. Gated on SEASON_MANAGE rather than a new Permission value: deployed wasm
        // clients deserialize UserDto.permissions and would break on an unknown enum entry.
        delete("/generate/cache") {
            val user = currentUser(users) ?: return@delete
            if (!requirePermission(user, Permission.SEASON_MANAGE)) return@delete
            val cleared = pdfCache?.let { withContext(Dispatchers.IO) { it.clear() } } ?: 0
            call.respond(ClearPdfCacheResponse(cleared))
        }
    }
}

/**
 * A heading's reference as it reads on the chapter-headings sheet, where the chapter is already
 * printed beside the row: verses only ("12-26", or "7" for a one-verse heading). A heading that runs
 * on into the next chapter is the exception — there the chapter is the whole point, so both ends keep
 * theirs ("21:37-22:21").
 */
internal fun VerseRange.headingReference(): String = when {
    start.chapterRef != endInclusive.chapterRef -> format(NO_BOOK_FORMAT)
    start == endInclusive -> start.verse.toString()
    else -> "${start.verse}-${endInclusive.verse}"
}

/** "Chapter 14" for a single-book set, "Num 14" for a multi-book set (a bare number is ambiguous). */
private fun chapterLabel(set: StudySet, ref: ChapterRef): String =
    if (set.isSingleBook) "Chapter ${ref.chapter}" else ref.format(BRIEF_BOOK_FORMAT)

/**
 * Generates a text-based practice test (R1 Find-the-Verse, R4 Quotations, or R5 Events) from the indexed
 * [StudyData] and responds with the PDF. [scope]'s chapter, when set, scopes the test cumulatively
 * through that chapter; [seed] makes selection reproducible.
 */
private suspend fun io.ktor.server.routing.RoutingContext.respondTextPracticeTest(
    round: Round,
    scope: StudyScope,
    seed: Int?,
    study: StudyDataService?,
) {
    if (study == null || !study.isConfigured) {
        return call.respond(
            HttpStatusCode.ServiceUnavailable,
            ApiError("esv_unconfigured", "ESV service is not configured (set ESV_API_TOKEN)"),
        )
    }
    val studyData = try {
        study.studyData()
    } catch (e: EsvUpstreamException) {
        return call.respond(HttpStatusCode.BadGateway, ApiError("esv_upstream", e.message ?: "ESV API error"))
    }

    // Filtering the data's own chapters (not the set's) keeps this to material we actually have — the
    // same guarantee the old take-through-index gave, now over an arbitrary span.
    val covered = studyData.chapterRefs.filter { scope.covers(it) }
    if (covered.isEmpty()) {
        return call.respond(
            HttpStatusCode.NotFound,
            ApiError("no_chapters", "No chapters in scope${scope.chapters?.let { " for ${it.format()}" } ?: ""}"),
        )
    }
    val content = studyData.practice(covered)
    val practiceTest = PracticeTest(round, content, randomSeed = seed ?: Random.nextInt(1..9_999))
    val (typstSource, layoutRevision) = when (round) {
        Round.FIND_THE_VERSE -> findTheVerseTypst(practiceTest) to LayoutRevisions.FIND_THE_VERSE
        Round.QUOTES -> quotesTypst(practiceTest) to LayoutRevisions.QUOTES
        Round.EVENTS -> eventsTypst(practiceTest) to LayoutRevisions.EVENTS
        else -> null to 0 // unreachable: guarded by round.textGenerated at the call site
    }
    if (typstSource == null) {
        return call.respond(
            HttpStatusCode.UnprocessableEntity,
            ApiError("not_enough_chapters", "Not enough chapters covered to build a ${round.displayName} test"),
        )
    }
    // Advertised under `chapter`, the key this route actually reads — canonicalising it to
    // `throughChapter` would emit a URL that comes back here meaning something narrower.
    call.advertiseCanonicalScope(scope)
    val fileName = "practice-${round.name.lowercase()}${scope.chapterSuffix()}.pdf"
    respondPdf(typstSource, PdfFileNames.withSet(scope.set.simpleName, fileName), layoutRevision)
}



private enum class ExportFormat { SPACE_CSV, QUIZLET_TXT, KAHOOT_XLSX }

/**
 * Kahoot's own ceiling: one kahoot holds at most this many questions, so a bigger sheet would be
 * rejected by their importer. Space and Quizlet have no comparable limit — their exports are whole.
 */
private const val KAHOOT_MAX_QUESTIONS = 100

/**
 * Responds with an import-ready export of the question bank or the R5 headings (see the route
 * comments for parameter semantics). All formats share source selection; only the rendering and
 * the multiple-choice requirement (Kahoot) differ.
 */
private suspend fun io.ktor.server.routing.RoutingContext.respondExport(
    questions: QuestionRepository,
    seasons: SeasonRepository,
    study: StudyDataRegistry?,
    format: ExportFormat,
) {
    val qp = call.request.queryParameters
    val source = qp["source"]?.lowercase() ?: "questions"
    if (source !in setOf("questions", "headings")) {
        return call.respond(HttpStatusCode.BadRequest, ApiError("bad_source", "source must be questions or headings"))
    }
    val round = qp["round"]?.let { runCatching { Round.valueOf(it) }.getOrNull() }
    val seasonSet = seasons.currentStudySet()

    if (source == "questions") {
        val scope = call.resolveScopeOrRespond(seasonSet) ?: return
        // Uncapped on purpose: an export is the one caller that wants the whole bank. The bundled Acts
        // study guide alone seeds 1,646 approved questions, so any round number here silently truncates
        // — and since seeded questions all have zero votes, list()'s `votes DESC, id ASC` would hand out
        // an arbitrary UUID-ordered slice, not a readable prefix. APPROVED + scope already bounds this
        // to one season's material (a few thousand prompt/answer rows).
        val pool = questions.list(QuestionStatus.APPROVED, scope.toQuestionScope(), roundType = round)
        call.advertiseCanonicalScope(scope)
        val baseName = PdfFileNames.withSet(
            scope.set.simpleName,
            "questions${round?.let { "-${it.name.lowercase()}" } ?: ""}${scope.chapterSuffix()}",
        )
        when (format) {
            ExportFormat.SPACE_CSV, ExportFormat.QUIZLET_TXT -> {
                if (pool.isEmpty()) return call.respond(HttpStatusCode.NotFound, ApiError("no_questions", "No approved questions match"))
                respondCardFile(format, pool.map { it.prompt to it.answer }, baseName)
            }
            ExportFormat.KAHOOT_XLSX -> {
                // Kahoot is multiple-choice only: the answer must be among 2+ choices.
                val mc = pool.filter { it.choices.size >= 2 && it.answer in it.choices }
                if (mc.isEmpty()) {
                    return call.respond(
                        HttpStatusCode.NotFound,
                        ApiError("no_questions", "No approved multiple-choice questions match (Kahoot needs choices)"),
                    )
                }
                val rows = mc.take(KAHOOT_MAX_QUESTIONS).map { q ->
                    // Kahoot allows at most 4 answers: keep the correct one plus the first 3 others.
                    val answers =
                        if (q.choices.size <= 4) q.choices
                        else listOf(q.answer) + q.choices.filterNot { it == q.answer }.take(3)
                    KahootQuestion(q.prompt, answers, listOf(answers.indexOf(q.answer) + 1))
                }
                respondAttachment(kahootXlsx(rows), "kahoot-$baseName.xlsx", XLSX_CONTENT_TYPE)
            }
        }
        return
    }

    // source == "headings" — the R5 material; `chapter` scopes cumulatively (through chapter N). Like
    // the text practice rounds, the key says "exact" and the route means "through", so say so.
    val scope = call.resolveEsvScopeOrRespond(seasonSet, cumulative = true) ?: return
    val svc = study?.forSet(scope.set)
    if (svc == null || !svc.isConfigured) {
        return call.respond(
            HttpStatusCode.ServiceUnavailable,
            ApiError("esv_unconfigured", "ESV service is not configured (set ESV_API_TOKEN)"),
        )
    }
    val headings = try {
        // The scope's own span test — book-aware, and the same for a cumulative or an explicit range.
        svc.studyData().headings.filter { scope.covers(it.chapterRange.start) }
    } catch (e: EsvUpstreamException) {
        return call.respond(HttpStatusCode.BadGateway, ApiError("esv_upstream", e.message ?: "ESV API error"))
    }
    if (headings.isEmpty()) return call.respond(HttpStatusCode.NotFound, ApiError("no_headings", "No headings in scope"))
    // Advertised under `chapter`, the key this route reads (see above).
    call.advertiseCanonicalScope(scope)
    val baseName = PdfFileNames.withSet(scope.set.simpleName, "headings${scope.chapterSuffix()}")
    when (format) {
        ExportFormat.SPACE_CSV, ExportFormat.QUIZLET_TXT -> respondCardFile(
            format,
            headings.map { it.title to chapterLabel(scope.set, it.chapterRange.start) },
            baseName,
        )
        ExportFormat.KAHOOT_XLSX -> {
            val chaptersInScope = headings.map { it.chapterRange.start }.distinct()
            val rows = headings.take(KAHOOT_MAX_QUESTIONS).mapIndexed { i, h ->
                val own = h.chapterRange.start
                // Seeded per row so the same export is reproducible; distractors never leak
                // chapters beyond the requested scope.
                val random = Random(i * 31 + own.chapter)
                val distractors = (chaptersInScope - own).shuffled(random).take(3)
                val answers = (distractors + own).sorted().map { chapterLabel(scope.set, it) }
                KahootQuestion(
                    question = "Which chapter has the heading “${h.title}”?",
                    answers = answers,
                    correctIndices = listOf(answers.indexOf(chapterLabel(scope.set, own)) + 1),
                )
            }
            respondAttachment(kahootXlsx(rows), "kahoot-$baseName.xlsx", XLSX_CONTENT_TYPE)
        }
    }
}

/**
 * [cards] in [format]'s import file (see the route comments for the two shapes), named after the
 * target app: `space-<base>.csv` or `quizlet-<base>.txt`.
 */
private suspend fun io.ktor.server.routing.RoutingContext.respondCardFile(
    format: ExportFormat,
    cards: List<Pair<String, String>>,
    baseName: String,
) = when (format) {
    ExportFormat.SPACE_CSV ->
        respondAttachment(spaceCsv(cards).toByteArray(), "space-$baseName.csv", CSV_CONTENT_TYPE)
    ExportFormat.QUIZLET_TXT ->
        respondAttachment(quizletTsv(cards).toByteArray(), "quizlet-$baseName.txt", ContentType.Text.Plain)
    ExportFormat.KAHOOT_XLSX -> error("Kahoot exports are spreadsheets, not card files")
}

/**
 * The unique-word flashcard deck as a per-app import file, [forSpace]'s CSV or Quizlet's tabbed
 * text (see the route comments for the two formats). Same shape either way: the one-time word up
 * front; the heading, full verse reference, and the verse with the word emphasized behind it — the
 * original bible-bowl Cram export, in each importer's own markup.
 */
private suspend fun io.ktor.server.routing.RoutingContext.respondUniqueWordDeck(
    study: StudyDataRegistry?,
    seasons: SeasonRepository,
    forSpace: Boolean,
) {
    val scope = call.resolveEsvScopeOrRespond(seasons.currentStudySet()) ?: return
    val svc = study?.forSet(scope.set)
    if (svc == null || !svc.isConfigured) {
        return call.respond(
            HttpStatusCode.ServiceUnavailable,
            ApiError("esv_unconfigured", "ESV service is not configured (set ESV_API_TOKEN)"),
        )
    }
    try {
        call.advertiseCanonicalScope(scope)
        val sd = svc.studyData()
        val cards = oneTimeWordCards(sd).map { c ->
            // The full book name (unlike the PDF's set-relative refs): the export leaves the app, so
            // each card has to name its verse completely on its own.
            val ref = c.verseRef.format(FULL_BOOK_FORMAT)
            val back =
                if (forSpace) listOfNotNull(
                    c.heading?.let { "$it\n" }, // + the joiner's newline = the Markdown paragraph break
                    "**$ref**",
                    "${c.versePrefix}***${c.word}***${c.verseSuffix}",
                ) else listOfNotNull(
                    c.heading,
                    "*$ref*",
                    "${c.versePrefix}*${c.word}*${c.verseSuffix}",
                )
            c.word to back.joinToString("\n")
        }
        val baseName = PdfFileNames.withSet(scope.set.simpleName, "unique-words")
        if (forSpace) respondAttachment(spaceCsv(cards).toByteArray(), "space-$baseName.csv", CSV_CONTENT_TYPE)
        else respondAttachment(quizletTabbed(cards).toByteArray(), "quizlet-$baseName.txt", ContentType.Text.Plain)
    } catch (e: EsvUpstreamException) {
        call.respond(HttpStatusCode.BadGateway, ApiError("esv_upstream", e.message ?: "ESV API error"))
    }
}

/**
 * The verse flashcard deck as a per-app import file, [forSpace]'s CSV or Quizlet's tabbed text — the
 * verse text up front, its section heading and full reference behind it. The original bible-bowl Cram
 * verses export (`PrintVersesForCram`), in each importer's own markup instead of its `<br/>`s, and
 * laid out like the unique-words deck: heading for context, reference bold as the answer.
 */
private suspend fun io.ktor.server.routing.RoutingContext.respondVerseDeck(
    study: StudyDataRegistry?,
    seasons: SeasonRepository,
    forSpace: Boolean,
) {
    // Both endpoint spellings, because memory work wants both: `throughChapter=N` is cumulative
    // (everything learned so far), plain `chapter=N` is that chapter on its own, and `fromChapter`
    // narrows the start of either into an explicit range. Which key the request used only decides how
    // a lone endpoint is read; the resolved span is what filters, names the file, and gets advertised.
    // `throughChapter` wins if a request somehow carries both endpoints.
    val chapterKey = if (call.request.queryParameters[StudyScopeParams.THROUGH_CHAPTER] != null) {
        StudyScopeParams.THROUGH_CHAPTER
    } else {
        StudyScopeParams.CHAPTER
    }
    val scope = call.resolveEsvScopeOrRespond(seasons.currentStudySet(), chapterKey) ?: return
    val svc = study?.forSet(scope.set)
    if (svc == null || !svc.isConfigured) {
        return call.respond(
            HttpStatusCode.ServiceUnavailable,
            ApiError("esv_unconfigured", "ESV service is not configured (set ESV_API_TOKEN)"),
        )
    }
    try {
        call.advertiseCanonicalScope(scope, chapterKey)
        val sd = svc.studyData()
        // One span test for every spelling: a single chapter, a cumulative reach-back, or a range.
        val inScope = sd.verses.entries.filter { (_, verseRef) -> scope.covers(verseRef.chapterRef) }
        if (inScope.isEmpty()) {
            return call.respond(HttpStatusCode.NotFound, ApiError("no_verses", "No verses in scope"))
        }
        val cards = inScope.map { (range, verseRef) ->
            // The full book name (unlike the PDFs' set-relative refs): the export leaves the app, so
            // each card has to name its verse completely on its own.
            val ref = verseRef.format(FULL_BOOK_FORMAT)
            // Null for a verse that straddles a heading boundary — the card then carries the reference
            // alone rather than one arbitrary side's heading.
            val heading = sd.headingCharRanges.valueEnclosing(range)
            val back =
                if (forSpace) listOfNotNull(
                    heading?.let { "$it\n" }, // + the joiner's newline = the Markdown paragraph break
                    "**$ref**",
                ) else listOfNotNull(heading, "*$ref*")
            // The stored text keeps the layout's line breaks; a card front wants one flowing paragraph.
            sd.text.substring(range).normalizeWS() to back.joinToString("\n")
        }
        val baseName = PdfFileNames.withSet(scope.set.simpleName, "verses${scope.chapterSuffix()}")
        if (forSpace) respondAttachment(spaceCsv(cards).toByteArray(), "space-$baseName.csv", CSV_CONTENT_TYPE)
        else respondAttachment(quizletTabbed(cards).toByteArray(), "quizlet-$baseName.txt", ContentType.Text.Plain)
    } catch (e: EsvUpstreamException) {
        call.respond(HttpStatusCode.BadGateway, ApiError("esv_upstream", e.message ?: "ESV API error"))
    }
}

private val CSV_CONTENT_TYPE = ContentType("text", "csv")
private val XLSX_CONTENT_TYPE = ContentType("application", "vnd.openxmlformats-officedocument.spreadsheetml.sheet")

/** Responds with [bytes] as a named download attachment. */
private suspend fun io.ktor.server.routing.RoutingContext.respondAttachment(
    bytes: ByteArray,
    fileName: String,
    contentType: ContentType,
) {
    call.response.header(
        HttpHeaders.ContentDisposition,
        ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, fileName).toString(),
    )
    call.respondBytes(bytes, contentType)
}

/**
 * Compiles [typstSource] off the event loop and responds with PDF bytes as a named attachment — or,
 * for `?format=typ`, hands back the source exactly as the compiler would consume it (wrapped with the
 * [layoutRevision] corner stamp). These are the uncached endpoints (a practice test is freshly sampled
 * per request), so the source isn't cached either: same treatment as the PDF it would have produced.
 */
private suspend fun io.ktor.server.routing.RoutingContext.respondPdf(
    typstSource: String,
    fileName: String,
    layoutRevision: Int,
) {
    if (call.wantsTypstSource()) {
        val source = stampLayoutRevision(typstSource, layoutRevision).encodeToByteArray()
        return respondAttachment(source, fileName.asTypstFileName(), TYPST_CONTENT_TYPE)
    }
    try {
        val pdf = withContext(Dispatchers.IO) { TypstCompiler.compile(typstSource, layoutRevision) }
        respondAttachment(pdf, fileName, ContentType.Application.Pdf)
    } catch (e: TypstException) {
        call.respond(HttpStatusCode.ServiceUnavailable, ApiError("typst_failed", e.message ?: "PDF generation failed"))
    }
}

/**
 * The shared shape of the study-set index PDFs: `set=`-scoped (allowlisted, defaulting to the season),
 * ESV-gated (503 if unconfigured), cached, Typst-compiled, with upstream ESV failures mapped to 502.
 * [typstSource] receives the non-null, configured study service for the resolved set.
 *
 * [layoutRevision] is deliberately not defaulted: the content stamp can't see a rendering change, so
 * every one of these endpoints has to name the [LayoutRevisions] entry for the generator behind it,
 * and a new endpoint can't be added without deciding which one it belongs to.
 */
private suspend fun io.ktor.server.routing.RoutingContext.respondIndexPdf(
    study: StudyDataRegistry?,
    seasons: SeasonRepository,
    pdfCache: PdfCache?,
    baseFileName: String,
    layoutRevision: Int,
    typstSource: suspend (StudyDataService) -> String,
) {
    val scope = call.resolveEsvScopeOrRespond(seasons.currentStudySet()) ?: return
    val svc = study?.forSet(scope.set)
    if (svc == null || !svc.isConfigured) {
        return call.respond(
            HttpStatusCode.ServiceUnavailable,
            ApiError("esv_unconfigured", "ESV service is not configured (set ESV_API_TOKEN)"),
        )
    }
    try {
        call.advertiseCanonicalScope(scope)
        val fileName = PdfFileNames.withSet(scope.set.simpleName, baseFileName)
        respondCachedPdf(svc, pdfCache, fileName, layoutRevision) { typstSource(svc) }
    } catch (e: EsvUpstreamException) {
        call.respond(HttpStatusCode.BadGateway, ApiError("esv_upstream", e.message ?: "ESV API error"))
    }
}

/**
 * The study guide PDF — the student copy ([markAnswers] false) or the answer copy ([markAnswers] true, each
 * correct choice starred inline with no key at the end). Pure curated TSV (no ESV), so it is gated only on a
 * study set and cached under a stamp of the questions + year + logo + which copy (so the two never collide).
 */
private suspend fun io.ktor.server.routing.RoutingContext.respondStudyGuidePdf(
    study: StudyDataRegistry?,
    pdfCache: PdfCache?,
    seasons: SeasonRepository,
    markAnswers: Boolean,
) {
    val scope = call.resolveEsvScopeOrRespond(seasons.currentStudySet()) ?: return
    val svc = study?.forSet(scope.set) ?: return call.respond(
        HttpStatusCode.ServiceUnavailable,
        ApiError("esv_unconfigured", "Study set is not configured"),
    )
    val guide = StudyGuideParser.loadStudyGuideOrNull(svc.studySet) ?: return call.respond(
        HttpStatusCode.NotFound,
        ApiError("no_study_guide", "This study set has no study guide"),
    )
    val year = seasons.current().eventYear
    val logo = tbbLogoBytes()
    val fileName = PdfFileNames.withSet(
        scope.set.simpleName,
        if (markAnswers) PdfFileNames.studyGuideAnswers() else PdfFileNames.studyGuide(),
    )
    // Fold the logo, the copy flag and the layout revision into the stamp so a new logo, the other copy,
    // or a change to how the guide is drawn never serves a stale PDF.
    val stamp = 31 * guide.hashCode() + year + (logo?.contentHashCode() ?: 0) +
        (if (markAnswers) 1 else 0) + LayoutRevisions.STUDY_GUIDE
    // `?format=typ` serves the markup instead, cached under the `.typ` sibling and the same stamp. No ESV
    // gate: the guide is our own curated TSV. The source does `#image("tbb-logo.png")`, so compiling it
    // outside the server needs that file dropped in beside it — everything else is self-contained.
    if (call.wantsTypstSource()) {
        val typFileName = fileName.asTypstFileName()
        val cachedSource =
            pdfCache?.let { c -> withContext(Dispatchers.IO) { c.get(svc.studySet.simpleName, typFileName, stamp) } }
        if (cachedSource != null) return respondAttachment(cachedSource, typFileName, TYPST_CONTENT_TYPE)
        val bytes = stampLayoutRevision(
            studyGuideTypst(
                guide, svc.studySet, year, logoFile = logo?.let { STUDY_GUIDE_LOGO_FILE }, markAnswers = markAnswers,
            ),
            LayoutRevisions.STUDY_GUIDE,
        ).encodeToByteArray()
        pdfCache?.let { c -> withContext(Dispatchers.IO) { c.put(svc.studySet.simpleName, typFileName, stamp, bytes) } }
        return respondAttachment(bytes, typFileName, TYPST_CONTENT_TYPE)
    }
    val cached = pdfCache?.let { c -> withContext(Dispatchers.IO) { c.get(svc.studySet.simpleName, fileName, stamp) } }
    if (cached != null) return respondAttachment(cached, fileName, ContentType.Application.Pdf)
    try {
        val source = studyGuideTypst(
            guide, svc.studySet, year, logoFile = logo?.let { STUDY_GUIDE_LOGO_FILE }, markAnswers = markAnswers,
        )
        val assets = logo?.let { mapOf(STUDY_GUIDE_LOGO_FILE to it) } ?: emptyMap()
        val pdf = withContext(Dispatchers.IO) {
            TypstCompiler.compile(source, LayoutRevisions.STUDY_GUIDE, assets = assets)
        }
        pdfCache?.let { c -> withContext(Dispatchers.IO) { c.put(svc.studySet.simpleName, fileName, stamp, pdf) } }
        respondAttachment(pdf, fileName, ContentType.Application.Pdf)
    } catch (e: TypstException) {
        call.respond(HttpStatusCode.ServiceUnavailable, ApiError("typst_failed", e.message ?: "PDF generation failed"))
    }
}

/**
 * Serves the PDF from [pdfCache] when a row matches ([fileName], content stamp) — skipping both the
 * Typst compile and the markup build entirely — otherwise builds [typstSource], compiles, stores, and
 * responds. [fileName] doubles as the cache key, so it must encode every generation param (use
 * [PdfFileNames]). Concurrent misses may compile twice; the upsert makes that benign. May throw
 * [EsvUpstreamException] (resolving the stamp needs the study text) — callers already catch it.
 *
 * [layoutRevision] is the generator's [LayoutRevisions] entry, folded into the cache stamp (a
 * rendering change is invisible to the content stamp, so this is what retires artifacts cached before
 * it) and stamped on both artifacts — printed as the corner mark on the compiled PDF and wrapped into
 * the served source — so the number a document shows and the key it caches under can never disagree.
 * [extraStampSalt] folds in any other request input the content stamp can't see (e.g. the season's
 * event-date footer).
 *
 * `?format=typ` serves the markup instead, cached under the `.typ` sibling filename and the very same
 * stamp — so the source invalidates on exactly the same events as the PDF, and a [LayoutRevisions] bump
 * retires both.
 *
 * [gatedSourceUsers] is non-null for the study text alone, whose markup reproduces the running ESV text
 * and so is SEASON_MANAGE-only (see [allowStudyTextSource]); everything else here serves its source as
 * publicly as its PDF.
 */
private suspend fun io.ktor.server.routing.RoutingContext.respondCachedPdf(
    study: StudyDataService,
    pdfCache: PdfCache?,
    fileName: String,
    layoutRevision: Int,
    extraStampSalt: Int = 0,
    gatedSourceUsers: UserRepository? = null,
    typstSource: suspend () -> String,
) {
    val studySet = study.studySet.simpleName
    val stamp = study.contentStamp() + 31 * extraStampSalt + layoutRevision
    if (call.wantsTypstSource()) {
        if (gatedSourceUsers != null && !allowStudyTextSource(gatedSourceUsers)) return
        val typFileName = fileName.asTypstFileName()
        val cachedSource =
            pdfCache?.let { cache -> withContext(Dispatchers.IO) { cache.get(studySet, typFileName, stamp) } }
        if (cachedSource != null) return respondAttachment(cachedSource, typFileName, TYPST_CONTENT_TYPE)
        val bytes = stampLayoutRevision(typstSource(), layoutRevision).encodeToByteArray()
        pdfCache?.let { cache -> withContext(Dispatchers.IO) { cache.put(studySet, typFileName, stamp, bytes) } }
        return respondAttachment(bytes, typFileName, TYPST_CONTENT_TYPE)
    }
    val cached = pdfCache?.let { cache -> withContext(Dispatchers.IO) { cache.get(studySet, fileName, stamp) } }
    if (cached != null) return respondAttachment(cached, fileName, ContentType.Application.Pdf)
    val source = typstSource()
    try {
        val pdf = withContext(Dispatchers.IO) { TypstCompiler.compile(source, layoutRevision) }
        pdfCache?.let { cache -> withContext(Dispatchers.IO) { cache.put(studySet, fileName, stamp, pdf) } }
        respondAttachment(pdf, fileName, ContentType.Application.Pdf)
    } catch (e: TypstException) {
        call.respond(HttpStatusCode.ServiceUnavailable, ApiError("typst_failed", e.message ?: "PDF generation failed"))
    }
}
