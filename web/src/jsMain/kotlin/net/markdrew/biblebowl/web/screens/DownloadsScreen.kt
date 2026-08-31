package net.markdrew.biblebowl.web.screens

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import kotlin.js.json
import net.markdrew.biblebowl.api.HeadingSize
import net.markdrew.biblebowl.api.Permission
import net.markdrew.biblebowl.api.ScopeSelection
import net.markdrew.biblebowl.api.StudyMaterialDto
import net.markdrew.biblebowl.api.StudyMaterialType
import net.markdrew.biblebowl.api.StudyScopeParams
import net.markdrew.biblebowl.api.filePath
import net.markdrew.biblebowl.api.scopeQueryParams
import net.markdrew.biblebowl.model.Round
import net.markdrew.biblebowl.api.StudySection
import net.markdrew.biblebowl.web.Routes
import net.markdrew.biblebowl.web.Session
import net.markdrew.biblebowl.web.Shell
import net.markdrew.biblebowl.web.child
import net.markdrew.biblebowl.web.route
import net.markdrew.biblebowl.web.clear
import net.markdrew.biblebowl.web.onClick
import net.markdrew.biblebowl.web.ui.chapterChips
import net.markdrew.biblebowl.web.ui.chipRow
import net.markdrew.biblebowl.web.ui.optionSwitch
import org.w3c.dom.Element
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.url.URL
import org.w3c.fetch.RequestInit

/** JS global for URL-encoding a book name into the external reader/audio links (e.g. "1 Samuel" → "1%20Samuel"). */
private external fun encodeURIComponent(value: String): String

/**
 * The `?format=typ` twin of a generated-PDF URL: the Typst markup the server compiled that PDF from,
 * with whatever options the card is currently set to.
 *
 * Derived from the href rather than passed per card, so every generated PDF offers its source and a
 * new card can't forget to. Anything that isn't one of our generated PDFs — the CSV/XLSX exports,
 * admin-uploaded study materials — has no Typst behind it and gets nothing.
 */
private fun typstSourceUrl(href: String): String? {
    if ("/generate/" !in href || ".pdf" !in href) return null
    return href + (if ('?' in href) "&" else "?") + "format=typ"
}

/**
 * Whether to offer Typst source at all. The server only gates the study text (whose markup reproduces
 * the running ESV text) and serves every other generator's source publicly — but the *link* is
 * admin-only across the board: it's an authoring tool nobody else asked for, and one uniform rule beats
 * a per-card one that mostly says "hidden". Anyone else who wants it can still add `format=typ` by hand.
 */
private val showTypstSource: Boolean
    get() = Session.user?.let { Permission.SEASON_MANAGE in it.permissions } == true

/**
 * Fetches [href] with the signed-in user's bearer token and saves the response as a file. [what] names
 * the download in any failure alert; [fallbackFileName] is used only if the response arrives without a
 * `Content-Disposition`.
 *
 * A plain `<a href download>` is how every other download here works, but it can't carry an
 * `Authorization` header, and our JWT lives in localStorage rather than a cookie — so a gated download
 * has to come through `fetch` and a blob URL. The filename comes from the server's `Content-Disposition`
 * (CORS-exposed for exactly this), so the saved file keeps its set-prefixed, param-encoded name.
 */
private fun downloadWithAuth(href: String, what: String, fallbackFileName: String) {
    Shell.scope.launch {
        val response = runCatching {
            window.fetch(
                href,
                RequestInit(headers = json("Authorization" to "Bearer ${Session.api.token.orEmpty()}")),
            ).await()
        }.getOrNull()
        if (response == null || !response.ok) {
            window.alert(
                when (response?.status?.toInt()) {
                    401 -> "Please sign in to download $what."
                    403 -> "Your account can't download $what."
                    else -> "Couldn't download $what. Please try again."
                },
            )
            return@launch
        }
        val fileName = FILENAME_PARAM.find(response.headers.get("Content-Disposition").orEmpty())
            ?.groupValues?.get(1)
            ?: fallbackFileName
        val blobUrl = URL.createObjectURL(response.blob().await())
        try {
            (document.createElement("a") as HTMLAnchorElement).apply {
                this.href = blobUrl
                download = fileName
                // Firefox only honours a synthetic click on a connected node.
                document.body?.appendChild(this)
                click()
                remove()
            }
        } finally {
            URL.revokeObjectURL(blobUrl)
        }
    }
}

/** `filename="acts-names-index.typ"` out of a Content-Disposition header (quoted or bare). */
private val FILENAME_PARAM = Regex("""filename\*?=(?:UTF-8'')?"?([^";]+)"?""")

