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

/** Which card's customize panel is expanded. */
private sealed interface Customize {
    data object StudyText : Customize
    data object QuestionFlashcards : Customize
    data class PracticeTest(val round: Round) : Customize
    data class Export(val kahoot: Boolean) : Customize
}

/** Study-text options, hoisted so choices stick for the whole visit (§7.6 "remember everything cheap"). */
private data class StudyTextChoices(
    val fontSize: Int = 11,
    val twoColumns: Boolean = false,
    val justified: Boolean = false,
    val chapterBreaksPage: Boolean = false,
    val highlight: Boolean = true,
    val underlineUniqueWords: Boolean = false,
)

/**
 * Download center (docs/gui-redesign.md §5B): one scrolling page of preset cards in five groups —
 * each card is one click to a sensible default, with options behind a "Customize" panel. Public.
 *
 * Every download is a plain link to the backend (the generate endpoints are public and send
 * Content-Disposition: attachment), opened in a new tab so a generation error shows its message
 * there instead of navigating the app away; on success the tab closes into a normal download.
 */
object DownloadsScreen {

    // Sticky for the whole page session, deliberately outliving route changes.
    private var chapter: Int? = null
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
        root.child("p", "fw-semibold mb-1", "Chapter scope (flashcards, practice tests & exports)")
        root.chapterChips(chapter) { chapter = it; rerender() }

        groupHeader("Study text")
        downloadCard(
            title = "Highlighted study text",
            subtitle = "The full text of ${season.eventScripture} with names, numbers, and more " +
                "highlighted by category — the flagship study document." +
                customizedNote(textChoices != StudyTextChoices()),
            href = studyTextUrl(),
            customize = Customize.StudyText,
        )

        groupHeader("Flashcards")
        downloadCard(
            title = "Question flashcards",
            subtitle = "Duplex deck built from the approved community questions." + scopeNote() +
                customizedNote(flashcardRound != null),
            href = generateUrl(
                "/generate/flashcards.pdf",
                "chapter" to chapter,
                "round" to flashcardRound?.name,
            ),
            customize = Customize.QuestionFlashcards,
        )
        downloadCard(
            title = "Chapter-heading flashcards",
            subtitle = "One card per ESV section heading (Round 5 material)." +
                (chapter?.let { " Through chapter $it." } ?: ""),
            href = generateUrl("/generate/heading-flashcards.pdf", "throughChapter" to chapter),
        )

        groupHeader("Indices")
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

        groupHeader("Practice tests")
        // R1–R5 only: the Power Round has no generator or question bank behind it.
        Round.entries.filter { it.number in 1..5 }.forEach { round ->
            val roundCustomized =
                if (round.crowdSourced) practiceLimit != null else practiceSeed.toIntOrNull() != null
            downloadCard(
                title = "Round ${round.number}: ${round.displayName}",
                subtitle = (if (round.crowdSourced) "Built from the approved community questions."
                else "Generated from the ESV text.") + scopeNote() + customizedNote(roundCustomized),
                href = practiceTestUrl(round),
                customize = Customize.PracticeTest(round),
            )
        }

        groupHeader("Exports")
        val exportCustomized = exportHeadings || exportRound != null
        downloadCard(
            title = "Kahoot spreadsheet",
            subtitle = "Multiple-choice questions as a Kahoot-importable .xlsx (their template layout)." +
                scopeNote() + customizedNote(exportCustomized),
            href = exportUrl(kahoot = true),
            customize = Customize.Export(kahoot = true),
            buttonLabel = "Download",
        )
        downloadCard(
            title = "Quizlet / Space TSV",
            subtitle = "Question-and-answer pairs as tab-separated text, import-ready for " +
                "Quizlet, Space, or Anki." + scopeNote() + customizedNote(exportCustomized),
            href = exportUrl(kahoot = false),
            customize = Customize.Export(kahoot = false),
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
            "highlight" to false.takeIf { !c.highlight },
            "underlineUniqueWords" to true.takeIf { c.underlineUniqueWords },
        )
    }

    private fun practiceTestUrl(round: Round): String = generateUrl(
        "/generate/practice-test.pdf",
        "round" to round.name,
        "chapter" to chapter,
        "limit" to practiceLimit.takeIf { round.crowdSourced },
        "seed" to practiceSeed.toIntOrNull().takeIf { !round.crowdSourced },
    )

    private fun exportUrl(kahoot: Boolean): String = generateUrl(
        if (kahoot) "/generate/questions.xlsx" else "/generate/questions.tsv",
        "source" to "headings".takeIf { exportHeadings },
        "round" to exportRound?.name.takeIf { !exportHeadings },
        "chapter" to chapter,
    )

    // --- rendering ---

    private fun groupHeader(title: String) {
        root.child("h2", "h5 fw-bold mt-4", title)
    }

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
                optionSwitch("Highlight names & numbers by category", textChoices.highlight) {
                    textChoices = textChoices.copy(highlight = it); rerender()
                }
                optionSwitch("Underline words that appear only once", textChoices.underlineUniqueWords) {
                    textChoices = textChoices.copy(underlineUniqueWords = it); rerender()
                }
            }
            Customize.QuestionFlashcards -> {
                child("p", "fw-semibold mb-1", "Round")
                chipRow(roundOptions(), flashcardRound) { flashcardRound = it; rerender() }
            }
            is Customize.PracticeTest -> {
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

    private fun roundOptions(): List<Pair<String, Round?>> =
        listOf<Pair<String, Round?>>("All" to null) + Round.crowdSourcedRounds.map { it.displayName to it }

    private fun scopeNote(): String = chapter?.let { " Scoped to chapter $it." } ?: ""

    private fun customizedNote(customized: Boolean): String =
        if (customized) " Using your customized settings." else ""
}
