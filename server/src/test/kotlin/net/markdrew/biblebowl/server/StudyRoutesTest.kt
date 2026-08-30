package net.markdrew.biblebowl.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import net.markdrew.biblebowl.api.HeadingDto
import net.markdrew.biblebowl.api.IndexEntryDto
import net.markdrew.biblebowl.generate.LayoutRevisions
import net.markdrew.biblebowl.generation.typst.ChapterHeadingBook
import net.markdrew.biblebowl.generation.typst.ChapterHeadingChapter
import net.markdrew.biblebowl.generation.typst.ChapterHeadingRow
import net.markdrew.biblebowl.generation.typst.chapterHeadingsTypst
import kotlinx.coroutines.runBlocking
import net.markdrew.biblebowl.model.Book
import net.markdrew.biblebowl.model.ChapterRef
import net.markdrew.biblebowl.model.StandardStudySet
import net.markdrew.biblebowl.model.StudySet
import net.markdrew.biblebowl.model.VerseRef
import kotlin.time.Duration.Companion.milliseconds
import net.markdrew.biblebowl.server.data.DEFAULT_SEASON
import net.markdrew.biblebowl.server.data.InMemoryQuestionRepository
import net.markdrew.biblebowl.server.data.InMemorySeasonRepository
import net.markdrew.biblebowl.server.data.InMemoryUserRepository
import net.markdrew.biblebowl.server.esv.EsvPassageService
import net.markdrew.biblebowl.server.esv.InMemoryEsvCache
import net.markdrew.biblebowl.server.security.JwtService
import net.markdrew.biblebowl.server.study.StudyDataRegistry
import net.markdrew.biblebowl.server.study.StudyDataService
import net.markdrew.biblebowl.server.routes.headingReference
import net.markdrew.biblebowl.server.typst.TypstCompiler
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/** ESV text-format chapter bodies keyed by packed absolute-verse query, in EsvIndexer's expected shape. */
private val CHAPTER_TEXTS = mapOf(
    "44001001-44001999" to """
        _______________________________________________________
        The Promise of the Holy Spirit

          [1] In the first book, O Theophilus, I have dealt with all that Jesus began to do and teach, [2] until the day when he was taken up.
    """.trimIndent(),
    "44002001-44002999" to """
        _______________________________________________________
        The Coming of the Holy Spirit

          [1] When the day of Pentecost arrived, they were all together in one place. [2] And suddenly there came from heaven a sound.
    """.trimIndent(),
)

/** A generated PDF reduced to what's deterministic about it: its name and its render. */
private data class RenderedPdf(val fileName: String, val render: String)

/**
 * The parts of a Typst PDF that change on every compile even when the source doesn't: the wall clock
 * (`/CreationDate`, `/ModDate`, and the XMP packet's dates) and the timestamp-derived `/ID` and
 * `xmpMM:InstanceID`. Verified against typst 0.14.2: strip these and back-to-back compiles of identical
 * source are byte-identical, while any real rendering difference survives.
 */
private val PDF_COMPILE_TIMESTAMPS = Regex(
    """/(?:CreationDate|ModDate)\s*\(D:[^)]*\)|/ID\s*\[[^]]*]|<\?xpacket begin.*?<\?xpacket end[^>]*>""",
    RegexOption.DOT_MATCHES_ALL,
)

/**
 * The PDF's bytes with the per-compile timestamps removed, as a latin-1 string (a lossless byte-per-char
 * view — this is a comparison key, not text). Comparing raw PDF bytes is meaningless: two compiles seconds
 * apart always differ, so `!a.contentEquals(b)` holds even when the render options were ignored entirely.
 */
private fun ByteArray.pdfRenderBytes(): String =
    String(this, Charsets.ISO_8859_1).replace(PDF_COMPILE_TIMESTAMPS, "")

/**
 * The page count off the page-tree root's `/Count`. Typst writes that node uncompressed, so this
 * needs no PDF library; it fails loudly rather than returning 0 if that ever stops being true.
 */
private fun ByteArray.pdfPageCount(): Int =
    Regex("""/Count\s+(\d+)""").find(String(this, Charsets.ISO_8859_1))?.groupValues?.get(1)?.toInt()
        ?: fail("no /Count in the PDF's page tree")