/** Which card's customize panel is expanded. */
private sealed interface Customize {
    data object StudyText : Customize
    data object QuestionFlashcards : Customize
    data object HeadingFlashcards : Customize
    // Per-deck, like Export: the panel is keyed by identity, so two cards sharing one value would
    // expand together. The scope choice behind them is still shared — it's one card group.
    data class VerseDeck(val forSpace: Boolean) : Customize
    data class PracticeTest(val round: Round) : Customize
    data class Export(val app: ExportApp) : Customize
}

/** The importer a questions/headings export card targets — each wants its own file shape. */
private enum class ExportApp { KAHOOT, SPACE, QUIZLET }

/** How chapter titles render: inline with the first verse, or as standalone headings (± divider lines). */
private enum class ChapterStyle(val label: String, val headings: Boolean, val lines: Boolean) {
    HEADING_LINES("Heading with divider line", headings = true, lines = true),
    HEADING("Heading without divider line", headings = true, lines = false),
    INLINE("Same line as first verse", headings = false, lines = false),
}

/** Study-text options, hoisted so choices stick for the whole visit (§7.6 "remember everything cheap"). */
private data class StudyTextChoices(
    val fontSize: Int = 11,
    val twoColumns: Boolean = false,
    val justified: Boolean = false,
    val chapterBreaksPage: Boolean = false,
    val chapterStyle: ChapterStyle = ChapterStyle.HEADING_LINES,
    val verseOnNewLine: Boolean = false,
    val highlight: Boolean = true,
    val underlineUniqueWords: Boolean = true,
    val chapterHeading: HeadingSize = HeadingSize.DEFAULT_CHAPTER,
    val sectionHeading: HeadingSize = HeadingSize.DEFAULT_SECTION,
)

/**
 * The Study & Practice pages: the overview (`#study`) is a card grid — one card per study-focus
 * section, "Start here" on The Text — matching the site's section overview pages (Event etc.);
 * each section (`#study/<slug>`, also a navbar dropdown item) is its own page holding that
 * subject's download cards (one click to a sensible default, options behind "Customize") AND
 * links to its interactive tools (quiz, browsers, community questions), so nothing about a
 * subject lives anywhere else. The section pages lay their cards out in the same tile grid as
 * the overview, so a section reads as a continuation of the grid that led to it. Public.
 *
 * Every download is a plain link to the backend (the generate endpoints send Content-Disposition:
 * attachment), opened in a new tab so a generation error shows its message there instead of
 * navigating the app away; on success the tab closes into a normal download. They are public bar the
 * verse decks, which take `requiresAuth` — see [versesCards].
 */
object DownloadsScreen {

    // Sticky for the whole page session, deliberately outliving route changes. Chapter scope is
    // per card group: each customize panel's chips affect only the cards that panel belongs to.
    // Selections are canonical (book + book-relative chapter), so the emitted download URLs stay
    // valid across the 10-year study rotation.
    private var flashcardScope = ScopeSelection()
    private var headingScope = ScopeSelection()
    private var practiceScope = ScopeSelection()
    private var exportScope = ScopeSelection()
    private var verseScope = ScopeSelection()
    private var customize: Customize? = null
    private var textChoices = StudyTextChoices()
    private var flashcardRound: Round? = null
    private var practiceLimit: Int? = null
    private var practiceSeed: String = ""
    private var exportHeadings = false
    private var exportRound: Round? = null

    private lateinit var root: HTMLElement
    private lateinit var grid: HTMLElement // the current section page's tile row; cards append cols to it
    private var section: StudySection? = null // null = the overview card grid

    // Admin-curated extras (uploaded documents + external links), fetched once per page session
    // for the whole study set and filtered per section. Best-effort: a fetch failure only costs
    // the extras — the built-in cards must never break on a public page.
    private var materials: List<StudyMaterialDto>? = null
    private var materialsRequested = false

    fun render(container: HTMLElement, section: StudySection? = null) {
        root = container
        this.section = section
        rerender()
    }

    private fun rerender() {
        root.clear()
        when (val current = section) {
            null -> renderOverview()
            else -> renderSection(current)
        }
    }

    /**
     * The `#study` overview — one card per section with "Start here" on The Text, using the same
     * markup as the site's section overview pages (layouts/_default/list.html) so the two look alike.
     */
    private fun renderOverview() {
        root.child("h1", "page-title", "Study & Practice")
        root.child(
            "p", "text-muted mb-4",
            "Everything you need to prepare for ${Session.season.eventScripture}, organized by what " +
                "you're studying. Every resource is free, and almost all of it downloads without an " +
                "account — only the verse decks ask you to sign in.",
        )
        root.child("div", "row row-cols-1 row-cols-md-2 row-cols-lg-3 g-4") {
            StudySection.entries.forEach { sec ->
                val start = sec == StudySection.TEXT
                child("div", "col") {
                    child("div", "card h-100 border-0 shadow-sm section-card${if (start) " start-here-card" else ""}") {
                        child("div", "card-body") {
                            if (start) {
                                child("span", "start-here-badge") {
                                    child("i", "bi bi-arrow-right-circle-fill")
                                    append(" Start here")
                                }
                            }
                            child("h5", "card-title") {
                                child("a", "text-decoration-none stretched-link text-dark", sec.title) {
                                    setAttribute("href", "#${sec.route}")
                                }
                            }
                            child("p", "card-text text-muted small", blurb(sec))
                        }
                    }
                }
            }
        }
    }

