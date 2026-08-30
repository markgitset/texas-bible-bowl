package net.markdrew.biblebowl.app.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.markdrew.biblebowl.api.HeadingSize
import net.markdrew.biblebowl.api.PdfFileNames
import net.markdrew.biblebowl.api.schoolYear
import net.markdrew.biblebowl.api.ScopeSelection
import net.markdrew.biblebowl.api.StudyMaterialDto
import net.markdrew.biblebowl.api.StudyMaterialType
import net.markdrew.biblebowl.api.resolvedStudySet
import net.markdrew.biblebowl.model.Round
import net.markdrew.biblebowl.client.TbbApi
import net.markdrew.biblebowl.api.StudySection
import net.markdrew.biblebowl.app.platform.Mime
import net.markdrew.biblebowl.app.platform.saveFile
import net.markdrew.biblebowl.app.ui.ChapterChips
import net.markdrew.biblebowl.app.ui.LocalSeason

/** Which card's "Customize" sheet is open. */
private sealed interface Customize {
    data object StudyText : Customize
    data object QuestionFlashcards : Customize
    data object HeadingFlashcards : Customize
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
 * Sticky per-visit choices (§7.6 "remember everything cheap") — object-level snapshot state so
 * the selections survive moving between the section screens, like the web app's screen object.
 * Chapter scope is per card group: each sheet's chips affect only the cards of its own group.
 */
private object StudyChoices {
    var textChoices by mutableStateOf(StudyTextChoices())
    var flashcardScope by mutableStateOf(ScopeSelection())
    var flashcardRound by mutableStateOf<Round?>(null)
    var headingScope by mutableStateOf(ScopeSelection())
    var practiceScope by mutableStateOf(ScopeSelection())
    var practiceLimit by mutableStateOf<Int?>(null)
    var practiceSeed by mutableStateOf("")
    var exportScope by mutableStateOf(ScopeSelection())
    var exportHeadings by mutableStateOf(false)
    var exportRound by mutableStateOf<Round?>(null)
}

/** One-sentence description of [section] — its overview card and the intro line atop its screen. */
private fun blurb(section: StudySection, scripture: String): String = when (section) {
    StudySection.TEXT ->
        "The complete text of $scripture — the highlighted study PDF, plus places to read or listen online."
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

/**
 * The Study & Practice overview — the Study tab's screen and the app's default landing, mirroring
 * the web app's `#study`: one card per study-focus section ("Start here" on The Text), each
 * opening that section's own screen. Public.
 */
@Composable
fun StudyOverviewScreen(onOpenSection: (StudySection) -> Unit) {
    val season = LocalSeason.current
    Column(
        Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "This season: ${season.eventScripture}",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            "${season.schoolYear} · all ${season.chapterCount} chapters (ESV) — everything you need, " +
                "organized by what you're studying. Free, no sign-in needed.",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.secondary,
        )
        StudySection.entries.forEach { section ->
            SectionCard(section, onClick = { onOpenSection(section) })
        }
    }
}

/** An overview card for [section]: title + blurb, the whole card tappable, gold badge on The Text. */
@Composable
private fun SectionCard(section: StudySection, onClick: () -> Unit) {
    ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (section == StudySection.TEXT) {
                Surface(
                    color = MaterialTheme.colorScheme.secondary,
                    shape = RoundedCornerShape(50),
                ) {
                    Text(
                        "START HERE",
                        Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondary,
                    )
                }
            }
            Text(section.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                blurb(section, LocalSeason.current.eventScripture),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A study-focus section's screen: that subject's download cards (one click to a sensible default,
 * options behind a "Customize" sheet) AND its interactive tools (quiz, browsers, community
 * questions), so nothing about a subject lives anywhere else. Public.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudySectionScreen(
    api: TbbApi,
    section: StudySection,
    onOpenIndices: () -> Unit,
    onOpenHeadings: () -> Unit,
    onOpenQuiz: () -> Unit,
    onOpenQuestions: () -> Unit,
) {
    var busyCard by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    var customize by remember { mutableStateOf<Customize?>(null) }
    // Sheet choices delegate to [StudyChoices] so they stick across section/route changes.
    var textChoices by StudyChoices::textChoices
    var flashcardScope by StudyChoices::flashcardScope
    var flashcardRound by StudyChoices::flashcardRound
    var headingScope by StudyChoices::headingScope
    var practiceScope by StudyChoices::practiceScope
    var practiceLimit by StudyChoices::practiceLimit
    var practiceSeed by StudyChoices::practiceSeed
    var exportScope by StudyChoices::exportScope
    var exportHeadings by StudyChoices::exportHeadings
    var exportRound by StudyChoices::exportRound
    val scope = rememberCoroutineScope()

    fun download(cardTitle: String, fileName: String, mime: String = Mime.PDF, fetch: suspend () -> ByteArray) {
        if (busyCard != null) return
        customize = null
        busyCard = cardTitle; message = null
        scope.launch {
            try {
                message = saveFile(fileName, fetch(), mime)
                isError = false
            } catch (e: Throwable) {
                message = "Download failed: ${e.message}"
                isError = true
            } finally {
                busyCard = null
            }
        }
    }

    val studySet = LocalSeason.current.resolvedStudySet

    // File names mirror the server's set-prefixed, book-qualified names (single-book sets keep -chN).
    fun chSuffix(sel: ScopeSelection, cumulative: Boolean = false): String {
        val ref = sel.chapterRef ?: return ""
        val core = if (studySet.isSingleBook) "ch${ref.chapter}" else ref.serialize().lowercase()
        return (if (cumulative) "-through-" else "-") + core
    }

    fun withSet(name: String) = PdfFileNames.withSet(studySet.simpleName, name)

    // One download action per card, honoring the (sticky) customize choices. Used by BOTH the
    // card's primary button and the sheet's Download button — customizing and then pressing
    // either button yields the customized document, and the file name spells out the options.
    fun downloadStudyText() {
        val c = textChoices
        // Shared with the server, which uses the same name as its cache key and attachment name.
        val name = PdfFileNames.bibleText(
            highlight = c.highlight,
            twoColumns = c.twoColumns,
            justified = c.justified,
            chapterBreaksPage = c.chapterBreaksPage,
            useHeadingsForChapters = c.chapterStyle.headings,
            chapterEndLines = c.chapterStyle.lines,
            verseOnNewLine = c.verseOnNewLine,
            underlineUniqueWords = c.underlineUniqueWords,
            fontSize = c.fontSize,
            chapterHeading = c.chapterHeading,
            sectionHeading = c.sectionHeading,
        )
        download("Printable study text", withSet(name)) {
            api.bibleTextPdf(
                fontSize = c.fontSize.takeIf { it != 11 },
                twoColumns = c.twoColumns,
                justified = c.justified,
                chapterBreaksPage = c.chapterBreaksPage,
                useHeadingsForChapters = c.chapterStyle.headings,
                chapterEndLines = c.chapterStyle.lines,
                verseOnNewLine = c.verseOnNewLine,
                highlight = c.highlight,
                underlineUniqueWords = c.underlineUniqueWords,
                chapterHeading = c.chapterHeading,
                sectionHeading = c.sectionHeading,
            )
        }
    }

    fun downloadQuestionFlashcards() {
        val round = flashcardRound
        val name = withSet("flashcards${round?.let { "-${it.name.lowercase()}" } ?: ""}${chSuffix(flashcardScope)}.pdf")
        download("Question flashcards", name) {
            api.flashcardsPdf(flashcardScope.chapter, round, book = flashcardScope.book?.name)
        }
    }

    fun downloadPracticeTest(round: Round) {
        val limit = practiceLimit.takeIf { round.crowdSourced }
        val seed = practiceSeed.toIntOrNull().takeIf { !round.crowdSourced }
        download(
            "Round ${round.number}: ${round.displayName}",
            withSet(
                "practice-${round.name.lowercase()}${chSuffix(practiceScope, cumulative = round.textGenerated)}" +
                    "${seed?.let { "-seed$it" } ?: ""}.pdf",
            ),
        ) {
            api.practiceTestPdf(
                round, practiceScope.chapter, limit = limit, seed = seed, book = practiceScope.book?.name,
            )
        }
    }

    fun downloadHeadingFlashcards() {
        download("Chapter-heading flashcards", withSet("heading-flashcards${chSuffix(headingScope, cumulative = true)}.pdf")) {
            api.headingFlashcardsPdf(headingScope.chapter, book = headingScope.book?.name)
        }
    }

    fun downloadExport(app: ExportApp) {
        val headings = exportHeadings
        val round = exportRound.takeIf { !headings }
        val base = withSet(
            if (headings) "headings${chSuffix(exportScope, cumulative = true)}"
            else "questions${round?.let { "-${it.name.lowercase()}" } ?: ""}${chSuffix(exportScope)}",
        )
        when (app) {
            ExportApp.KAHOOT -> download("Kahoot spreadsheet", "kahoot-$base.xlsx", Mime.XLSX) {
                api.kahootQuestionsXlsx(headings, round, exportScope.chapter, book = exportScope.book?.name)
            }
            ExportApp.SPACE -> download("Questions for the Space app", "space-$base.csv", Mime.CSV) {
                api.spaceQuestionsCsv(headings, round, exportScope.chapter, book = exportScope.book?.name)
            }
            ExportApp.QUIZLET -> download("Questions for Quizlet", "quizlet-$base.txt", Mime.TEXT) {
                api.quizletQuestionsTxt(headings, round, exportScope.chapter, book = exportScope.book?.name)
            }
        }
    }

    val season = LocalSeason.current

    Column(
        Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            section.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            blurb(section, season.eventScripture),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.secondary,
        )

        message?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        }

        // Each section carries its downloads AND its interactive tools, so a subject has one home.
        when (section) {
            StudySection.TEXT -> {
                DownloadCard(
                    title = "Printable study text",
                    subtitle = "Fully customizable text of ${season.eventScripture} with support for " +
                        "highlighting by category, underlining one-time words, and more! " +
                        customizedNote(textChoices != StudyTextChoices()),
                    busyCard = busyCard,
                    onClick = ::downloadStudyText,
                    onCustomize = { customize = Customize.StudyText },
                )
                ReadListenCard()
            }

            StudySection.GENERAL -> {
                DownloadCard(
                    title = "Study guide",
                    subtitle = "The official multiple-choice study guide for ${season.eventScripture} — every " +
                        "chapter's review questions with an answer key. The single best place to start.",
                    busyCard = busyCard,
                    onClick = { download("Study guide", withSet(PdfFileNames.studyGuide())) { api.studyGuidePdf() } },
                )
                DownloadCard(
                    title = "Study guide — answer copy",
                    subtitle = "The same guide with each correct answer marked (★) and no separate key — handy for " +
                        "coaches, parents, and self-checking.",
                    busyCard = busyCard,
                    onClick = {
                        download("Study guide — answer copy", withSet(PdfFileNames.studyGuideAnswers())) {
                            api.studyGuideAnswersPdf()
                        }
                    },
                )
                DownloadCard(
                    title = "Question flashcards",
                    subtitle = "Duplex deck built from the approved community questions." + scopeNote(flashcardScope) +
                        customizedNote(flashcardRound != null),
                    busyCard = busyCard,
                    onClick = ::downloadQuestionFlashcards,
                    onCustomize = { customize = Customize.QuestionFlashcards },
                )
                LinkCard(
                    title = "Quiz me",
                    subtitle = "Drill the community question bank with instant feedback — the interactive twin " +
                        "of the flashcards and practice tests.",
                    actionLabel = "Open Quiz",
                    onClick = onOpenQuiz,
                )
                LinkCard(
                    title = "Community questions",
                    subtitle = "Browse the community question bank, vote for the best questions, and submit your own.",
                    actionLabel = "Browse questions",
                    onClick = onOpenQuestions,
                )
            }

            StudySection.HEADINGS -> {
                DownloadCard(
                    title = "Chapter headings list",
                    subtitle = "Every ESV section heading in ${season.eventScripture} on a single page, in order " +
                        "with the verses it covers — print it once and keep it in front of you.",
                    busyCard = busyCard,
                    onClick = {
                        download("Chapter headings list", withSet(PdfFileNames.chapterHeadings())) {
                            api.chapterHeadingsPdf()
                        }
                    },
                )
                DownloadCard(
                    title = "Chapter-heading flashcards",
                    subtitle = "One card per ESV section heading (Round 5 material)." +
                        (headingScope.label()?.let { " Through $it." } ?: ""),
                    busyCard = busyCard,
                    onClick = ::downloadHeadingFlashcards,
                    onCustomize = { customize = Customize.HeadingFlashcards },
                )
                LinkCard(
                    title = "Headings browser & self-check",
                    subtitle = "Browse every ESV section heading, or flip to self-check mode — the interactive " +
                        "twin of the flashcards. Quiz can also drill headings.",
                    actionLabel = "Open browser",
                    onClick = onOpenHeadings,
                )
            }

            StudySection.UNIQUE_WORDS -> {
                DownloadCard(
                    title = "Unique-word flashcards",
                    subtitle = "One card per word that appears only once in ${season.eventScripture} — the word up " +
                        "front, its verse on the back. A powerful memory hook for pinpointing chapters.",
                    busyCard = busyCard,
                    onClick = {
                        download("Unique-word flashcards", withSet(PdfFileNames.uniqueWordFlashcards())) {
                            api.uniqueWordFlashcardsPdf()
                        }
                    },
                )
                DownloadCard(
                    title = "Unique words index",
                    subtitle = "Every word that appears only once in ${season.eventScripture} — alphabetical and " +
                        "in order of appearance.",
                    busyCard = busyCard,
                    onClick = { download("Unique words index", withSet(PdfFileNames.uniqueWordsIndex())) { api.uniqueWordsIndexPdf() } },
                )
                DownloadCard(
                    title = "Flashcards for the Space app",
                    subtitle = "The flashcard deck as a CSV that imports straight into Space (getspace.app) — " +
                        "each unique word up front, its verse with the word bolded on the back.",
                    busyCard = busyCard,
                    onClick = {
                        download("Flashcards for the Space app", "space-${withSet("unique-words")}.csv", Mime.CSV) {
                            api.uniqueWordFlashcardsCsv()
                        }
                    },
                )
                DownloadCard(
                    title = "Flashcards for Quizlet",
                    subtitle = "The same deck as paste-ready text for Quizlet's import screen — choose Tab " +
                        "between term and definition, and enter \\n\\n as the custom separator between cards.",
                    busyCard = busyCard,
                    onClick = {
                        download("Flashcards for Quizlet", "quizlet-${withSet("unique-words")}.txt", Mime.TEXT) {
                            api.uniqueWordFlashcardsTxt()
                        }
                    },
                )
            }

            StudySection.PRACTICE_TESTS -> {
                // R1–R5 only: the Power Round has no generator or question bank behind it.
                Round.entries.filter { it.number in 1..5 }.forEach { round ->
                    val roundCustomized =
                        if (round.crowdSourced) practiceLimit != null else practiceSeed.toIntOrNull() != null
                    DownloadCard(
                        title = "Round ${round.number}: ${round.displayName}",
                        subtitle = (if (round.crowdSourced) "Built from the approved community questions."
                        else "Generated from the ESV text.") + scopeNote(practiceScope) + customizedNote(roundCustomized),
                        busyCard = busyCard,
                        onClick = { downloadPracticeTest(round) },
                        onCustomize = { customize = Customize.PracticeTest(round) },
                    )
                }
            }

            StudySection.REFERENCE -> {
                DownloadCard(
                    title = "Names index",
                    subtitle = "Every proper name in ${season.eventScripture} with its verses — alphabetical and by frequency.",
                    busyCard = busyCard,
                    onClick = { download("Names index", withSet(PdfFileNames.namesIndex())) { api.namesIndexPdf() } },
                )
                DownloadCard(
                    title = "Numbers index",
                    subtitle = "Every number in ${season.eventScripture} with its verses — alphabetical and by frequency.",
                    busyCard = busyCard,
                    onClick = { download("Numbers index", withSet(PdfFileNames.numbersIndex())) { api.numbersIndexPdf() } },
                )
                DownloadCard(
                    title = "Men index",
                    subtitle = "Every man named in ${season.eventScripture} with the verses he appears in.",
                    busyCard = busyCard,
                    onClick = { download("Men index", withSet(PdfFileNames.menIndex())) { api.menIndexPdf() } },
                )
                DownloadCard(
                    title = "Women index",
                    subtitle = "Every woman named in ${season.eventScripture} with the verses she appears in.",
                    busyCard = busyCard,
                    onClick = { download("Women index", withSet(PdfFileNames.womenIndex())) { api.womenIndexPdf() } },
                )
                DownloadCard(
                    title = "Places index",
                    subtitle = "Every place named in ${season.eventScripture} with the verses it appears in.",
                    busyCard = busyCard,
                    onClick = { download("Places index", withSet(PdfFileNames.placesIndex())) { api.placesIndexPdf() } },
                )
                DownloadCard(
                    title = "Full word index",
                    subtitle = "A complete concordance — every significant word in ${season.eventScripture} with its verses.",
                    busyCard = busyCard,
                    onClick = { download("Full word index", withSet(PdfFileNames.fullIndex())) { api.fullIndexPdf() } },
                )
                LinkCard(
                    title = "Names & numbers browser",
                    subtitle = "Search and filter the names and numbers indices — the interactive twin of the PDFs.",
                    actionLabel = "Open browser",
                    onClick = onOpenIndices,
                )
            }

            StudySection.DATA -> {
                val exportCustomized = exportHeadings || exportRound != null
                DownloadCard(
                    title = "Kahoot spreadsheet",
                    subtitle = "Multiple-choice questions as a Kahoot-importable .xlsx (their template layout)." +
                        scopeNote(exportScope) + customizedNote(exportCustomized),
                    busyCard = busyCard,
                    onClick = { downloadExport(ExportApp.KAHOOT) },
                    onCustomize = { customize = Customize.Export(ExportApp.KAHOOT) },
                )
                DownloadCard(
                    title = "Questions for the Space app",
                    subtitle = "Question-and-answer pairs as a CSV that imports straight into Space " +
                        "(getspace.app)." + scopeNote(exportScope) + customizedNote(exportCustomized),
                    busyCard = busyCard,
                    onClick = { downloadExport(ExportApp.SPACE) },
                    onCustomize = { customize = Customize.Export(ExportApp.SPACE) },
                )
                DownloadCard(
                    title = "Questions for Quizlet",
                    subtitle = "The same pairs as paste-ready text for Quizlet's import screen — its default " +
                        "settings read them as-is." + scopeNote(exportScope) + customizedNote(exportCustomized),
                    busyCard = busyCard,
                    onClick = { downloadExport(ExportApp.QUIZLET) },
                    onCustomize = { customize = Customize.Export(ExportApp.QUIZLET) },
                )
                DownloadCard(
                    title = "Study guide (CSV)",
                    subtitle = "The full study-guide question bank as comma-separated text — every question, its " +
                        "choices, answer, and reference — for building your own materials.",
                    busyCard = busyCard,
                    onClick = { download("Study guide (CSV)", "study-guide.csv", Mime.CSV) { api.studyGuideCsv() } },
                )
            }
        }

        // Admin-curated extras for this section (uploaded documents + external links), below the
        // built-in cards like the web app. Best-effort: a fetch failure only costs the extras —
        // the built-in cards must never break on a public screen.
        var extraMaterials by remember(section, studySet) { mutableStateOf(emptyList<StudyMaterialDto>()) }
        LaunchedEffect(section, studySet) {
            runCatching { api.studyMaterials(set = studySet.simpleName, section = section) }
                .onSuccess { extraMaterials = it }
        }
        val uriHandler = LocalUriHandler.current
        extraMaterials.forEach { m ->
            when (m.type) {
                StudyMaterialType.DOCUMENT -> DownloadCard(
                    title = m.title,
                    subtitle = m.description,
                    busyCard = busyCard,
                    onClick = {
                        // saveFile gets the byte-exact original under its original name and type.
                        download(m.title, m.fileName ?: "download", m.contentType ?: Mime.PDF) {
                            api.studyMaterialFile(m.id)
                        }
                    },
                    buttonLabel = "Download",
                )
                StudyMaterialType.LINK -> LinkCard(m.title, m.description, actionLabel = "Open") {
                    m.url?.let(uriHandler::openUri)
                }
            }
        }
    }

    when (val target = customize) {
        null -> {}
        else -> ModalBottomSheet(
            onDismissRequest = { customize = null },
            // Fully expanded from the start: the default half-expansion hides most options (and
            // the action) behind a drag gesture that mouse users never discover.
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
                Column(
                    // Only the options scroll; the Download button below stays pinned and visible.
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    when (target) {
                        Customize.StudyText -> StudyTextOptions(
                            choices = textChoices,
                            onChange = { textChoices = it },
                        )
                        Customize.QuestionFlashcards -> QuestionFlashcardOptions(
                            selection = flashcardScope, onSelection = { flashcardScope = it },
                            round = flashcardRound,
                            onChange = { flashcardRound = it },
                        )
                        Customize.HeadingFlashcards -> HeadingFlashcardOptions(
                            selection = headingScope, onSelection = { headingScope = it },
                        )
                        is Customize.PracticeTest -> PracticeTestOptions(
                            round = target.round,
                            selection = practiceScope, onSelection = { practiceScope = it },
                            limit = practiceLimit, onLimit = { practiceLimit = it },
                            seedText = practiceSeed, onSeedText = { practiceSeed = it },
                        )
                        is Customize.Export -> ExportOptions(
                            app = target.app,
                            selection = exportScope, onSelection = { exportScope = it },
                            headingsSource = exportHeadings, onHeadingsSource = { exportHeadings = it },
                            round = exportRound, onRound = { exportRound = it },
                        )
                    }
                }
                SheetDownloadButton {
                    when (target) {
                        Customize.StudyText -> downloadStudyText()
                        Customize.QuestionFlashcards -> downloadQuestionFlashcards()
                        Customize.HeadingFlashcards -> downloadHeadingFlashcards()
                        is Customize.PracticeTest -> downloadPracticeTest(target.round)
                        is Customize.Export -> downloadExport(target.app)
                    }
                }
            }
        }
    }
}

