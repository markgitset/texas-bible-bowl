package net.markdrew.biblebowl.web.screens

import net.markdrew.biblebowl.model.Round
import net.markdrew.biblebowl.web.Session
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
    INLINE("Inline", headings = false, lines = false),
    HEADING("Heading", headings = true, lines = false),
    HEADING_LINES("Heading with lines", headings = true, lines = true),
}

/** Study-text options, hoisted so choices stick for the whole visit (§7.6 "remember everything cheap"). */
private data class StudyTextChoices(
    val fontSize: Int = 11,
    val twoColumns: Boolean = false,
    val justified: Boolean = false,
    val chapterBreaksPage: Boolean = false,
    val chapterStyle: ChapterStyle = ChapterStyle.INLINE,
    val verseOnNewLine: Boolean = false,
    val highlight: Boolean = true,
    val underlineUniqueWords: Boolean = false,
)

/**
 * Download center (docs/gui-redesign.md §5B): one scrolling page of preset cards, grouped by study
 * focus (The Text, General Knowledge, Chapter Headings, Unique Words, Practice Tests, Reference
 * Documents) with a separate creators' commons (Data & Source Files) below. Each card is one click to a sensible
 * default, with options behind a "Customize" panel. Public.
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

    fun render(container: HTMLElement) {
        root = container
        rerender()
    }

    private fun rerender() {
        root.clear()
        val season = Session.season

        root.child("h1", "page-title", "Downloads")

        // Studier resources are grouped by STUDY FOCUS (the subject you're drilling), not by format.
        // Cross-cutting tools (quiz, games, community questions) live on the study hub, not here.
        groupHeader("The Text")
        downloadCard(
            title = "Highlighted study text",
            subtitle = "The full text of ${season.eventScripture} with names, numbers, and more " +
                "highlighted by category — the flagship study document." +
                customizedNote(textChoices != StudyTextChoices()),
            href = studyTextUrl(),
            customize = Customize.StudyText,
        )
        // The text is the hub, not just a download: read/listen sit inline on it. The ESV text and
        // audio belong to their publishers (license is server-side only), so these link out to
        // Crossway's ESV.org reader and BibleGateway's licensed ESV audio rather than serving either.
        val book = primaryBook(season.eventScripture)
        externalCard(
            title = "Read or listen online",
            subtitle = "Read ${season.eventScripture} in your browser or play the audio narration — " +
                "opens the publisher's site in a new tab.",
            links = listOf(
                "Read on ESV.org" to "https://www.esv.org/${encodeURIComponent(book)}/",
                "Listen (audio)" to "https://www.biblegateway.com/audio/mclean/esv/${encodeURIComponent(book)}.1",
            ),
        )

        groupHeader("General Knowledge")
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

        groupHeader("Chapter Headings")
        downloadCard(
            title = "Chapter-heading flashcards",
            subtitle = "One card per ESV section heading (Round 5 material)." +
                (headingChapter?.let { " Through chapter $it." } ?: ""),
            href = generateUrl("/generate/heading-flashcards.pdf", "throughChapter" to headingChapter),
            customize = Customize.HeadingFlashcards,
        )

        groupHeader("Unique Words")
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

        groupHeader("Practice Tests")
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

        groupHeader("Reference Documents")
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

        // Separate "commons" for a different audience — builders, not studiers (docs/study-materials-organization.md).
        commonsHeader(
            "Data & Source Files",
            "For coaches and question writers — reusable source files for building your own study material.",
        )
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

    private fun groupHeader(title: String) {
        root.child("h2", "h5 fw-bold mt-4", title)
    }

    /** A study-focus group's twin for the creators' commons: a rule + intro sets it apart from the studier groups above. */
    private fun commonsHeader(title: String, intro: String) {
        root.child("hr", "mt-5")
        root.child("h2", "h5 fw-bold", title)
        root.child("p", "text-muted small", intro)
    }

    /** A card whose actions are external links (each opens in a new tab) — e.g. read/listen on a publisher's site. */
    private fun externalCard(title: String, subtitle: String, links: List<Pair<String, String>>) {
        root.child("div", "card section-card mb-3") {
            child("div", "card-body") {
                child("h5", "card-title", title)
                child("p", "card-text text-muted", subtitle)
                child("div", "d-flex align-items-center gap-2 flex-wrap") {
                    links.forEach { (label, url) ->
                        child("a", "btn btn-outline-primary btn-sm", label) {
                            setAttribute("href", url)
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
                chipRow(listOf(9, 10, 11, 12, 14).map { "$it pt" to it }, textChoices.fontSize) {
                    textChoices = textChoices.copy(fontSize = it); rerender()
                }
                optionSwitch("Two columns", textChoices.twoColumns) {
                    textChoices = textChoices.copy(twoColumns = it); rerender()
                }
                optionSwitch("Justified text", textChoices.justified) {
                    textChoices = textChoices.copy(justified = it); rerender()
                }
                optionSwitch("Each chapter starts a new page", textChoices.chapterBreaksPage) {
                    textChoices = textChoices.copy(chapterBreaksPage = it); rerender()
                }
                optionSwitch("Each verse starts a new line", textChoices.verseOnNewLine) {
                    textChoices = textChoices.copy(verseOnNewLine = it); rerender()
                }
                child("p", "fw-semibold mb-1", "Chapter titles")
                chipRow(ChapterStyle.entries.map { it.label to it }, textChoices.chapterStyle) {
                    textChoices = textChoices.copy(chapterStyle = it); rerender()
                }
                optionSwitch("Highlight names & numbers by category", textChoices.highlight) {
                    textChoices = textChoices.copy(highlight = it); rerender()
                }
                optionSwitch("Underline words that appear only once", textChoices.underlineUniqueWords) {
                    textChoices = textChoices.copy(underlineUniqueWords = it); rerender()
                }
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
