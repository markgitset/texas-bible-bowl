package net.markdrew.biblebowl.web.screens

import net.markdrew.biblebowl.model.Round
import net.markdrew.biblebowl.web.Session
import net.markdrew.biblebowl.web.StudySection
import net.markdrew.biblebowl.web.child
import net.markdrew.biblebowl.web.clear
import net.markdrew.biblebowl.web.onClick
import net.markdrew.biblebowl.web.ui.chapterChips
import net.markdrew.biblebowl.web.ui.chipRow
import net.markdrew.biblebowl.web.ui.optionSwitch
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement

/** JS global for URL-encoding a book name into the external reader/audio links (e.g. "1 Samuel" → "1%20Samuel"). */
private external fun encodeURIComponent(value: String): String

/** Which card's customize panel is expanded. */
private sealed interface Customize {
    data object StudyText : Customize
    data object QuestionFlashcards : Customize
    data object HeadingFlashcards : Customize
    data class PracticeTest(val round: Round) : Customize
    data class Export(val kahoot: Boolean) : Customize
}

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
)

/**
 * The Study & Practice pages: the overview (`#study`) is a card grid — one card per study-focus
 * section, "Start here" on The Text — matching the site's section overview pages (Event etc.);
 * each section (`#study/<slug>`, also a navbar dropdown item) is its own page holding that
 * subject's download cards (one click to a sensible default, options behind "Customize") AND
 * links to its interactive tools (quiz, browsers, community questions), so nothing about a
 * subject lives anywhere else. Public.
 *
 * Every download is a plain link to the backend (the generate endpoints are public and send
 * Content-Disposition: attachment), opened in a new tab so a generation error shows its message
 * there instead of navigating the app away; on success the tab closes into a normal download.
 */
object DownloadsScreen {

    // Sticky for the whole page session, deliberately outliving route changes. Chapter scope is
    // per card group: each customize panel's chips affect only the cards that panel belongs to.
    private var flashcardChapter: Int? = null
    private var headingChapter: Int? = null
    private var practiceChapter: Int? = null
    private var exportChapter: Int? = null
    private var customize: Customize? = null
    private var textChoices = StudyTextChoices()
    private var flashcardRound: Round? = null
    private var practiceLimit: Int? = null
    private var practiceSeed: String = ""
    private var exportHeadings = false
    private var exportRound: Round? = null

    private lateinit var root: HTMLElement
    private var section: StudySection? = null // null = the overview card grid

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
                "you're studying. Every resource is free — no sign-in needed.",
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
            StudySection.GENERAL ->
                "The official study guide and question flashcards, plus the interactive quiz and the " +
                    "community question bank."
            StudySection.HEADINGS ->
                "Every ESV section heading (Round 5 material) — flashcards, a browser, and self-check mode."
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
        root.child("p", "text-muted", blurb(section))
        when (section) {
            StudySection.TEXT -> textCards()
            StudySection.GENERAL -> generalKnowledgeCards()
            StudySection.HEADINGS -> headingsCards()
            StudySection.UNIQUE_WORDS -> uniqueWordsCards()
            StudySection.PRACTICE_TESTS -> practiceTestCards()
            StudySection.REFERENCE -> referenceCards()
            StudySection.DATA -> dataCards()
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
        val book = primaryBook(season.eventScripture)
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
            subtitle = "Duplex deck built from the approved community questions." + scopeNote(flashcardChapter) +
                customizedNote(flashcardRound != null),
            href = generateUrl(
                "/generate/flashcards.pdf",
                "chapter" to flashcardChapter,
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
            title = "Chapter-heading flashcards",
            subtitle = "One card per ESV section heading (Round 5 material)." +
                (headingChapter?.let { " Through chapter $it." } ?: ""),
            href = generateUrl("/generate/heading-flashcards.pdf", "throughChapter" to headingChapter),
            customize = Customize.HeadingFlashcards,
        )
        linkCard(
            title = "Headings browser & self-check",
            subtitle = "Browse every ESV section heading online, or flip to self-check mode — the " +
                "interactive twin of the flashcards. Quiz Me can also drill headings.",
            links = listOf("Open browser" to "#study/headings", "Quiz headings" to "#quiz"),
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
    }

    private fun practiceTestCards() {
        // R1–R5 only: the Power Round has no generator or question bank behind it.
        Round.entries.filter { it.number in 1..5 }.forEach { round ->
            val roundCustomized =
                if (round.crowdSourced) practiceLimit != null else practiceSeed.toIntOrNull() != null
            downloadCard(
                title = "Round ${round.number}: ${round.displayName}",
                subtitle = (if (round.crowdSourced) "Built from the approved community questions."
                else "Generated from the ESV text.") + scopeNote(practiceChapter) + customizedNote(roundCustomized),
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
            subtitle = "Multiple-choice questions as a Kahoot-importable .xlsx (their template layout)." +
                scopeNote(exportChapter) + customizedNote(exportCustomized),
            href = exportUrl(kahoot = true),
            customize = Customize.Export(kahoot = true),
            buttonLabel = "Download",
        )
        downloadCard(
            title = "Quizlet / Space TSV",
            subtitle = "Question-and-answer pairs as tab-separated text, import-ready for " +
                "Quizlet, Space, or Anki." + scopeNote(exportChapter) + customizedNote(exportCustomized),
            href = exportUrl(kahoot = false),
            customize = Customize.Export(kahoot = false),
            buttonLabel = "Download",
        )
        downloadCard(
            title = "Study guide (TSV)",
            subtitle = "The full study-guide question bank as tab-separated text — every question, its " +
                "choices, answer, and reference — for building your own materials.",
            href = generateUrl("/generate/study-guide.tsv"),
            buttonLabel = "Download",
        )
    }

    // --- download URLs (query params match TbbApi's byte methods exactly) ---

    private fun generateUrl(path: String, vararg params: Pair<String, Any?>): String {
        val query = params.mapNotNull { (k, v) -> v?.let { "$k=$it" } }.joinToString("&")
        return Session.api.baseUrl + path + (if (query.isEmpty()) "" else "?$query")
    }

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
        )
    }