@Composable
private fun StudyTextOptions(
    choices: StudyTextChoices,
    onChange: (StudyTextChoices) -> Unit,
) {
    SheetTitle("Customize study text")
    Text("Font size", style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(9, 10, 11, 12, 13, 14, 15).forEach { size ->
            FilterChip(
                selected = choices.fontSize == size,
                onClick = { onChange(choices.copy(fontSize = size)) },
                label = { Text("$size pt") },
            )
        }
    }
    OptionSwitch("Underline words that appear only once", choices.underlineUniqueWords) {
        onChange(choices.copy(underlineUniqueWords = it))
    }
    OptionSwitch("Highlight names & numbers by category", choices.highlight) {
        onChange(choices.copy(highlight = it))
    }
    Text("Chapter titles", style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ChapterStyle.entries.forEach { style ->
            FilterChip(
                selected = choices.chapterStyle == style,
                onClick = { onChange(choices.copy(chapterStyle = style)) },
                label = { Text(style.label) },
            )
        }
    }
    // Only meaningful when chapters actually render as headings; inline chapter labels take the
    // body size, so the chips would be a control that does nothing.
    if (choices.chapterStyle.headings) {
        Text("Chapter heading size", style = MaterialTheme.typography.labelLarge)
        HeadingSizeChips(choices.chapterHeading) { onChange(choices.copy(chapterHeading = it)) }
    }
    Text("Section heading size", style = MaterialTheme.typography.labelLarge)
    HeadingSizeChips(choices.sectionHeading) { onChange(choices.copy(sectionHeading = it)) }
    OptionSwitch("Two columns", choices.twoColumns) { onChange(choices.copy(twoColumns = it)) }
    OptionSwitch("Justified text", choices.justified) { onChange(choices.copy(justified = it)) }
    OptionSwitch("Each verse starts on a new line", choices.verseOnNewLine) {
        onChange(choices.copy(verseOnNewLine = it))
    }
    /* This is not a legal option, so don't encourage it
    OptionSwitch("Each chapter starts a new page", choices.chapterBreaksPage) {
        onChange(choices.copy(chapterBreaksPage = it))
    }*/
}