    /** One-sentence description of [section] — its overview card and the intro line atop its page. */
    private fun blurb(section: StudySection): String {
        val scripture = Session.season.eventScripture
        return when (section) {
            StudySection.TEXT ->
                "The complete text of $scripture — the highlighted study PDF, plus places to read or listen online."
            StudySection.VERSES ->
                "Every verse in $scripture as a flashcard — the verse up front, its reference on the " +
                    "back — ready to import into Space or Quizlet."
            StudySection.GENERAL ->
                "The official study guide and question flashcards, plus the interactive quiz and the " +
                    "community question bank."
            StudySection.HEADINGS ->
                "Every ESV section heading (Round 5 material) — a one-page list, flashcards, a browser, " +
                    "and self-check mode."
            StudySection.UNIQUE_WORDS ->
                "Words that appear only once in $scripture — flashcards and a printable index."
            StudySection.PRACTICE_TESTS ->
                "Printable practice tests for Rounds 1–5, built like the real thing."
            StudySection.REFERENCE ->
                "Printable indices — names, numbers, men, women, places, and a full concordance."
            StudySection.DATA ->
                "For coaches and question writers — reusable source files for building your own study material."
        }
    }

    /** A section's own page: title, intro, then that subject's download cards and interactive tools. */
    private fun renderSection(section: StudySection) {
        root.child("h1", "page-title", section.title)
        root.child("p", "text-muted mb-4", blurb(section))
        // Same breakpoints as the overview's row-cols-1/md-2/lg-3, spelled out per column so an
        // expanded card can widen itself to the full row (see [tile]).
        grid = root.child("div", "row g-4")
        when (section) {
            StudySection.TEXT -> textCards()
            StudySection.VERSES -> versesCards()
            StudySection.GENERAL -> generalKnowledgeCards()
            StudySection.HEADINGS -> headingsCards()
            StudySection.UNIQUE_WORDS -> uniqueWordsCards()
            StudySection.PRACTICE_TESTS -> practiceTestCards()
            StudySection.REFERENCE -> referenceCards()
            StudySection.DATA -> dataCards()
        }
        extraMaterialCards(section)
    }

    /** The admin-added documents and links assigned to [section], after the built-in cards. */
    private fun extraMaterialCards(section: StudySection) {
        val loaded = materials
        if (loaded == null) {
            if (!materialsRequested) {
                materialsRequested = true
                Shell.scope.launch {
                    // Failure stays silent (and un-retried until the next full page load).
                    runCatching { Session.api.studyMaterials(set = Session.studySet.simpleName) }
                        .onSuccess { materials = it; if (this@DownloadsScreen.section != null) rerender() }
                }
            }
            return
        }
        loaded.filter { it.section == section }.forEach { m ->
            when (m.type) {
                StudyMaterialType.DOCUMENT -> downloadCard(
                    title = m.title,
                    subtitle = m.description,
                    href = Session.api.baseUrl + m.filePath(),
                    buttonLabel = "Download",
                )
                StudyMaterialType.LINK -> linkCard(
                    title = m.title,
                    subtitle = m.description,
                    links = listOf("Open" to (m.url ?: return@forEach)),
                    newTab = true,
                )
            }
        }
    }

    private fun textCards() {
        val season = Session.season
        downloadCard(
            title = "Printable study text",
            subtitle = "Fully customizable text of ${season.eventScripture} with support for highlighting by category, underlining one-time words, and more! " +
                customizedNote(textChoices != StudyTextChoices()),
            href = studyTextUrl(),
            customize = Customize.StudyText,
        )
        // The text is the hub, not just a download: read/listen sit inline on it. The ESV text and
        // audio belong to their publishers (license is server-side only), so these link out to
        // Crossway's ESV.org reader and BibleGateway's licensed ESV audio rather than serving either.
        linkCard(
            title = "Read or listen online",
            subtitle = "Read ${season.eventScripture} in your browser or play the audio narration — " +
                "opens the publisher's site in a new tab.",
            links = listOf(
                "esv.org" to "https://www.esv.org/${encodeURIComponent(season.bookCode)}/",
                "blueletterbible.org" to "https://www.blueletterbible.org/esv/${encodeURIComponent(season.bookCode)}",
                "biblegateway.com" to "https://www.biblegateway.com/passage/?version=ESV&search=${encodeURIComponent(season.bookCode)}",
            ),
            newTab = true,
        )
    }

