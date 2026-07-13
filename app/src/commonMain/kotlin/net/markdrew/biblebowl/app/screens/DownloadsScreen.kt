package net.markdrew.biblebowl.app.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.markdrew.biblebowl.model.Round
import net.markdrew.biblebowl.app.net.TbbApi
import net.markdrew.biblebowl.app.platform.Mime
import net.markdrew.biblebowl.app.platform.saveFile
import net.markdrew.biblebowl.app.ui.ChapterChips
import net.markdrew.biblebowl.app.ui.LocalSeason

/** Which card's "Customize" sheet is open. */
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
 * each card is one click to a sensible default, with options behind a "Customize" sheet. Public.
 * The chapter chip row scopes the chapter-aware generators (flashcards, practice tests, exports).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(api: TbbApi) {
    var chapter by remember { mutableStateOf<Int?>(null) }
    var busyCard by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    var customize by remember { mutableStateOf<Customize?>(null) }
    // Sheet choices live at screen level so closing/reopening a sheet keeps the last selections.
    var textChoices by remember { mutableStateOf(StudyTextChoices()) }
    var flashcardRound by remember { mutableStateOf<Round?>(null) }
    var practiceLimit by remember { mutableStateOf<Int?>(null) }
    var practiceSeed by remember { mutableStateOf("") }
    var exportHeadings by remember { mutableStateOf(false) }
    var exportRound by remember { mutableStateOf<Round?>(null) }
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

    val chSuffix = chapter?.let { "-ch$it" } ?: ""
    val throughSuffix = chapter?.let { "-through-ch$it" } ?: ""

    // One download action per card, honoring the (sticky) customize choices. Used by BOTH the
    // card's primary button and the sheet's Download button — customizing and then pressing
    // either button yields the customized document, and the file name spells out the options.
    fun downloadStudyText() {
        val c = textChoices
        val name = buildString {
            append("bible-text")
            if (c.highlight) append("-highlighted")
            if (c.twoColumns) append("-2col")
            if (c.justified) append("-justified")
            if (c.chapterBreaksPage) append("-page-per-ch")
            if (c.underlineUniqueWords) append("-unique-words")
            if (c.fontSize != 11) append("-${c.fontSize}pt")
            append(".pdf")
        }
        download("Highlighted study text", name) {
            api.bibleTextPdf(
                fontSize = c.fontSize.takeIf { it != 11 },
                twoColumns = c.twoColumns,
                justified = c.justified,
                chapterBreaksPage = c.chapterBreaksPage,
                highlight = c.highlight,
                underlineUniqueWords = c.underlineUniqueWords,
            )
        }
    }

    fun downloadQuestionFlashcards() {
        val round = flashcardRound
        download("Question flashcards", "flashcards${round?.let { "-${it.name.lowercase()}" } ?: ""}$chSuffix.pdf") {
            api.flashcardsPdf(chapter, round)
        }
    }

    fun downloadPracticeTest(round: Round) {
        val limit = practiceLimit.takeIf { round.crowdSourced }
        val seed = practiceSeed.toIntOrNull().takeIf { !round.crowdSourced }
        download(
            "Round ${round.number}: ${round.displayName}",
            "practice-${round.name.lowercase()}$chSuffix${seed?.let { "-seed$it" } ?: ""}.pdf",
        ) {
            api.practiceTestPdf(round, chapter, limit = limit, seed = seed)
        }
    }

    fun downloadExport(kahoot: Boolean) {
        val headings = exportHeadings
        val round = exportRound.takeIf { !headings }
        val base = if (headings) "headings$throughSuffix" else
            "questions${round?.let { "-${it.name.lowercase()}" } ?: ""}$chSuffix"
        if (kahoot) {
            download("Kahoot spreadsheet", "kahoot-$base.xlsx", Mime.XLSX) {
                api.questionsXlsx(headings, round, chapter)
            }
        } else {
            download("Quizlet / Space TSV", "quizlet-$base.tsv", Mime.TSV) {
                api.questionsTsv(headings, round, chapter)
            }
        }
    }

    Column(
        Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Chapter scope (flashcards, practice tests & exports)",
            style = MaterialTheme.typography.labelLarge,
        )
        ChapterChips(selected = chapter, onSelect = { chapter = it })

        message?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        }

        GroupHeader("Study text")
        DownloadCard(
            title = "Highlighted study text",
            subtitle = "The full text of ${LocalSeason.current.eventScripture} with names, numbers, and more " +
                "highlighted by category — the flagship study document." + customizedNote(textChoices != StudyTextChoices()),
            busyCard = busyCard,
            onClick = ::downloadStudyText,
            onCustomize = { customize = Customize.StudyText },
        )

        GroupHeader("Flashcards")
        DownloadCard(
            title = "Question flashcards",
            subtitle = "Duplex deck built from the approved community questions." + scopeNote(chapter) +
                customizedNote(flashcardRound != null),
            busyCard = busyCard,
            onClick = ::downloadQuestionFlashcards,
            onCustomize = { customize = Customize.QuestionFlashcards },
        )
        DownloadCard(
            title = "Chapter-heading flashcards",
            subtitle = "One card per ESV section heading (Round 5 material)." +
                (chapter?.let { " Through chapter $it." } ?: ""),
            busyCard = busyCard,
            onClick = {
                download("Chapter-heading flashcards", "heading-flashcards$throughSuffix.pdf") {
                    api.headingFlashcardsPdf(chapter)
                }
            },
        )

        GroupHeader("Indices")
        DownloadCard(
            title = "Names index",
            subtitle = "Every proper name in ${LocalSeason.current.eventScripture} with its verses — alphabetical and by frequency.",
            busyCard = busyCard,
            onClick = { download("Names index", "names-index.pdf") { api.namesIndexPdf() } },
        )
        DownloadCard(
            title = "Numbers index",
            subtitle = "Every number in ${LocalSeason.current.eventScripture} with its verses — alphabetical and by frequency.",
            busyCard = busyCard,
            onClick = { download("Numbers index", "numbers-index.pdf") { api.numbersIndexPdf() } },
        )

        GroupHeader("Practice tests")
        // R1–R5 only: the Power Round has no generator or question bank behind it.
        Round.entries.filter { it.number in 1..5 }.forEach { round ->
            val roundCustomized =
                if (round.crowdSourced) practiceLimit != null else practiceSeed.toIntOrNull() != null
            DownloadCard(
                title = "Round ${round.number}: ${round.displayName}",
                subtitle = (if (round.crowdSourced) "Built from the approved community questions."
                else "Generated from the ESV text.") + scopeNote(chapter) + customizedNote(roundCustomized),
                busyCard = busyCard,
                onClick = { downloadPracticeTest(round) },
                onCustomize = { customize = Customize.PracticeTest(round) },
            )
        }

        GroupHeader("Exports")
        val exportCustomized = exportHeadings || exportRound != null
        DownloadCard(
            title = "Kahoot spreadsheet",
            subtitle = "Multiple-choice questions as a Kahoot-importable .xlsx (their template layout)." +
                scopeNote(chapter) + customizedNote(exportCustomized),
            busyCard = busyCard,
            onClick = { downloadExport(kahoot = true) },
            onCustomize = { customize = Customize.Export(kahoot = true) },
        )
        DownloadCard(
            title = "Quizlet / Space TSV",
            subtitle = "Question-and-answer pairs as tab-separated text, import-ready for " +
                "Quizlet, Space, or Anki." + scopeNote(chapter) + customizedNote(exportCustomized),
            busyCard = busyCard,
            onClick = { downloadExport(kahoot = false) },
            onCustomize = { customize = Customize.Export(kahoot = false) },
        )
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
                            round = flashcardRound,
                            onChange = { flashcardRound = it },
                        )
                        is Customize.PracticeTest -> PracticeTestOptions(
                            round = target.round,
                            limit = practiceLimit, onLimit = { practiceLimit = it },
                            seedText = practiceSeed, onSeedText = { practiceSeed = it },
                        )
                        is Customize.Export -> ExportOptions(
                            kahoot = target.kahoot,
                            headingsSource = exportHeadings, onHeadingsSource = { exportHeadings = it },
                            round = exportRound, onRound = { exportRound = it },
                        )
                    }
                }
                SheetDownloadButton {
                    when (target) {
                        Customize.StudyText -> downloadStudyText()
                        Customize.QuestionFlashcards -> downloadQuestionFlashcards()
                        is Customize.PracticeTest -> downloadPracticeTest(target.round)
                        is Customize.Export -> downloadExport(target.kahoot)
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
        listOf(9, 10, 11, 12, 14).forEach { size ->
            FilterChip(
                selected = choices.fontSize == size,
                onClick = { onChange(choices.copy(fontSize = size)) },
                label = { Text("$size pt") },
            )
        }
    }
    OptionSwitch("Two columns", choices.twoColumns) { onChange(choices.copy(twoColumns = it)) }
    OptionSwitch("Justified text", choices.justified) { onChange(choices.copy(justified = it)) }
    OptionSwitch("Each chapter starts a new page", choices.chapterBreaksPage) {
        onChange(choices.copy(chapterBreaksPage = it))
    }
    OptionSwitch("Highlight names & numbers by category", choices.highlight) {
        onChange(choices.copy(highlight = it))
    }
    OptionSwitch("Underline words that appear only once", choices.underlineUniqueWords) {
        onChange(choices.copy(underlineUniqueWords = it))
    }
}