/** The shared heading-size chips, used for both the chapter and section controls. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeadingSizeChips(selected: HeadingSize, onSelect: (HeadingSize) -> Unit) {
    // FlowRow, not Row: these labels are words rather than "11 pt", so they wrap on a phone.
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        HeadingSize.entries.forEach { size ->
            FilterChip(
                selected = selected == size,
                onClick = { onSelect(size) },
                label = { Text(size.label) },
            )
        }
    }
}

@Composable
private fun QuestionFlashcardOptions(
    selection: ScopeSelection, onSelection: (ScopeSelection) -> Unit,
    round: Round?, onChange: (Round?) -> Unit,
) {
    SheetTitle("Customize question flashcards")
    ChapterScope(selection, onSelection)
    Text("Round", style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = round == null, onClick = { onChange(null) }, label = { Text("All") })
        Round.crowdSourcedRounds.forEach { rt ->
            FilterChip(selected = round == rt, onClick = { onChange(rt) }, label = { Text(rt.displayName) })
        }
    }
}

@Composable
private fun HeadingFlashcardOptions(selection: ScopeSelection, onSelection: (ScopeSelection) -> Unit) {
    SheetTitle("Customize chapter-heading flashcards")
    ChapterScope(selection, onSelection, label = "Through chapter")
}

@Composable
private fun PracticeTestOptions(
    round: Round,
    selection: ScopeSelection, onSelection: (ScopeSelection) -> Unit,
    limit: Int?, onLimit: (Int?) -> Unit,
    seedText: String, onSeedText: (String) -> Unit,
) {
    SheetTitle("Customize: ${round.displayName}")
    ChapterScope(selection, onSelection)
    if (round.crowdSourced) {
        Text("Number of questions", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = limit == null, onClick = { onLimit(null) }, label = { Text("Default (40)") })
            listOf(10, 20, 60, 100).forEach { n ->
                FilterChip(selected = limit == n, onClick = { onLimit(n) }, label = { Text("$n") })
            }
        }
    } else {
        OutlinedTextField(
            value = seedText,
            onValueChange = { onSeedText(it.filter(Char::isDigit).take(4)) },
            label = { Text("Seed (same seed → same test again)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        Text(
            "Leave blank for a fresh random test. The seed prints on the test so a group can regenerate it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ExportOptions(
    app: ExportApp,
    selection: ScopeSelection, onSelection: (ScopeSelection) -> Unit,
    headingsSource: Boolean, onHeadingsSource: (Boolean) -> Unit,
    round: Round?, onRound: (Round?) -> Unit,
) {
    SheetTitle(
        when (app) {
            ExportApp.KAHOOT -> "Customize Kahoot export"
            ExportApp.SPACE -> "Customize Space export"
            ExportApp.QUIZLET -> "Customize Quizlet export"
        },
    )
    ChapterScope(selection, onSelection)
    Text("Source", style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(
            selected = !headingsSource,
            onClick = { onHeadingsSource(false) },
            label = { Text("Question bank") },
        )
        FilterChip(
            selected = headingsSource,
            onClick = { onHeadingsSource(true) },
            label = { Text("Chapter headings") },
        )
    }
    if (!headingsSource) {
        Text("Round", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = round == null, onClick = { onRound(null) }, label = { Text("All") })
            Round.crowdSourcedRounds.forEach { rt ->
                FilterChip(selected = round == rt, onClick = { onRound(rt) }, label = { Text(rt.displayName) })
            }
        }
    }
    if (app == ExportApp.KAHOOT) {
        Text(
            "Kahoot needs multiple-choice material; open-answer questions are left out.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Chapter chips for one card group's downloads; the cards' subtitles echo the choice. */