    private fun generalKnowledgeCards() {
        val season = Session.season
        downloadCard(
            title = "Study guide",
            subtitle = "The official multiple-choice study guide for ${season.eventScripture} — every chapter's " +
                "review questions with an answer key. The single best place to start.",
            href = generateUrl("/generate/study-guide.pdf"),
        )
        downloadCard(
            title = "Study guide — answer copy",
            subtitle = "The same guide with each correct answer marked (★) and no separate key — handy for " +
                "coaches, parents, and self-checking.",
            href = generateUrl("/generate/study-guide-answers.pdf"),
        )
        downloadCard(
            title = "Question flashcards",
            subtitle = "Duplex deck built from the approved community questions. The 400 top-voted " +
                "questions in scope, which is 40 sheets to print and cut — scope it to a chapter for " +
                "the rest." + scopeNote(flashcardScope) + customizedNote(flashcardRound != null),
            href = generateUrl(
                "/generate/flashcards.pdf",
                *scopedParams(flashcardScope),
                "round" to flashcardRound?.name,
            ),
            customize = Customize.QuestionFlashcards,
        )
        linkCard(
            title = "Quiz me",
            subtitle = "Drill the community question bank online with instant feedback — the interactive " +
                "twin of the flashcards and practice tests.",
            links = listOf("Open Quiz Me" to "#quiz"),
        )
        linkCard(
            title = "Community questions",
            subtitle = "Browse the community question bank, vote for the best questions, and submit your own.",
            links = listOf("Browse questions" to "#questions"),
        )
    }

    private fun headingsCards() {
        downloadCard(
            title = "Chapter headings list",
            subtitle = "Every ESV section heading in ${Session.season.eventScripture} on a single page, in " +
                "order with the verses it covers — print it once and keep it in front of you.",
            href = generateUrl("/generate/chapter-headings.pdf"),
        )
        downloadCard(
            title = "Chapter-heading flashcards",
            subtitle = "One card per ESV section heading (Round 5 material)." +
                // A cumulative endpoint reads "Through Acts 5."; an explicit span "Scoped to Acts 3-7."
                when {
                    headingScope.isRange -> scopeNote(headingScope)
                    else -> headingScope.label()?.let { " Through $it." } ?: ""
                },
            href = generateUrl(
                "/generate/heading-flashcards.pdf",
                *scopedParams(headingScope, chapterKey = StudyScopeParams.THROUGH_CHAPTER),
            ),
            customize = Customize.HeadingFlashcards,
        )
        linkCard(
            title = "Headings browser & self-check",
            subtitle = "Browse every ESV section heading online, or flip to self-check mode — the " +
                "interactive twin of the flashcards. Quiz Me can also drill headings.",
            links = listOf("Open browser" to "#study/headings", "Quiz headings" to "#quiz"),
        )
    }

    /**
     * The verse decks. Both need a signed-in user: a card per verse is the whole ESV text of the set in
     * one plain-text file, so it isn't offered anonymously the way the word-list decks are.
     */
    private fun versesCards() {
        val season = Session.season
        downloadCard(
            title = "Verse flashcards for the Space app",
            subtitle = "Every verse in ${season.eventScripture} as a CSV that imports straight into " +
                "Space (getspace.app) — the verse up front, its section heading and reference on the back." +
                verseScopeNote(),
            href = verseDeckUrl("/generate/space-verses.csv"),
            buttonLabel = "Download",
            requiresAuth = true,
            customize = Customize.VerseDeck(forSpace = true),
        )
        downloadCard(
            title = "Verse flashcards for Quizlet",
            subtitle = "The same deck as paste-ready text for Quizlet's import screen — choose Tab " +
                "between term and definition, and enter \\n\\n as the custom separator between cards." +
                verseScopeNote(),
            href = verseDeckUrl("/generate/quizlet-verses.txt"),
            buttonLabel = "Download",
            requiresAuth = true,
            customize = Customize.VerseDeck(forSpace = false),
        )
    }