class StudyRoutesTest {

    private fun mockEsvClient(): HttpClient = HttpClient(MockEngine { request ->
        val query = request.url.parameters["q"] ?: ""
        val text = CHAPTER_TEXTS.getValue(query)
        val chapter = query.substring(4, 7).trimStart('0')
        respond(
            content = """
                {
                  "query": "$query",
                  "canonical": "Acts $chapter",
                  "passages": [${Json.encodeToString(text)}]
                }
            """.trimIndent(),
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
        )
    })

    private fun studyService() = StudyDataService(
        esv = EsvPassageService(
            client = mockEsvClient(),
            cache = InMemoryEsvCache(),
            token = "test-esv-token",
            baseUrl = "https://fake.esv",
        ),
        studySet = StudySet("Acts 1-2", "acts-test", Book.ACT.chapterRange(1, 2)),
    )

    @Test
    fun defaultActsSetFetchesExactlyItsChapterCountNotTheSentinel() = runBlocking {
        // Regression: the DEFAULT Acts set uses an open-ended sentinel chapter range (to end of book). Build
        // must clamp to Book.ACT.chapterCount (28) — not fire ~999 ESV calls for chapters that don't exist.
        val queries = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            val query = request.url.parameters["q"] ?: ""
            queries += query
            respond(
                content = """
                    {
                      "query": "$query",
                      "canonical": "Acts",
                      "passages": [${Json.encodeToString("_______________________________________________________\nA Heading\n\n  [1] Some verse text.")}]
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        })
        val service = StudyDataService(
            esv = EsvPassageService(
                client = client,
                cache = InMemoryEsvCache(),
                token = "test-esv-token",
                baseUrl = "https://fake.esv",
                minFetchInterval = 0.milliseconds,
            ),
            studySet = StandardStudySet.DEFAULT, // Acts, sentinel "to end of book" range
        )

        service.studyData()
        assertEquals(Book.ACT.chapterCount, queries.size, "one ESV call per real chapter of Acts, no more")
        assertEquals(28, queries.size)
        assertEquals(28L, service.esvCallCount)
    }

    @Test
    fun headingsEndpointServesParsedHeadings() = testApplication {
        application {
            module(
                InMemoryUserRepository(), InMemoryQuestionRepository(),
                JwtService(secret = "test-secret"), esv = null, study = StudyDataRegistry.fixed(studyService()),
            )
        }
        val json = Json { ignoreUnknownKeys = true }
        val api = createClient { install(ContentNegotiation) { json(json) } }

        // Study material is public — no sign-in needed.
        val res = api.get("/study/headings")
        assertEquals(HttpStatusCode.OK, res.status)
        val headings: List<HeadingDto> = json.decodeFromString(res.bodyAsText())
        assertEquals(
            listOf("The Promise of the Holy Spirit", "The Coming of the Holy Spirit"),
            headings.map { it.title },
        )
        assertEquals(listOf(1, 2), headings.map { it.chapter })
        assertEquals(listOf(1, 2), headings.map { it.index })
        assertEquals(listOf(2, 2), headings.map { it.total })
        assertEquals("1:1-2", headings.first().reference)

        // throughChapter filter
        val filtered = api.get("/study/headings?throughChapter=1")
        val filteredHeadings: List<HeadingDto> = json.decodeFromString(filtered.bodyAsText())
        assertEquals(listOf("The Promise of the Holy Spirit"), filteredHeadings.map { it.title })
    }

    @Test
    fun headingFlashcardsAcceptAnAllowlistedSetParamAndNameTheFileByIt() = testApplication {
        if (!TypstCompiler.isAvailable) {
            println("typst not on PATH; skipping PDF compile test")
            return@testApplication
        }
        application {
            module(
                InMemoryUserRepository(), InMemoryQuestionRepository(),
                JwtService(secret = "test-secret"), esv = null, study = StudyDataRegistry.fixed(studyService()),
            )
        }
        val api = createClient { }

        // A non-current standard set is served (durable off-year links) and set-prefixes the filename.
        val res = api.get("/generate/heading-flashcards.pdf?set=john")
        assertEquals(HttpStatusCode.OK, res.status)
        assertTrue("john-heading-flashcards.pdf" in res.headers[io.ktor.http.HttpHeaders.ContentDisposition].orEmpty())

        // Unknown or non-standard sets are rejected before any ESV/Typst work.
        assertEquals(HttpStatusCode.BadRequest, api.get("/generate/heading-flashcards.pdf?set=zzz").status)
    }

    @Test
    fun headingFlashcardsPdfEndpointCompiles() = testApplication {
        if (!TypstCompiler.isAvailable) {
            println("typst not on PATH; skipping PDF compile test")
            return@testApplication
        }
        application {
            module(
                InMemoryUserRepository(), InMemoryQuestionRepository(),
                JwtService(secret = "test-secret"), esv = null, study = StudyDataRegistry.fixed(studyService()),
            )
        }
        val json = Json { ignoreUnknownKeys = true }
        val api = createClient { install(ContentNegotiation) { json(json) } }

        // PDF generation is public (rate-limited, not auth-gated).
        val res = api.get("/generate/heading-flashcards.pdf")
        assertEquals(HttpStatusCode.OK, res.status)
        val bytes = res.bodyAsBytes()
        assertTrue(bytes.size > 4 && bytes.decodeToString(0, 4) == "%PDF", "response should be a PDF")
    }

    @Test
    fun chapterHeadingsPdfEndpointCompilesToASetPrefixedOnePager() = testApplication {
        if (!TypstCompiler.isAvailable) {
            println("typst not on PATH; skipping PDF compile test")
            return@testApplication
        }
        application {
            module(
                InMemoryUserRepository(), InMemoryQuestionRepository(),
                JwtService(secret = "test-secret"), esv = null, study = StudyDataRegistry.fixed(studyService()),
            )
        }
        val api = createClient { }

        val res = api.get("/generate/chapter-headings.pdf")
        assertEquals(HttpStatusCode.OK, res.status)
        val bytes = res.bodyAsBytes()
        assertTrue(bytes.size > 4 && bytes.decodeToString(0, 4) == "%PDF", "response should be a PDF")
        // Named by the resolved scope's set (the season's, with no ?set=), not the fixture's.
        assertTrue("acts-chapter-headings.pdf" in res.headers[HttpHeaders.ContentDisposition].orEmpty())
        assertEquals(1, bytes.pdfPageCount(), "the sheet must be a one-pager")

        // Whole-set only: unknown sets are rejected before any ESV/Typst work.
        assertEquals(HttpStatusCode.BadRequest, api.get("/generate/chapter-headings.pdf?set=zzz").status)

        // The chapter is printed beside the row, so the rows themselves carry verses only.
        val source = api.get("/generate/chapter-headings.pdf?format=typ").bodyAsText()
        assertContains(source, """(title: "The Promise of the Holy Spirit", reference: "1-2"),""")
        assertFalse("""reference: "1:1-2"""" in source, "the chapter must not repeat on the row")
    }

    @Test
    fun headingReferencesDropTheChapterExceptWhenTheHeadingCrossesOne() {
        fun ref(chapter: Int, verse: Int) = VerseRef(ChapterRef(Book.ACT, chapter), verse)

        assertEquals("1-2", (ref(1, 1)..ref(1, 2)).headingReference())
        assertEquals("7", (ref(1, 7)..ref(1, 7)).headingReference(), "a one-verse heading is a bare verse")
        // A heading that runs on into the next chapter is the one place the chapter still earns its space.
        assertEquals("21:37-22:21", (ref(21, 37)..ref(22, 21)).headingReference())
    }

    /**
     * That the fit search really does keep the sheet to one page — the two-heading fixture above would
     * fit at any size, so this drives [chapterHeadingsTypst] directly with a heading list far denser
     * than any real study set (a whole Bible's worth) and compiles it.
     */
    @Test
    fun chapterHeadingsShrinkToOnePageForALargeStudySet() {
        if (!TypstCompiler.isAvailable) {
            println("typst not on PATH; skipping PDF compile test")
            return
        }
        // Three books of 20 chapters, three headings each — 180 headings, denser than any real set.
        val chapters = (1..20).map { chapter ->
            ChapterHeadingChapter(
                chapter,
                (1..3).map { ChapterHeadingRow("Paul and Barnabas Return to Antioch in Syria $it", "$chapter:1-25") },
            )
        }
        val books = listOf("Exodus", "Numbers", "Deuteronomy").map { ChapterHeadingBook(it, chapters) }
        val pdf = TypstCompiler.compile(chapterHeadingsTypst("Life of Moses", books), LayoutRevisions.CHAPTER_HEADINGS)

        assertEquals(1, pdf.pdfPageCount(), "the sheet must shrink to fit rather than spill")
    }

    @Test
    fun categoryAndFullIndexPdfEndpointsCompile() = testApplication {
        if (!TypstCompiler.isAvailable) {
            println("typst not on PATH; skipping PDF compile test")
            return@testApplication
        }
        application {
            module(
                InMemoryUserRepository(), InMemoryQuestionRepository(),
                JwtService(secret = "test-secret"), esv = null, study = StudyDataRegistry.fixed(studyService()),
            )
        }
        val api = createClient { }

        // Each is public and Typst-compiled; an empty category still renders a valid (empty) index PDF.
        // Covers the category/full indices plus the one-time-words index and its flashcard deck.
        listOf(
            "men-index", "women-index", "places-index", "full-index", "unique-words-index",
            "unique-word-flashcards",
        ).forEach { name ->
            val res = api.get("/generate/$name.pdf")
            assertEquals(HttpStatusCode.OK, res.status, "$name should return 200")
            val bytes = res.bodyAsBytes()
            assertTrue(bytes.size > 4 && bytes.decodeToString(0, 4) == "%PDF", "$name should be a PDF")
            assertTrue(
                "$name.pdf" in res.headers[HttpHeaders.ContentDisposition].orEmpty(),
                "$name should attach as $name.pdf",
            )
        }
    }

    @Test
    fun studyGuidePdfAndTsvServeTheBundledGuide() = testApplication {
        application {
            module(
                InMemoryUserRepository(), InMemoryQuestionRepository(),
                JwtService(secret = "test-secret"), esv = null, study = StudyDataRegistry.fixed(studyService()),
            )
        }
        val api = createClient { }

        // The CSV is the raw curated source (authored as TSV) — served with no ESV/Typst dependency.
        val csv = api.get("/generate/study-guide.csv")
        assertEquals(HttpStatusCode.OK, csv.status)
        val body = csv.bodyAsText()
        assertTrue("Theophilus" in body, "raw study guide should stream the curated content")
        assertTrue('\t' !in body, "the curated tabs are re-emitted as CSV commas")
        assertTrue("study-guide.csv" in csv.headers[HttpHeaders.ContentDisposition].orEmpty())

        if (!TypstCompiler.isAvailable) {
            println("typst not on PATH; skipping study-guide PDF compile assertion")
            return@testApplication
        }
        listOf("study-guide", "study-guide-answers").forEach { name ->
            val pdf = api.get("/generate/$name.pdf")
            assertEquals(HttpStatusCode.OK, pdf.status, "$name should return 200")
            val bytes = pdf.bodyAsBytes()
            assertTrue(bytes.size > 4 && bytes.decodeToString(0, 4) == "%PDF", "$name should be a PDF")
            assertTrue("$name.pdf" in pdf.headers[HttpHeaders.ContentDisposition].orEmpty())
        }
    }

    @Test
    fun categoryIndexReturns503WithoutStudyService() = testApplication {
        application {
            module(
                InMemoryUserRepository(), InMemoryQuestionRepository(),
                JwtService(secret = "test-secret"), esv = null, study = null,
            )
        }
        val res = createClient { }.get("/generate/men-index.pdf")
        assertEquals(HttpStatusCode.ServiceUnavailable, res.status)
    }

    @Test
    fun numbersEndpointServesTheNumbersIndex() = testApplication {
        application {
            module(
                InMemoryUserRepository(), InMemoryQuestionRepository(),
                JwtService(secret = "test-secret"), esv = null, study = StudyDataRegistry.fixed(studyService()),
            )
        }
        val json = Json { ignoreUnknownKeys = true }
        val api = createClient { install(ContentNegotiation) { json(json) } }

        val res = api.get("/study/numbers")
        assertEquals(HttpStatusCode.OK, res.status)
        val entries: List<IndexEntryDto> = json.decodeFromString(res.bodyAsText())
        // The Acts 1-2 fixture contains "one" ("in one place") and "first" ("the first book").
        val keys = entries.map { it.key }
        assertTrue(entries.isNotEmpty(), "expected some numbers, got $keys")
        assertTrue(keys.any { it == "one" || it == "first" }, "expected 'one'/'first' among $keys")
        entries.forEach { e -> assertEquals(e.total, e.references.sumOf { it.count }) }
    }

    @Test
    fun uniqueWordsExportForSpaceAndQuizlet() = testApplication {
        application {
            module(
                InMemoryUserRepository(), InMemoryQuestionRepository(),
                JwtService(secret = "test-secret"), esv = null, study = StudyDataRegistry.fixed(studyService()),
            )
        }
        val api = createClient { }

        // Space CSV (import-tested 2026-08): Front,Back header, quoted multi-line definitions in
        // Markdown — heading, then a blank line (a lone newline is only a soft break), then the bold
        // reference, then the verse with the word bold+italic. Cards run in order of appearance.
        val csv = api.get("/generate/unique-word-flashcards.csv")
        assertEquals(HttpStatusCode.OK, csv.status)
        assertTrue("space-acts-unique-words.csv" in csv.headers[HttpHeaders.ContentDisposition].orEmpty())
        val csvBody = csv.bodyAsText()
        assertTrue(csvBody.startsWith("Front,Back\n"), "Space requires the header row")
        val theophilusCard = "Theophilus,\"The Promise of the Holy Spirit\n\n**Acts 1:1**\nIn the first book, " +
            "O ***Theophilus***, I have dealt with all that Jesus began to do and teach,\""
        assertTrue(theophilusCard in csvBody, "unexpected Space card shape in:\n$csvBody")
        assertTrue("The Coming of the Holy Spirit\n\n**Acts 2:1**\n" in csvBody)
        assertTrue(csvBody.indexOf("***Theophilus***") < csvBody.indexOf("***Pentecost***"), "appearance order")

        // Quizlet paste file (import-tested 2026-08): term TAB definition, a blank line between cards
        // (custom "\n\n" card separator on import, so definitions keep their single line breaks);
        // only *bold* markup (underline doesn't survive import).
        val txt = api.get("/generate/unique-word-flashcards.txt")
        assertEquals(HttpStatusCode.OK, txt.status)
        assertTrue("quizlet-acts-unique-words.txt" in txt.headers[HttpHeaders.ContentDisposition].orEmpty())
        val txtBody = txt.bodyAsText()
        val theophilusTxtCard = "Theophilus\tThe Promise of the Holy Spirit\n*Acts 1:1*\nIn the first book, " +
            "O *Theophilus*, I have dealt with all that Jesus began to do and teach,"
        assertTrue("$theophilusTxtCard\n\n" in txtBody, "unexpected Quizlet card shape in:\n$txtBody")
        assertTrue("\n\n\n" !in txtBody, "definitions must not contain blank lines — they'd split the card")
        assertTrue("_" !in txtBody, "no underline markup — Quizlet's importer drops it")
    }

    @Test
    fun headingsExportForSpaceQuizletAndKahoot() = testApplication {
        application {
            module(
                InMemoryUserRepository(), InMemoryQuestionRepository(),
                JwtService(secret = "test-secret"), esv = null, study = StudyDataRegistry.fixed(studyService()),
            )
        }
        val api = createClient { }

        // Space CSV: the Front,Back header its importer requires, then title,chapter rows, anonymous;
        // `chapter` scopes cumulatively.
        val csv = api.get("/generate/space-questions.csv?source=headings")
        assertEquals(HttpStatusCode.OK, csv.status)
        assertTrue("space-acts-headings.csv" in csv.headers[HttpHeaders.ContentDisposition].orEmpty())
        assertEquals(
            listOf(
                "Front,Back",
                "The Promise of the Holy Spirit,Chapter 1",
                "The Coming of the Holy Spirit,Chapter 2",
            ),
            csv.bodyAsText().trim().lines(),
        )
        val filtered = api.get("/generate/space-questions.csv?source=headings&chapter=1")
        assertEquals("Front,Back\nThe Promise of the Holy Spirit,Chapter 1", filtered.bodyAsText().trim())

        // Quizlet paste file: TAB between title and chapter, one card per line — the importer's
        // default shape (single-line material needs no custom between-cards separator), no header.
        val txt = api.get("/generate/quizlet-questions.txt?source=headings")
        assertEquals(HttpStatusCode.OK, txt.status)
        assertTrue("quizlet-acts-headings.txt" in txt.headers[HttpHeaders.ContentDisposition].orEmpty())
        assertEquals(
            listOf(
                "The Promise of the Holy Spirit\tChapter 1",
                "The Coming of the Holy Spirit\tChapter 2",
            ),
            txt.bodyAsText().trim().lines(),
        )

        // Kahoot xlsx: which-chapter questions, distractors only from in-scope chapters.
        val xlsx = api.get("/generate/kahoot-questions.xlsx?source=headings")
        assertEquals(HttpStatusCode.OK, xlsx.status)
        val bytes = xlsx.bodyAsBytes()
        assertEquals("PK", bytes.decodeToString(0, 2), "xlsx must be a zip")
        val sheet = readZipEntry(bytes, "xl/worksheets/sheet1.xml")
        assertTrue("Which chapter has the heading" in sheet)
        assertTrue("The Coming of the Holy Spirit" in sheet)
        assertTrue("Chapter 3" !in sheet, "distractors must stay within the two-chapter fixture scope")
    }

    @Test
    fun bibleTextHeadingSizesReachTheFileNameAndFallBackWhenUnknown() = testApplication {
        if (!TypstCompiler.isAvailable) {
            println("typst not on PATH; skipping PDF compile test")
            return@testApplication
        }
        application {
            module(
                InMemoryUserRepository(), InMemoryQuestionRepository(),
                JwtService(secret = "test-secret"), esv = null, study = StudyDataRegistry.fixed(studyService()),
            )
        }
        val api = createClient { }
        // The attachment name is also the PDF cache key, so it's the assertion that matters: two
        // different heading pairs must never resolve to one name. (That the sizes reach the *render*
        // is covered by [bibleTextPdfHonorsRenderOptions], which compares [pdfRenderBytes] — raw PDF
        // bytes are useless here, since typst stamps every compile with the wall clock.)
        suspend fun name(query: String): String {
            val res = api.get("/generate/bible-text.pdf$query")
            assertEquals(HttpStatusCode.OK, res.status, "for query '$query'")
            return res.headers[HttpHeaders.ContentDisposition].orEmpty()
        }
        assertTrue("acts-bible-text-highlighted.pdf" in name(""), "defaults name no heading sizes")
        assertTrue(
            "acts-bible-text-highlighted-ch-head-small-sec-head-large.pdf" in
                name("?chapterHeadingSize=small&sectionHeadingSize=large"),
            "chosen heading sizes are spelled out in the name",
        )
        // An unrecognized slug renders (and caches as) the default rather than failing the download.
        assertTrue("acts-bible-text-highlighted.pdf" in name("?sectionHeadingSize=enormous"), "unknown → default")
    }

    @Test
    fun bibleTextPdfHonorsRenderOptions() = testApplication {
        if (!TypstCompiler.isAvailable) {
            println("typst not on PATH; skipping PDF compile test")
            return@testApplication
        }
        application {
            module(
                InMemoryUserRepository(), InMemoryQuestionRepository(),
                JwtService(secret = "test-secret"), esv = null, study = StudyDataRegistry.fixed(studyService()),
            )
        }
        val api = createClient { }

        suspend fun pdf(query: String): RenderedPdf {
            val res = api.get("/generate/bible-text.pdf$query")
            assertEquals(HttpStatusCode.OK, res.status, "for query '$query'")
            val bytes = res.bodyAsBytes()
            assertEquals("%PDF", bytes.decodeToString(0, 4), "for query '$query'")
            val disposition = res.headers[HttpHeaders.ContentDisposition]
            assertTrue(disposition != null, "for query '$query'")
            return RenderedPdf(
                fileName = ContentDisposition.parse(disposition).parameter(ContentDisposition.Parameters.FileName)
                    ?: fail("no filename in Content-Disposition for query '$query'"),
                render = bytes.pdfRenderBytes(),
            )
        }

        val default = pdf("")
        val customized = pdf("?fontSize=14&twoColumns=true&justified=true&chapterBreaksPage=true&underlineUniqueWords=true")
        val plain = pdf("?highlight=false")
        val chapterLayout = pdf("?useHeadingsForChapters=true&chapterEndLines=true&verseOnNewLine=true")
        val headingSizes = pdf("?chapterHeadingSize=small&sectionHeadingSize=large")

        // Self-check: two compiles of the SAME request must normalize to identical bytes. Without this, a
        // stale [pdfRenderBytes] (say Typst starts stamping something new) would make every assertion below
        // pass trivially — which is exactly how the old raw-byte version of this test rotted.
        assertEquals(default.render, pdf("").render, "identical requests must render identically once normalized")

        // The filename is also the PDF cache key, so it must spell out every option that was honored.
        assertEquals("acts-bible-text-highlighted.pdf", default.fileName)
        assertEquals(
            "acts-bible-text-highlighted-2col-justified-page-per-ch-unique-words-14pt.pdf",
            customized.fileName,
        )
        assertEquals("acts-bible-text.pdf", plain.fileName)
        assertEquals(
            "acts-bible-text-highlighted-ch-headings-ch-lines-verse-per-line.pdf",
            chapterLayout.fileName,
        )
        assertEquals("acts-bible-text-highlighted-ch-head-small-sec-head-large.pdf", headingSizes.fileName)

        // ...and the options must reach the rendering, not just the name.
        assertTrue(default.render != customized.render, "render options must change the PDF")
        assertTrue(default.render != plain.render, "highlight=false must change the PDF")
        assertTrue(default.render != chapterLayout.render, "chapter/verse layout options must change the PDF")
        assertTrue(default.render != headingSizes.render, "heading sizes must change the PDF")
    }

    @Test
    fun bibleTextFooterDateComesFromTheSeason() {
        if (!TypstCompiler.isAvailable) {
            println("typst not on PATH; skipping PDF compile test")
            return
        }
        // The footer stamps the season's event dates, so the same request against two seasons that
        // differ only in event dates must yield different PDFs (compared with the compile timestamps
        // normalized out — see [pdfRenderBytes]).
        fun pdfForSeason(dateRange: String): String {
            var bytes = ByteArray(0)
            testApplication {
                application {
                    module(
                        InMemoryUserRepository(), InMemoryQuestionRepository(),
                        JwtService(secret = "test-secret"), esv = null, study = StudyDataRegistry.fixed(studyService()),
                        seasons = InMemorySeasonRepository(DEFAULT_SEASON.copy(eventDateRange = dateRange)),
                    )
                }
                val res = createClient { }.get("/generate/bible-text.pdf")
                assertEquals(HttpStatusCode.OK, res.status)
                bytes = res.bodyAsBytes()
            }
            return bytes.pdfRenderBytes()
        }
        val april = pdfForSeason("April 2–4")
        val march = pdfForSeason("March 5–7")
        assertEquals(april, pdfForSeason("April 2–4"), "same season must render identically once normalized")
        assertTrue(april != march, "the season's event dates must appear in the PDF")
    }

    @Test
    fun headingsEndpointReturns503WithoutStudyService() = testApplication {
        application {
            module(
                InMemoryUserRepository(), InMemoryQuestionRepository(),
                JwtService(secret = "test-secret"), esv = null, study = null,
            )
        }
        val json = Json { ignoreUnknownKeys = true }
        val api = createClient { install(ContentNegotiation) { json(json) } }
        val res = api.get("/study/headings")
        assertEquals(HttpStatusCode.ServiceUnavailable, res.status)
    }
}
