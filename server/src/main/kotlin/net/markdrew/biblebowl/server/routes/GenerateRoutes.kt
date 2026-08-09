package net.markdrew.biblebowl.server.routes

import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
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
import net.markdrew.biblebowl.analysis.namesIndex
import net.markdrew.biblebowl.analysis.oneTimeWords
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
import net.markdrew.biblebowl.model.NO_BOOK_FORMAT
import net.markdrew.biblebowl.model.StudyScope
import net.markdrew.biblebowl.model.StudySet
import net.markdrew.biblebowl.server.data.QuestionRepository
import net.markdrew.biblebowl.server.data.SeasonRepository
import net.markdrew.biblebowl.server.data.UserRepository
import net.markdrew.biblebowl.server.esv.EsvUpstreamException
import net.markdrew.biblebowl.server.export.KahootQuestion
import net.markdrew.biblebowl.server.export.kahootXlsx
import net.markdrew.biblebowl.server.export.quizletCsv
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
 * Revision of the study-text layout, folded into that PDF's cache stamp. Bump it whenever
 * `bibleTextTypst` renders the same options differently, else the content-stamped cache keeps
 * serving the old layout. 1 = footnotes sized relative to the body text (were a fixed 10pt).
 */
// Bumped whenever the study-text layout changes, so PDFs cached under the old rendering are retired.
// 2: headings sized relative to the body text (was a fixed 14pt/16pt).
private const val BIBLE_TEXT_LAYOUT_REVISION = 2

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
                val scope = call.resolveEsvScopeOrRespond(seasonSet) ?: return@get
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
                        (scope.chapterRef?.let { " for ${it.book.fullName} ${it.chapter}" } ?: "")),
                )
            }

            call.advertiseCanonicalScope(scope)
            val typstSource = practiceTestTypst(round, pool)
            val fileName = "practice-${round.name.lowercase()}${scope.chapterSuffix()}.pdf"
            respondPdf(typstSource, PdfFileNames.withSet(scope.set.simpleName, fileName))
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
            val pool = questions.list(QuestionStatus.APPROVED, scope.toQuestionScope(), roundType = round, limit = 200)
            if (pool.isEmpty()) {
                return@get call.respond(
                    HttpStatusCode.NotFound,
                    ApiError("no_questions", "No approved questions" +
                        (scope.chapterRef?.let { " for ${it.book.fullName} ${it.chapter}" } ?: "")),
                )
            }
            call.advertiseCanonicalScope(scope)
            val fileName = PdfFileNames.withSet(scope.set.simpleName, "flashcards${scope.chapterSuffix()}.pdf")
            respondPdf(flashcardsTypst(pool.toFlashcards()), fileName)
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
                // salt the stamp with it so editing the event dates refreshes cached study texts, and
                // fold in the layout revision so a rendering change retires PDFs cached before it.
                val salt = 31 * options.dateLine.hashCode() + BIBLE_TEXT_LAYOUT_REVISION
                respondCachedPdf(svc, pdfCache, fileName, stampSalt = salt) {
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
            respondIndexPdf(study, seasons, pdfCache, PdfFileNames.numbersIndex()) { s ->
                numbersIndexTypst(s.studyData())
            }
        }

        // GET /generate/names-index.pdf?set=acts — the set's names index (alphabetical + by frequency)
        get("/generate/names-index.pdf") {
            respondIndexPdf(study, seasons, pdfCache, PdfFileNames.namesIndex()) { s ->
                indexTypst(s.studyData(), namesIndex(s.studyData(), s.categoryResolution()), "Name")
            }
        }

        // GET /generate/men-index.pdf?set=acts — the men named in the set (alphabetical + by frequency)
        get("/generate/men-index.pdf") {
            respondIndexPdf(study, seasons, pdfCache, PdfFileNames.menIndex()) { s ->
                val sd = s.studyData()
                indexTypst(
                    sd, wordListIndex(sd, s.categoryResolution(), WordList.MEN),
                    "Man", plural = "Men", title = "${sd.studySet.name} Men Index",
                )
            }
        }

        // GET /generate/women-index.pdf?set=acts
        get("/generate/women-index.pdf") {
            respondIndexPdf(study, seasons, pdfCache, PdfFileNames.womenIndex()) { s ->
                val sd = s.studyData()
                indexTypst(
                    sd, wordListIndex(sd, s.categoryResolution(), WordList.WOMEN),
                    "Woman", plural = "Women", title = "${sd.studySet.name} Women Index",
                )
            }
        }

        // GET /generate/places-index.pdf?set=acts
        get("/generate/places-index.pdf") {
            respondIndexPdf(study, seasons, pdfCache, PdfFileNames.placesIndex()) { s ->
                val sd = s.studyData()
                indexTypst(
                    sd, wordListIndex(sd, s.categoryResolution(), WordList.PLACES),
                    "Place", title = "${sd.studySet.name} Places Index",
                )
            }
        }

        // GET /generate/full-index.pdf?set=acts — a complete word concordance for the set
        get("/generate/full-index.pdf") {
            respondIndexPdf(study, seasons, pdfCache, PdfFileNames.fullIndex()) { s ->
                val sd = s.studyData()
                indexTypst(sd, fullIndex(sd), "Word", title = "${sd.studySet.name} Complete Word Index")
            }
        }

        // GET /generate/unique-words-index.pdf?set=acts — the hapax index (alphabetical + by appearance)
        get("/generate/unique-words-index.pdf") {
            respondIndexPdf(study, seasons, pdfCache, PdfFileNames.uniqueWordsIndex()) { s ->
                oneTimeWordsIndexTypst(s.studyData())
            }
        }

        // GET /generate/unique-word-flashcards.pdf?set=acts — one card per one-time word: the word up
        // front, its verse (with the verse text as context, the word underlined) on the back. Cards run
        // in the word's order of appearance. stampSalt is a format revision: bump it whenever the deck's
        // rendering changes, else the content-stamped cache keeps serving the old layout.
        get("/generate/unique-word-flashcards.pdf") {
            respondIndexPdf(study, seasons, pdfCache, PdfFileNames.uniqueWordFlashcards(), stampSalt = 1) { s ->
                val sd = s.studyData()
                val ranges = oneTimeWords(sd).sortedBy { it.first }
                val whitespace = Regex("\\s+")
                val cards = ranges.mapIndexedNotNull { i, range ->
                    val verse = sd.verseEnclosing(range) ?: return@mapIndexedNotNull null
                    // Locate the word in its verse by raw char offsets (not text search, which could
                    // hit a substring of another word), then emphasize it in a markdown note.
                    val note = sd.verseIndex[verse]?.let { verseRange ->
                        val raw = sd.excerpt(verseRange).excerptText
                        val start = range.first - verseRange.first
                        val end = start + (range.last - range.first + 1)
                        val pre = raw.substring(0, start).replace(whitespace, " ").trimStart()
                        val word = raw.substring(start, end)
                        val post = raw.substring(end).replace(whitespace, " ").trimEnd()
                        CardText.Markdown(
                            markdownEscape(pre) + "**<u>" + markdownEscape(word) + "</u>**" + markdownEscape(post)
                        )
                    } ?: CardText.Plain("")
                    Flashcard(
                        front = CardText.Plain(sd.excerpt(range).excerptText),
                        back = CardText.Plain(sd.verseRefFormat(verse)),
                        note = note,
                        footer = "${i + 1} of ${ranges.size}",
                    )
                }
                flashcardsTypst(cards)
            }
        }

        // GET /generate/questions.csv?source=questions|headings&round=FACT_FINDER&chapter=2
        // Comma-separated term/definition pairs, import-ready for Quizlet/Space/Anki. `source=questions`
        // (default) exports the approved bank (prompt -> answer); `source=headings` exports the R5
        // headings (title -> chapter), with `chapter` meaning "through chapter" as usual for headings.
        get("/generate/questions.csv") {
            respondExport(questions, seasons, study, format = ExportFormat.CSV)
        }

        // GET /generate/questions.xlsx?source=questions|headings&round=FACT_FINDER&chapter=2
        // A Kahoot-import spreadsheet (their template layout). Only multiple-choice material can go
        // to Kahoot, so `source=questions` keeps just questions whose choices contain the answer;
        // `source=headings` builds which-chapter questions with in-scope distractor chapters.
        get("/generate/questions.xlsx") {
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
            val throughRef = scope.chapterRef

            try {
                call.advertiseCanonicalScope(scope, chapterKey = StudyScopeParams.THROUGH_CHAPTER)
                val fileName = PdfFileNames.withSet(
                    scope.set.simpleName, "heading-flashcards${scope.chapterSuffix(cumulative = true)}.pdf",
                )
                respondCachedPdf(svc, pdfCache, fileName) {
                    // ChapterRef comparison is book-aware (absoluteChapter), so cumulative scoping is
                    // correct for multi-book sets too.
                    val headings = svc.studyData().headings
                        .filter { throughRef == null || it.chapterRange.start <= throughRef }
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
            respondIndexPdf(study, seasons, pdfCache, PdfFileNames.chapterHeadings()) { s ->
                val sd = s.studyData()
                // A heading never spans books, so grouping keeps scripture order and lets each row's
                // reference stay book-less — the band above it names the book. Single-book sets get no
                // bands at all (the sheet title already says the book).
                val multiBook = !sd.studySet.isSingleBook
                val books = sd.headings.groupBy { it.verseRange.start.book }.map { (book, headings) ->
                    ChapterHeadingBook(
                        book = book.fullName.takeIf { multiBook },
                        headings = headings.map {
                            ChapterHeadingRow(it.title, it.verseRange.format(NO_BOOK_FORMAT))
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

    val content = studyData.practice(scope.chapterRef)
    val practiceTest = PracticeTest(round, content, randomSeed = seed ?: Random.nextInt(1..9_999))
    val typstSource: String? = when (round) {
        Round.FIND_THE_VERSE -> findTheVerseTypst(practiceTest)
        Round.QUOTES -> quotesTypst(practiceTest)
        Round.EVENTS -> eventsTypst(practiceTest)
        else -> null // unreachable: guarded by round.textGenerated at the call site
    }
    if (typstSource == null) {
        return call.respond(
            HttpStatusCode.UnprocessableEntity,
            ApiError("not_enough_chapters", "Not enough chapters covered to build a ${round.displayName} test"),
        )
    }
    call.advertiseCanonicalScope(scope)
    val fileName = "practice-${round.name.lowercase()}${scope.chapterSuffix(cumulative = true)}.pdf"
    respondPdf(typstSource, PdfFileNames.withSet(scope.set.simpleName, fileName))
}



private enum class ExportFormat { CSV, KAHOOT_XLSX }

/**
 * Responds with an import-ready export of the question bank or the R5 headings (see the route
 * comments for parameter semantics). Both formats share source selection; only the rendering and
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
        val pool = questions.list(QuestionStatus.APPROVED, scope.toQuestionScope(), roundType = round, limit = 500)
        call.advertiseCanonicalScope(scope)
        val baseName = PdfFileNames.withSet(
            scope.set.simpleName,
            "questions${round?.let { "-${it.name.lowercase()}" } ?: ""}${scope.chapterSuffix()}",
        )
        when (format) {
            ExportFormat.CSV -> {
                if (pool.isEmpty()) return call.respond(HttpStatusCode.NotFound, ApiError("no_questions", "No approved questions match"))
                respondAttachment(quizletCsv(pool.map { it.prompt to it.answer }).toByteArray(), "quizlet-$baseName.csv", CSV_CONTENT_TYPE)
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
                val rows = mc.take(100).map { q ->
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

    // source == "headings" — the R5 material; `chapter` scopes cumulatively (through chapter N).
    val scope = call.resolveEsvScopeOrRespond(seasonSet) ?: return
    val svc = study?.forSet(scope.set)
    if (svc == null || !svc.isConfigured) {
        return call.respond(
            HttpStatusCode.ServiceUnavailable,
            ApiError("esv_unconfigured", "ESV service is not configured (set ESV_API_TOKEN)"),
        )
    }
    val throughRef = scope.chapterRef
    val headings = try {
        // Book-aware cumulative filter (ChapterRef compares by absoluteChapter across books).
        svc.studyData().headings.filter { throughRef == null || it.chapterRange.start <= throughRef }
    } catch (e: EsvUpstreamException) {
        return call.respond(HttpStatusCode.BadGateway, ApiError("esv_upstream", e.message ?: "ESV API error"))
    }
    if (headings.isEmpty()) return call.respond(HttpStatusCode.NotFound, ApiError("no_headings", "No headings in scope"))
    call.advertiseCanonicalScope(scope)
    val baseName = PdfFileNames.withSet(scope.set.simpleName, "headings${scope.chapterSuffix(cumulative = true)}")
    when (format) {
        ExportFormat.CSV -> respondAttachment(
            quizletCsv(headings.map { it.title to chapterLabel(scope.set, it.chapterRange.start) }).toByteArray(),
            "quizlet-$baseName.csv",
            CSV_CONTENT_TYPE,
        )
        ExportFormat.KAHOOT_XLSX -> {
            val chaptersInScope = headings.map { it.chapterRange.start }.distinct()
            val rows = headings.take(100).mapIndexed { i, h ->
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

/** Compiles [typstSource] off the event loop and responds with PDF bytes as a named attachment. */
private suspend fun io.ktor.server.routing.RoutingContext.respondPdf(typstSource: String, fileName: String) {
    try {
        val pdf = withContext(Dispatchers.IO) { TypstCompiler.compile(typstSource) }
        respondAttachment(pdf, fileName, ContentType.Application.Pdf)
    } catch (e: TypstException) {
        call.respond(HttpStatusCode.ServiceUnavailable, ApiError("typst_failed", e.message ?: "PDF generation failed"))
    }
}

/**
 * Serves the PDF from [pdfCache] when a row matches ([fileName], content stamp) — skipping both the
 * Typst compile and the markup build entirely — otherwise builds [typstSource], compiles, stores, and
 * responds. [fileName] doubles as the cache key, so it must encode every generation param (use
 * [PdfFileNames]). [stampSalt] folds request inputs the content stamp doesn't cover (e.g. the
 * season's event-date footer) into the row's validity. Concurrent misses may compile twice; the
 * upsert makes that benign. May throw [EsvUpstreamException] (resolving the stamp needs the study
 * text) — callers already catch it.
 */
/**
 * The shared shape of the study-set index PDFs: `set=`-scoped (allowlisted, defaulting to the season),
 * ESV-gated (503 if unconfigured), cached, Typst-compiled, with upstream ESV failures mapped to 502.
 * [typstSource] receives the non-null, configured study service for the resolved set.
 */
private suspend fun io.ktor.server.routing.RoutingContext.respondIndexPdf(
    study: StudyDataRegistry?,
    seasons: SeasonRepository,
    pdfCache: PdfCache?,
    baseFileName: String,
    stampSalt: Int = 0,
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
        respondCachedPdf(svc, pdfCache, fileName, stampSalt) { typstSource(svc) }
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
    // Fold the logo + copy flag into the stamp so a new logo, or the other copy, never serves a stale PDF.
    val stamp = 31 * guide.hashCode() + year + (logo?.contentHashCode() ?: 0) + if (markAnswers) 1 else 0
    val cached = pdfCache?.let { c -> withContext(Dispatchers.IO) { c.get(svc.studySet.simpleName, fileName, stamp) } }
    if (cached != null) return respondAttachment(cached, fileName, ContentType.Application.Pdf)
    try {
        val source = studyGuideTypst(
            guide, svc.studySet, year, logoFile = logo?.let { STUDY_GUIDE_LOGO_FILE }, markAnswers = markAnswers,
        )
        val assets = logo?.let { mapOf(STUDY_GUIDE_LOGO_FILE to it) } ?: emptyMap()
        val pdf = withContext(Dispatchers.IO) { TypstCompiler.compile(source, assets = assets) }
        pdfCache?.let { c -> withContext(Dispatchers.IO) { c.put(svc.studySet.simpleName, fileName, stamp, pdf) } }
        respondAttachment(pdf, fileName, ContentType.Application.Pdf)
    } catch (e: TypstException) {
        call.respond(HttpStatusCode.ServiceUnavailable, ApiError("typst_failed", e.message ?: "PDF generation failed"))
    }
}

private suspend fun io.ktor.server.routing.RoutingContext.respondCachedPdf(
    study: StudyDataService,
    pdfCache: PdfCache?,
    fileName: String,
    stampSalt: Int = 0,
    typstSource: suspend () -> String,
) {
    val studySet = study.studySet.simpleName
    val stamp = study.contentStamp() + stampSalt
    val cached = pdfCache?.let { cache -> withContext(Dispatchers.IO) { cache.get(studySet, fileName, stamp) } }
    if (cached != null) return respondAttachment(cached, fileName, ContentType.Application.Pdf)
    val source = typstSource()
    try {
        val pdf = withContext(Dispatchers.IO) { TypstCompiler.compile(source) }
        pdfCache?.let { cache -> withContext(Dispatchers.IO) { cache.put(studySet, fileName, stamp, pdf) } }
        respondAttachment(pdf, fileName, ContentType.Application.Pdf)
    } catch (e: TypstException) {
        call.respond(HttpStatusCode.ServiceUnavailable, ApiError("typst_failed", e.message ?: "PDF generation failed"))
    }
}