    private fun uniqueWordsCards() {
        val season = Session.season
        downloadCard(
            title = "Unique-word flashcards",
            subtitle = "One card per word that appears only once in ${season.eventScripture} — the word up " +
                "front, its verse on the back. A powerful memory hook for pinpointing chapters.",
            href = generateUrl("/generate/unique-word-flashcards.pdf"),
        )
        downloadCard(
            title = "Unique words index",
            subtitle = "Every word that appears only once in ${season.eventScripture} — alphabetical and " +
                "in order of appearance.",
            href = generateUrl("/generate/unique-words-index.pdf"),
        )
        downloadCard(
            title = "Flashcards for the Space app",
            subtitle = "The flashcard deck as a CSV that imports straight into Space (getspace.app) — " +
                "each unique word up front, its verse with the word bolded on the back.",
            href = generateUrl("/generate/unique-word-flashcards.csv"),
            buttonLabel = "Download",
        )
        downloadCard(
            title = "Flashcards for Quizlet",
            subtitle = "The same deck as paste-ready text for Quizlet's import screen — choose Tab " +
                "between term and definition, and enter \\n\\n as the custom separator between cards.",
            href = generateUrl("/generate/unique-word-flashcards.txt"),
            buttonLabel = "Download",
        )
    }

    private fun practiceTestCards() {
        // R1–R5 only: the Power Round has no generator or question bank behind it.
        Round.entries.filter { it.number in 1..5 }.forEach { round ->
            val roundCustomized =
                if (round.crowdSourced) practiceLimit != null else practiceSeed.toIntOrNull() != null
            downloadCard(
                title = "Round ${round.number}: ${round.displayName}",
                subtitle = (if (round.crowdSourced) "Built from the approved community questions."
                else "Generated from the ESV text.") + scopeNote(practiceScope) + customizedNote(roundCustomized),
                href = practiceTestUrl(round),
                customize = Customize.PracticeTest(round),
            )
        }
    }

    private fun referenceCards() {
        val season = Session.season
        downloadCard(
            title = "Names index",
            subtitle = "Every proper name in ${season.eventScripture} with its verses — alphabetical and by frequency.",
            href = generateUrl("/generate/names-index.pdf"),
        )
        downloadCard(
            title = "Numbers index",
            subtitle = "Every number in ${season.eventScripture} with its verses — alphabetical and by frequency.",
            href = generateUrl("/generate/numbers-index.pdf"),
        )
        downloadCard(
            title = "Men index",
            subtitle = "Every man named in ${season.eventScripture} with the verses he appears in.",
            href = generateUrl("/generate/men-index.pdf"),
        )
        downloadCard(
            title = "Women index",
            subtitle = "Every woman named in ${season.eventScripture} with the verses she appears in.",
            href = generateUrl("/generate/women-index.pdf"),
        )
        downloadCard(
            title = "Places index",
            subtitle = "Every place named in ${season.eventScripture} with the verses it appears in.",
            href = generateUrl("/generate/places-index.pdf"),
        )
        downloadCard(
            title = "Full word index",
            subtitle = "A complete concordance — every significant word in ${season.eventScripture} with its verses.",
            href = generateUrl("/generate/full-index.pdf"),
        )
        linkCard(
            title = "Names & numbers browser",
            subtitle = "Search and filter the names and numbers indices online — the interactive twin of the PDFs.",
            links = listOf("Open browser" to "#study/indices"),
        )
    }

    // The creators' commons — a different audience: builders, not studiers (docs/study-materials-organization.md).
    private fun dataCards() {
        val exportCustomized = exportHeadings || exportRound != null
        downloadCard(
            title = "Kahoot spreadsheet",
            subtitle = "Multiple-choice questions as a Kahoot-importable .xlsx (their template layout). " +
                "One kahoot holds 100 questions, so that's the most a sheet carries — narrow the scope " +
                "to choose which 100." + scopeNote(exportScope) + customizedNote(exportCustomized),
            href = exportUrl(ExportApp.KAHOOT),
            customize = Customize.Export(ExportApp.KAHOOT),
            buttonLabel = "Download",
        )
        downloadCard(
            title = "Questions for the Space app",
            subtitle = "Question-and-answer pairs as a CSV that imports straight into Space " +
                "(getspace.app)." + scopeNote(exportScope) + customizedNote(exportCustomized),
            href = exportUrl(ExportApp.SPACE),
            customize = Customize.Export(ExportApp.SPACE),
            buttonLabel = "Download",
        )
        downloadCard(
            title = "Questions for Quizlet",
            subtitle = "The same pairs as paste-ready text for Quizlet's import screen — its default " +
                "settings read them as-is." + scopeNote(exportScope) + customizedNote(exportCustomized),
            href = exportUrl(ExportApp.QUIZLET),
            customize = Customize.Export(ExportApp.QUIZLET),
            buttonLabel = "Download",
        )
        downloadCard(
            title = "Study guide (CSV)",
            subtitle = "The full study-guide question bank as comma-separated text — every question, its " +
                "choices, answer, and reference — for building your own materials.",
            href = generateUrl("/generate/study-guide.csv"),
            buttonLabel = "Download",
        )
    }

    // --- download URLs (query params match TbbApi's byte methods exactly) ---

