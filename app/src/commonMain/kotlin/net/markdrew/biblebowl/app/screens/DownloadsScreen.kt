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

/** Which card's "Customize" sheet is open. */
private sealed interface Customize {
    data object StudyText : Customize
    data object QuestionFlashcards : Customize
    data class PracticeTest(val round: Round) : Customize
    data class Export(val kahoot: Boolean) : Customize
}

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

    Column(
        Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Chapter scope (flashcards, practice tests & exports)",
            style = MaterialTheme.typography.labelLarge,
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilterChip(selected = chapter == null, onClick = { chapter = null }, label = { Text("All") })
            (1..ACTS_CHAPTERS).forEach { ch ->
                FilterChip(
                    selected = chapter == ch,
                    onClick = { chapter = if (chapter == ch) null else ch },
                    label = { Text("$ch") },
                )
            }
        }

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
            subtitle = "The full text of Acts with names, numbers, and more highlighted by category — " +
                "the flagship study document.",
            busyCard = busyCard,
            onClick = { download("Highlighted study text", "bible-text-highlighted.pdf") { api.bibleTextPdf() } },
            onCustomize = { customize = Customize.StudyText },
        )

        GroupHeader("Flashcards")
        DownloadCard(
            title = "Question flashcards",
            subtitle = "Duplex deck built from the approved community questions." + scopeNote(chapter),
            busyCard = busyCard,
            onClick = { download("Question flashcards", "flashcards$chSuffix.pdf") { api.flashcardsPdf(chapter) } },
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
            subtitle = "Every proper name in Acts with its verses — alphabetical and by frequency.",
            busyCard = busyCard,
            onClick = { download("Names index", "names-index.pdf") { api.namesIndexPdf() } },
        )
        DownloadCard(
            title = "Numbers index",
            subtitle = "Every number in Acts with its verses — alphabetical and by frequency.",
            busyCard = busyCard,
            onClick = { download("Numbers index", "numbers-index.pdf") { api.numbersIndexPdf() } },
        )

        GroupHeader("Practice tests")
        // R1–R5 only: the Power Round has no generator or question bank behind it.
        Round.entries.filter { it.number in 1..5 }.forEach { round ->
            DownloadCard(
                title = "Round ${round.number}: ${round.displayName}",
                subtitle = (if (round.crowdSourced) "Built from the approved community questions."
                else "Generated from the ESV text.") + scopeNote(chapter),
                busyCard = busyCard,
                onClick = {
                    download("Round ${round.number}: ${round.displayName}",
                        "practice-${round.name.lowercase()}$chSuffix.pdf") {
                        api.practiceTestPdf(round, chapter)
                    }
                },
                onCustomize = { customize = Customize.PracticeTest(round) },
            )
        }

        GroupHeader("Exports")
        DownloadCard(
            title = "Kahoot spreadsheet",
            subtitle = "Multiple-choice questions as a Kahoot-importable .xlsx (their template layout)." +
                scopeNote(chapter),
            busyCard = busyCard,
            onClick = {
                download("Kahoot spreadsheet", "kahoot-questions$chSuffix.xlsx", Mime.XLSX) {
                    api.questionsXlsx(chapter = chapter)
                }
            },
            onCustomize = { customize = Customize.Export(kahoot = true) },
        )
        DownloadCard(
            title = "Quizlet / Space TSV",
            subtitle = "Question-and-answer pairs as tab-separated text, import-ready for " +
                "Quizlet, Space, or Anki." + scopeNote(chapter),
            busyCard = busyCard,
            onClick = {
                download("Quizlet / Space TSV", "quizlet-questions$chSuffix.tsv", Mime.TSV) {
                    api.questionsTsv(chapter = chapter)
                }
            },
            onCustomize = { customize = Customize.Export(kahoot = false) },
        )
    }

    when (val target = customize) {
        null -> {}
        else -> ModalBottomSheet(onDismissRequest = { customize = null }) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (target) {
                    Customize.StudyText -> StudyTextOptions { fontSize, twoCol, justified, pageBreaks, highlight, unique ->
                        download(
                            "Highlighted study text",
                            "bible-text${if (highlight) "-highlighted" else ""}.pdf",
                        ) {
                            api.bibleTextPdf(
                                fontSize = fontSize, twoColumns = twoCol, justified = justified,
                                chapterBreaksPage = pageBreaks, highlight = highlight, underlineUniqueWords = unique,
                            )
                        }
                    }
                    Customize.QuestionFlashcards -> QuestionFlashcardOptions { round ->
                        val name = "flashcards${round?.let { "-${it.name.lowercase()}" } ?: ""}$chSuffix.pdf"
                        download("Question flashcards", name) { api.flashcardsPdf(chapter, round) }
                    }
                    is Customize.PracticeTest -> PracticeTestOptions(target.round) { limit, seed ->
                        download(
                            "Round ${target.round.number}: ${target.round.displayName}",
                            "practice-${target.round.name.lowercase()}$chSuffix.pdf",
                        ) {
                            api.practiceTestPdf(target.round, chapter, limit = limit, seed = seed)
                        }
                    }
                    is Customize.Export -> ExportOptions(kahoot = target.kahoot) { headingsSource, round ->
                        val base = if (headingsSource) "headings$throughSuffix" else
                            "questions${round?.let { "-${it.name.lowercase()}" } ?: ""}$chSuffix"
                        if (target.kahoot) {
                            download("Kahoot spreadsheet", "kahoot-$base.xlsx", Mime.XLSX) {
                                api.questionsXlsx(headingsSource, round, chapter)
                            }
                        } else {
                            download("Quizlet / Space TSV", "quizlet-$base.tsv", Mime.TSV) {
                                api.questionsTsv(headingsSource, round, chapter)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StudyTextOptions(
    onDownload: (fontSize: Int?, twoColumns: Boolean, justified: Boolean, chapterBreaksPage: Boolean,
                 highlight: Boolean, underlineUniqueWords: Boolean) -> Unit,
) {
    var fontSize by remember { mutableStateOf(11) }
    var twoColumns by remember { mutableStateOf(false) }
    var justified by remember { mutableStateOf(false) }
    var chapterBreaksPage by remember { mutableStateOf(false) }
    var highlight by remember { mutableStateOf(true) }
    var underlineUniqueWords by remember { mutableStateOf(false) }

    SheetTitle("Customize study text")
    Text("Font size", style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(9, 10, 11, 12, 14).forEach { size ->
            FilterChip(selected = fontSize == size, onClick = { fontSize = size }, label = { Text("$size pt") })
        }
    }
    OptionSwitch("Two columns", twoColumns) { twoColumns = it }
    OptionSwitch("Justified text", justified) { justified = it }
    OptionSwitch("Each chapter starts a new page", chapterBreaksPage) { chapterBreaksPage = it }
    OptionSwitch("Highlight names & numbers by category", highlight) { highlight = it }
    OptionSwitch("Underline words that appear only once", underlineUniqueWords) { underlineUniqueWords = it }
    SheetDownloadButton {
        onDownload(fontSize.takeIf { it != 11 }, twoColumns, justified, chapterBreaksPage, highlight, underlineUniqueWords)
    }
}

@Composable
private fun QuestionFlashcardOptions(onDownload: (round: Round?) -> Unit) {
    var round by remember { mutableStateOf<Round?>(null) }

    SheetTitle("Customize question flashcards")
    Text("Round", style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = round == null, onClick = { round = null }, label = { Text("All") })
        Round.crowdSourcedRounds.forEach { rt ->
            FilterChip(selected = round == rt, onClick = { round = rt }, label = { Text(rt.displayName) })
        }
    }
    SheetDownloadButton { onDownload(round) }
}

@Composable
private fun PracticeTestOptions(round: Round, onDownload: (limit: Int?, seed: Int?) -> Unit) {
    var limit by remember { mutableStateOf<Int?>(null) }
    var seedText by remember { mutableStateOf("") }

    SheetTitle("Customize: ${round.displayName}")
    if (round.crowdSourced) {
        Text("Number of questions", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = limit == null, onClick = { limit = null }, label = { Text("Default (40)") })
            listOf(10, 20, 60, 100).forEach { n ->
                FilterChip(selected = limit == n, onClick = { limit = n }, label = { Text("$n") })
            }
        }
    } else {
        OutlinedTextField(
            value = seedText,
            onValueChange = { seedText = it.filter(Char::isDigit).take(4) },
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
    SheetDownloadButton { onDownload(limit, seedText.toIntOrNull()) }
}

@Composable
private fun ExportOptions(kahoot: Boolean, onDownload: (headingsSource: Boolean, round: Round?) -> Unit) {
    var headingsSource by remember { mutableStateOf(false) }
    var round by remember { mutableStateOf<Round?>(null) }

    SheetTitle(if (kahoot) "Customize Kahoot export" else "Customize Quizlet/Space export")
    Text("Source", style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(
            selected = !headingsSource,
            onClick = { headingsSource = false },
            label = { Text("Question bank") },
        )
        FilterChip(
            selected = headingsSource,
            onClick = { headingsSource = true },
            label = { Text("Chapter headings") },
        )
    }
    if (!headingsSource) {
        Text("Round", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = round == null, onClick = { round = null }, label = { Text("All") })
            Round.crowdSourcedRounds.forEach { rt ->
                FilterChip(selected = round == rt, onClick = { round = rt }, label = { Text(rt.displayName) })
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
    SheetDownloadButton { onDownload(headingsSource, round.takeIf { !headingsSource }) }
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