@Composable
private fun ChapterScope(
    selection: ScopeSelection,
    onSelection: (ScopeSelection) -> Unit,
    label: String = "Chapter scope",
) {
    Text(label, style = MaterialTheme.typography.labelLarge)
    ChapterChips(selected = selection, onSelect = onSelection)
}

@Composable
private fun SheetTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun OptionSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SheetDownloadButton(onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text("Download")
    }
}

private fun scopeNote(selection: ScopeSelection): String =
    selection.label()?.let { " Scoped to $it." } ?: ""

private fun customizedNote(customized: Boolean): String =
    if (customized) " Using your customized settings." else ""

/** A card whose action opens an in-app tool rather than downloading — the interactive twin of a download. */
@Composable
private fun LinkCard(title: String, subtitle: String, actionLabel: String, onClick: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilledTonalButton(onClick = onClick) { Text(actionLabel) }
        }
    }
}

/**
 * Read/listen links for the season text — the text is the hub, not just a download. The ESV text
 * and audio belong to their publishers (license is server-side only), so these link out.
 */
@Composable
private fun ReadListenCard() {
    val uriHandler = LocalUriHandler.current
    val season = LocalSeason.current
    val links = listOf(
        "esv.org" to "https://www.esv.org/${season.bookCode}/",
        "blueletterbible.org" to "https://www.blueletterbible.org/esv/${season.bookCode}",
        "biblegateway.com" to "https://www.biblegateway.com/passage/?version=ESV&search=${season.bookCode}",
    )
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Read or listen online", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold)
            Text(
                "Read ${season.eventScripture} in your browser or play the audio narration — opens the " +
                    "publisher's site in a new tab.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                links.forEach { (name, url) ->
                    AssistChip(onClick = { uriHandler.openUri(url) }, label = { Text(name) })
                }
            }
        }
    }
}

@Composable
private fun DownloadCard(
    title: String,
    subtitle: String,
    busyCard: String?,
    onClick: () -> Unit,
    onCustomize: (() -> Unit)? = null,
    buttonLabel: String? = null,
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = onClick, enabled = busyCard == null) {
                    if (busyCard == title) CircularProgressIndicator(Modifier.height(16.dp))
                    else Text(
                        buttonLabel
                            ?: if (title.contains("CSV") || title.contains("spreadsheet")) "Download"
                            else "Download PDF",
                    )
                }
                if (onCustomize != null) {
                    TextButton(onClick = onCustomize, enabled = busyCard == null) { Text("Customize") }
                }
            }
        }
    }
}