    private fun generateUrl(path: String, vararg params: Pair<String, Any?>): String {
        val query = params.mapNotNull { (k, v) -> v?.let { "$k=$it" } }.joinToString("&")
        return Session.api.baseUrl + path + (if (query.isEmpty()) "" else "?$query")
    }

    /** [selection]'s canonical scope params (durable across seasons; see scopeQueryParams). */
    private fun scopedParams(
        selection: ScopeSelection,
        chapterKey: String = StudyScopeParams.CHAPTER,
    ): Array<Pair<String, Any?>> =
        scopeQueryParams(Session.studySet, selection, chapterKey).map { (k, v) -> k to (v as Any?) }.toTypedArray()

    private fun studyTextUrl(): String {
        val c = textChoices
        return generateUrl(
            "/generate/bible-text.pdf",
            "fontSize" to c.fontSize.takeIf { it != 11 },
            "twoColumns" to true.takeIf { c.twoColumns },
            "justified" to true.takeIf { c.justified },
            "chapterBreaksPage" to true.takeIf { c.chapterBreaksPage },
            "useHeadingsForChapters" to true.takeIf { c.chapterStyle.headings },
            "chapterEndLines" to true.takeIf { c.chapterStyle.lines },
            "verseOnNewLine" to true.takeIf { c.verseOnNewLine },
            "highlight" to false.takeIf { !c.highlight },
            "underlineUniqueWords" to true.takeIf { c.underlineUniqueWords },
            "chapterHeadingSize" to c.chapterHeading.slug.takeIf { c.chapterHeading != HeadingSize.DEFAULT_CHAPTER },
            "sectionHeadingSize" to c.sectionHeading.slug.takeIf { c.sectionHeading != HeadingSize.DEFAULT_SECTION },
        )
    }

    private fun practiceTestUrl(round: Round): String = generateUrl(
        "/generate/practice-test.pdf",
        "round" to round.name,
        *scopedParams(practiceScope),
        "limit" to practiceLimit.takeIf { round.crowdSourced },
        "seed" to practiceSeed.toIntOrNull().takeIf { !round.crowdSourced },
    )

    /**
     * A verse deck at [path]. The picker names both ends, so the endpoint is spelled exactly — a lone
     * "through chapter 5" is what the user gets by leaving the From row on "Start".
     */
    private fun verseDeckUrl(path: String): String =
        generateUrl(path, *scopedParams(verseScope, chapterKey = StudyScopeParams.THROUGH_CHAPTER))

    /** "Scoped to Acts 3-7." — the cards echo whatever span the two rows add up to. */
    private fun verseScopeNote(): String = verseScope.label()?.let { " Scoped to $it." } ?: ""

    private fun exportUrl(app: ExportApp): String = generateUrl(
        when (app) {
            ExportApp.KAHOOT -> "/generate/kahoot-questions.xlsx"
            ExportApp.SPACE -> "/generate/space-questions.csv"
            ExportApp.QUIZLET -> "/generate/quizlet-questions.txt"
        },
        "source" to "headings".takeIf { exportHeadings },
        "round" to exportRound?.name.takeIf { !exportHeadings },
        *scopedParams(exportScope),
    )

    // --- rendering ---

    /**
     * One tile in the section grid, with the overview cards' treatment so the two grids match.
     * The card body is a column with the actions pushed to the bottom (`mt-auto`), so buttons line
     * up across a row of `h-100` tiles no matter how long each subtitle runs. A card whose
     * customize panel is open takes the whole row instead: the option chips (up to 28 chapters)
     * need the width, and left in a third-width column they'd stretch their neighbours' tiles.
     */
    private fun tile(expanded: Boolean = false, body: HTMLElement.() -> Unit) {
        grid.child("div", if (expanded) "col-12" else "col-12 col-md-6 col-lg-4") {
            child("div", "card h-100 border-0 shadow-sm section-card") {
                child("div", "card-body d-flex flex-column", init = body)
            }
        }
    }

    /**
     * A card whose actions are links rather than downloads: in-app tools (hash routes) or, with
     * [newTab], external sites (e.g. read/listen on a publisher's site).
     */
    private fun linkCard(title: String, subtitle: String, links: List<Pair<String, String>>, newTab: Boolean = false) {
        tile {
            child("h5", "card-title", title)
            child("p", "card-text text-muted small", subtitle)
            child("div", "d-flex align-items-center gap-2 flex-wrap mt-auto") {
                links.forEach { (label, url) ->
                    child("a", "btn btn-outline-primary btn-sm", label) {
                        setAttribute("href", url)
                        if (newTab) {
                            setAttribute("target", "_blank")
                            setAttribute("rel", "noopener")
                        }
                    }
                }
            }
        }
    }