@Composable
private fun QuestionFlashcardOptions(round: Round?, onChange: (Round?) -> Unit) {
    SheetTitle("Customize question flashcards")
    Text("Round", style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = round == null, onClick = { onChange(null) }, label = { Text("All") })
        Round.crowdSourcedRounds.forEach { rt ->
            FilterChip(selected = round == rt, onClick = { onChange(rt) }, label = { Text(rt.displayName) })
        }
    }
}

@Composable
private fun PracticeTestOptions(
    round: Round,
    limit: Int?, onLimit: (Int?) -> Unit,
    seedText: String, onSeedText: (String) -> Unit,
) {
    SheetTitle("Customize: ${round.displayName}")
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
    kahoot: Boolean,
    headingsSource: Boolean, onHeadingsSource: (Boolean) -> Unit,
    round: Round?, onRound: (Round?) -> Unit,
) {
    SheetTitle(if (kahoot) "Customize Kahoot export" else "Customize Quizlet/Space export")
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
    if (kahoot) {
        Text(
            "Kahoot needs multiple-choice material; open-answer questions are left out.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
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

private fun scopeNote(chapter: Int?): String = chapter?.let { " Scoped to chapter $it." } ?: ""

private fun customizedNote(customized: Boolean): String =
    if (customized) " Using your customized settings." else ""

@Composable
private fun GroupHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun DownloadCard(
    title: String,
    subtitle: String,
    busyCard: String?,
    onClick: () -> Unit,
    onCustomize: (() -> Unit)? = null,
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
                    else Text(if (title.contains("TSV") || title.contains("spreadsheet")) "Download" else "Download PDF")
                }
                if (onCustomize != null) {
                    TextButton(onClick = onCustomize, enabled = busyCard == null) { Text("Customize") }
                }
            }
        }
    }
}
