package net.markdrew.biblebowl.app.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.markdrew.biblebowl.api.QuestionDto
import net.markdrew.biblebowl.model.Round
import net.markdrew.biblebowl.app.net.TbbApi
import net.markdrew.biblebowl.app.platform.savePdf

/** Acts has 28 chapters; used for the chapter filter strip. */
private const val ACTS_CHAPTERS = 28

@Composable
fun StudyScreen(api: TbbApi) {
    var questions by remember { mutableStateOf<List<QuestionDto>?>(null) }
    var chapter by remember { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun reload() {
        error = null
        try {
            questions = api.questions(chapter = chapter)
        } catch (e: Throwable) {
            error = e.message
        }
    }

    LaunchedEffect(chapter) { reload() }

    var pdfMenuOpen by remember { mutableStateOf(false) }
    var pdfBusy by remember { mutableStateOf(false) }
    var pdfMessage by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // One-tap chapter filter; "All" plus 1..28.
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilterChip(
                    selected = chapter == null,
                    onClick = { chapter = null },
                    label = { Text("All") },
                )
                (1..ACTS_CHAPTERS).forEach { ch ->
                    FilterChip(
                        selected = chapter == ch,
                        onClick = { chapter = if (chapter == ch) null else ch },
                        label = { Text("$ch") },
                    )
                }
            }
            Spacer(Modifier.weight(0.02f))
            Box {
                OutlinedButton(onClick = { pdfMenuOpen = true }, enabled = !pdfBusy) {
                    if (pdfBusy) CircularProgressIndicator(Modifier.height(16.dp))
                    else Text("Practice PDF")
                }
                DropdownMenu(expanded = pdfMenuOpen, onDismissRequest = { pdfMenuOpen = false }) {
                    fun download(name: String, fetch: suspend () -> ByteArray) {
                        pdfMenuOpen = false
                        pdfBusy = true; pdfMessage = null
                        scope.launch {
                            try {
                                pdfMessage = savePdf(name, fetch())
                            } catch (e: Throwable) {
                                pdfMessage = "PDF failed: ${e.message}"
                            } finally {
                                pdfBusy = false
                            }
                        }
                    }

                    val chSuffix = chapter?.let { "-ch$it" } ?: ""
                    DropdownMenuItem(
                        text = { Text("Formatted text (PDF)") },
                        onClick = { download("bible-text.pdf") { api.bibleTextPdf() } },
                    )
                    DropdownMenuItem(
                        text = { Text("Formatted text — 2 columns (PDF)") },
                        onClick = { download("bible-text-2col.pdf") { api.bibleTextPdf(twoColumns = true) } },
                    )
                    DropdownMenuItem(
                        text = { Text("Formatted text — underline unique words (PDF)") },
                        onClick = {
                            download("bible-text-unique-words.pdf") { api.bibleTextPdf(underlineUniqueWords = true) }
                        },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Flashcards (all rounds)") },
                        onClick = { download("flashcards$chSuffix.pdf") { api.flashcardsPdf(chapter) } },
                    )
                    DropdownMenuItem(
                        text = { Text("Chapter-heading flashcards" + (chapter?.let { " (through ch $it)" } ?: "")) },
                        onClick = {
                            download("heading-flashcards${chapter?.let { "-through-ch$it" } ?: ""}.pdf") {
                                api.headingFlashcardsPdf(chapter)
                            }
                        },
                    )
                    HorizontalDivider()
                    Round.entries.forEach { round ->
                        DropdownMenuItem(
                            text = { Text("Practice: ${round.displayName}") },
                            onClick = {
                                download("practice-${round.name.lowercase()}$chSuffix.pdf") {
                                    api.practiceTestPdf(round, chapter)
                                }
                            },
                        )
                    }
                }
            }
        }

        pdfMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        error?.let { Text("Error: $it", color = MaterialTheme.colorScheme.error) }

        when (val list = questions) {
            null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            else -> if (list.isEmpty()) {
                Text(
                    "No approved questions yet" + (chapter?.let { " for Acts $it" } ?: "") +
                        ". Be the first — add one in Contribute!",
                    style = MaterialTheme.typography.bodyLarge,
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(list, key = { it.id }) { q ->
                        QuestionCard(q, onVote = {
                            scope.launch {
                                try {
                                    api.vote(q.id)
                                    reload()
                                } catch (e: Throwable) {
                                    error = e.message
                                }
                            }
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun QuestionCard(q: QuestionDto, onVote: () -> Unit) {
    var revealed by remember(q.id) { mutableStateOf(false) }

    ElevatedCard(Modifier.fillMaxWidth().clickable { revealed = !revealed }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssistChip(
                    onClick = {},
                    label = { Text(q.roundType.displayName, style = MaterialTheme.typography.labelSmall) },
                )
                q.chapter?.let {
                    Spacer(Modifier.weight(1f))
                    Text("Acts $it", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary)
                }
            }
            Text(q.prompt, style = MaterialTheme.typography.bodyLarge)

            if (q.choices.isNotEmpty()) {
                q.choices.forEachIndexed { i, choice ->
                    Text("${'A' + i}. $choice", style = MaterialTheme.typography.bodyMedium)
                }
            }

            if (revealed) {
                Text(
                    q.answer,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (q.references.isNotEmpty()) {
                    Text(q.references.joinToString("; "), style = MaterialTheme.typography.bodySmall)
                }
            } else {
                Text(
                    "Tap to reveal answer",
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.outline,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                q.authorName?.let {
                    Text("by $it", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline)
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onVote) { Text("▲ ${q.votes}") }
            }
        }
    }
}