    /**
     * The season's primary book for the external reader/audio links. [eventScripture] is a display string
     * ("Acts", or "Joshua, Judges & Ruth" for a multi-book season); take the first book so both links always
     * resolve — a multi-book season opens at book one, and the reader/audio pages navigate on from there.
     */
    private fun primaryBook(eventScripture: String): String =
        eventScripture.split(',', '&').first().replace(" and ", " ").trim()

    private fun downloadCard(
        title: String,
        subtitle: String,
        href: String,
        customize: Customize? = null,
        buttonLabel: String = "Download PDF",
        /**
         * A signed-in-only download: it can't ride a plain `<a href>` (our JWT isn't a cookie), so it
         * goes through [downloadWithAuth]. The card itself stays public — anonymous visitors see what
         * the deck is, and its button offers the sign-in it needs.
         */
        requiresAuth: Boolean = false,
    ) {
        val open = customize != null && DownloadsScreen.customize == customize
        tile(expanded = open) {
            child("h5", "card-title", title)
            child("p", "card-text text-muted small", subtitle)
            child("div", "d-flex align-items-center gap-2 mt-auto") {
                when {
                    !requiresAuth -> child("a", "btn btn-primary btn-sm", buttonLabel) {
                        setAttribute("href", href)
                        setAttribute("target", "_blank")
                        setAttribute("rel", "noopener")
                    }
                    // The card stays public — anonymous visitors see what the deck is, and the button
                    // says what it needs and goes there, rather than 401ing or sitting inert.
                    Session.user == null -> child("button", "btn btn-primary btn-sm", "Sign in to download") {
                        setAttribute("type", "button")
                        onClick { Shell.navigate(Routes.SIGN_IN) }
                    }
                    else -> child("button", "btn btn-primary btn-sm", buttonLabel) {
                        setAttribute("type", "button")
                        onClick {
                            downloadWithAuth(
                                href,
                                what = title.replaceFirstChar { it.lowercase() },
                                fallbackFileName = href.substringAfterLast('/').substringBefore('?'),
                            )
                        }
                    }
                }
                if (customize != null) {
                    child("button", "btn btn-link btn-sm", if (open) "Hide options" else "Customize") {
                        setAttribute("type", "button")
                        onClick {
                            DownloadsScreen.customize = if (open) null else customize
                            rerender()
                        }
                    }
                }
                // The Typst source behind this exact PDF — same options, same URL, ?format=typ. Sits
                // last and muted: it's an authoring tool, not something a student needs to step past.
                typstSourceUrl(href)?.takeIf { showTypstSource }?.let { sourceHref ->
                    child("button", "btn btn-link btn-sm text-muted ms-auto p-0", ".typ") {
                        setAttribute("type", "button")
                        setAttribute("title", "Download the Typst source this PDF is compiled from")
                        onClick {
                            downloadWithAuth(
                                sourceHref,
                                what = "the Typst source",
                                fallbackFileName = sourceHref.substringAfterLast('/').substringBefore('?')
                                    .removeSuffix(".pdf") + ".typ",
                            )
                        }
                    }
                }
            }
            if (open) {
                child("div", "border-top pt-3 mt-3") { renderOptions(customize!!) }
            }
        }
    }