    private fun practiceTestUrl(round: Round): String = generateUrl(
        "/generate/practice-test.pdf",
        "round" to round.name,
        "chapter" to practiceChapter,
        "limit" to practiceLimit.takeIf { round.crowdSourced },
        "seed" to practiceSeed.toIntOrNull().takeIf { !round.crowdSourced },
    )

    private fun exportUrl(kahoot: Boolean): String = generateUrl(
        if (kahoot) "/generate/questions.xlsx" else "/generate/questions.tsv",
        "source" to "headings".takeIf { exportHeadings },
        "round" to exportRound?.name.takeIf { !exportHeadings },
        "chapter" to exportChapter,
    )

    // --- rendering ---

    /**
     * A card whose actions are links rather than downloads: in-app tools (hash routes) or, with
     * [newTab], external sites (e.g. read/listen on a publisher's site).
     */
    private fun linkCard(title: String, subtitle: String, links: List<Pair<String, String>>, newTab: Boolean = false) {
        root.child("div", "card section-card mb-3") {
            child("div", "card-body") {
                child("h5", "card-title", title)
                child("p", "card-text text-muted", subtitle)
                child("div", "d-flex align-items-center gap-2 flex-wrap") {
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
    ) {
        root.child("div", "card section-card mb-3") {
            child("div", "card-body") {
                child("h5", "card-title", title)
                child("p", "card-text text-muted", subtitle)
                child("div", "d-flex align-items-center gap-2") {
                    child("a", "btn btn-primary btn-sm", buttonLabel) {
                        setAttribute("href", href)
                        setAttribute("target", "_blank")
                        setAttribute("rel", "noopener")
                    }
                    if (customize != null) {
                        val open = DownloadsScreen.customize == customize
                        child("button", "btn btn-link btn-sm", if (open) "Hide options" else "Customize") {
                            setAttribute("type", "button")
                            onClick {
                                DownloadsScreen.customize = if (open) null else customize
                                rerender()
                            }
                        }
                    }
                }
                if (customize != null && DownloadsScreen.customize == customize) {
                    child("div", "border-top pt-3 mt-3") { renderOptions(customize) }
                }
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
                chapterScope(flashcardChapter) { flashcardChapter = it }
                child("p", "fw-semibold mb-1", "Round")
                chipRow(roundOptions(), flashcardRound) { flashcardRound = it; rerender() }
            }
            Customize.HeadingFlashcards ->
                chapterScope(headingChapter, "Through chapter") { headingChapter = it }
            is Customize.PracticeTest -> {
                chapterScope(practiceChapter) { practiceChapter = it }
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
                chapterScope(exportChapter) { exportChapter = it }
                child("p", "fw-semibold mb-1", "Source")
                chipRow(
                    listOf("Question bank" to false, "Chapter headings" to true),
                    exportHeadings,
                ) { exportHeadings = it; rerender() }
                if (!exportHeadings) {
                    child("p", "fw-semibold mb-1", "Round")
                    chipRow(roundOptions(), exportRound) { exportRound = it; rerender() }
                }
                if (target.kahoot) {
                    child("p", "form-text", "Kahoot needs multiple-choice material; open-answer questions are left out.")
                }
            }
        }
    }

    /** Chapter chips for one card group's downloads; the cards' subtitles echo the choice. */
    private fun Element.chapterScope(selected: Int?, label: String = "Chapter scope", onSelect: (Int?) -> Unit) {
        child("p", "fw-semibold mb-1", label)
        chapterChips(selected) { onSelect(it); rerender() }
    }

    private fun roundOptions(): List<Pair<String, Round?>> =
        listOf<Pair<String, Round?>>("All" to null) + Round.crowdSourcedRounds.map { it.displayName to it }

    private fun scopeNote(chapter: Int?): String = chapter?.let { " Scoped to chapter $it." } ?: ""

    private fun customizedNote(customized: Boolean): String =
        if (customized) " Using your customized settings." else ""
}