    private fun Element.renderOptions(target: Customize) {
        when (target) {
            Customize.StudyText -> {
                child("p", "fw-semibold mb-1", "Font size")
                chipRow(listOf(9, 10, 11, 12, 13, 14, 15).map { "$it pt" to it }, textChoices.fontSize) {
                    textChoices = textChoices.copy(fontSize = it); rerender()
                }
                optionSwitch("Underline words that appear only once", textChoices.underlineUniqueWords) {
                    textChoices = textChoices.copy(underlineUniqueWords = it); rerender()
                }
                optionSwitch("Highlight names & numbers by category", textChoices.highlight) {
                    textChoices = textChoices.copy(highlight = it); rerender()
                }
                child("p", "fw-semibold mb-1", "Chapter titles")
                chipRow(ChapterStyle.entries.map { it.label to it }, textChoices.chapterStyle) {
                    textChoices = textChoices.copy(chapterStyle = it); rerender()
                }
                // Only meaningful when chapters actually render as headings; inline chapter labels
                // take the body size, so the chips would be a control that does nothing.
                if (textChoices.chapterStyle.headings) {
                    child("p", "fw-semibold mb-1", "Chapter heading size")
                    chipRow(HeadingSize.entries.map { it.label to it }, textChoices.chapterHeading) {
                        textChoices = textChoices.copy(chapterHeading = it); rerender()
                    }
                }
                child("p", "fw-semibold mb-1", "Section heading size")
                chipRow(HeadingSize.entries.map { it.label to it }, textChoices.sectionHeading) {
                    textChoices = textChoices.copy(sectionHeading = it); rerender()
                }
                optionSwitch("Two columns", textChoices.twoColumns) {
                    textChoices = textChoices.copy(twoColumns = it); rerender()
                }
                optionSwitch("Justified text", textChoices.justified) {
                    textChoices = textChoices.copy(justified = it); rerender()
                }
                optionSwitch("Each verse starts on a new line", textChoices.verseOnNewLine) {
                    textChoices = textChoices.copy(verseOnNewLine = it); rerender()
                }
                /* This is not a legal option, so don't encourage it
                optionSwitch("Each chapter starts a new page", textChoices.chapterBreaksPage) {
                    textChoices = textChoices.copy(chapterBreaksPage = it); rerender()
                }*/
            }
            Customize.QuestionFlashcards -> {
                chapterScope(flashcardScope, range = true) { flashcardScope = it }
                child("p", "fw-semibold mb-1", "Round")
                chipRow(roundOptions(), flashcardRound) { flashcardRound = it; rerender() }
            }
            Customize.HeadingFlashcards ->
                chapterScope(headingScope, "Chapters", cumulative = true, range = true) { headingScope = it }
            is Customize.VerseDeck ->
                // Cumulative because that's what the deck endpoint generates for a lone endpoint
                // ("through chapter N") — the chips must light the verses actually in the file.
                chapterScope(verseScope, "Chapters", cumulative = true, range = true) { verseScope = it }
            is Customize.PracticeTest -> {
                // Text-generated rounds scope cumulatively server-side (`chapter` means "through
                // chapter"), so their chips reach back; bank rounds scope to exactly what's picked.
                chapterScope(practiceScope, cumulative = target.round.textGenerated, range = true) {
                    practiceScope = it
                }
                if (target.round.crowdSourced) {
                    child("p", "fw-semibold mb-1", "Number of questions")
                    chipRow(
                        listOf<Pair<String, Int?>>("Default (40)" to null) + listOf(10, 20, 60, 100).map { "$it" to it },
                        practiceLimit,
                    ) { practiceLimit = it; rerender() }
                } else {
                    child("label", "form-label fw-semibold", "Seed (same seed → same test again)")
                    val input = child("input", "form-control") as HTMLInputElement
                    input.type = "text"
                    input.setAttribute("inputmode", "numeric")
                    input.value = practiceSeed
                    input.addEventListener("input", {
                        val cleaned = input.value.filter(Char::isDigit).take(4)
                        if (cleaned != input.value) input.value = cleaned
                        practiceSeed = cleaned
                        // No rerender: the seed only affects the link href, updated on blur/change below.
                    })
                    input.addEventListener("change", { rerender() })
                    child(
                        "p", "form-text",
                        "Leave blank for a fresh random test. The seed prints on the test so a group can regenerate it.",
                    )
                }
            }
            is Customize.Export -> {
                // The headings source scopes cumulatively (like the heading flashcards); the bank exactly.
                chapterScope(exportScope, cumulative = exportHeadings, range = true) { exportScope = it }
                child("p", "fw-semibold mb-1", "Source")
                chipRow(
                    listOf("Question bank" to false, "Chapter headings" to true),
                    exportHeadings,
                ) { exportHeadings = it; rerender() }
                if (!exportHeadings) {
                    child("p", "fw-semibold mb-1", "Round")
                    chipRow(roundOptions(), exportRound) { exportRound = it; rerender() }
                }
                if (target.app == ExportApp.KAHOOT) {
                    child("p", "form-text", "Kahoot needs multiple-choice material; open-answer questions are left out.")
                }
            }
        }
    }

    /** Chapter chips for one card group's downloads; the cards' subtitles echo the choice. */
    private fun Element.chapterScope(
        selected: ScopeSelection,
        label: String = "Chapter scope",
        cumulative: Boolean = false,
        range: Boolean = false,
        onSelect: (ScopeSelection) -> Unit,
    ) {
        child("p", "fw-semibold mb-1", label)
        chapterChips(selected, cumulative, range) { onSelect(it); rerender() }
    }

    /** Human label for a selection: "Acts 2", "Acts 3-7" for a span, just the book for a whole-book slice. */
    private fun scopeLabel(selection: ScopeSelection): String? = selection.label()

    private fun roundOptions(): List<Pair<String, Round?>> =
        listOf<Pair<String, Round?>>("All" to null) + Round.crowdSourcedRounds.map { it.displayName to it }

    private fun scopeNote(selection: ScopeSelection): String =
        scopeLabel(selection)?.let { " Scoped to $it." } ?: ""

    private fun customizedNote(customized: Boolean): String =
        if (customized) " Using your customized settings." else ""
}
