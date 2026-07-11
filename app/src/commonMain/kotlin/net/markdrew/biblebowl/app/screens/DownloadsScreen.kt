package net.markdrew.biblebowl.app.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.markdrew.biblebowl.model.Round
import net.markdrew.biblebowl.app.net.TbbApi
import net.markdrew.biblebowl.app.platform.savePdf

/**
 * Download center (docs/gui-redesign.md §5B): the 11-item dropdown becomes one scrolling page of
 * preset cards in groups — each card is one click to a sensible default. Public. The chapter chip
 * row scopes the chapter-aware generators (flashcards, practice tests); "Customize" sheets and
 * Kahoot/Quizlet exports arrive with the full download center in a later phase.
 */
@Composable
fun DownloadsScreen(api: TbbApi) {
    var chapter by remember { mutableStateOf<Int?>(null) }
    var busyCard by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun download(cardTitle: String, fileName: String, fetch: suspend () -> ByteArray) {
        if (busyCard != null) return
        busyCard = cardTitle; message = null
        scope.launch {
            try {
                message = savePdf(fileName, fetch())
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
            "Chapter scope (flashcards & practice tests)",
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
        )
        DownloadCard(
            title = "Study text — two columns",
            subtitle = "Same text and highlighting, denser two-column layout for printing.",
            busyCard = busyCard,
            onClick = {
                download("Study text — two columns", "bible-text-2col.pdf") { api.bibleTextPdf(twoColumns = true) }
            },
        )
        DownloadCard(
            title = "Study text — unique words underlined",
            subtitle = "Adds an underline to every word that appears exactly once in Acts.",
            busyCard = busyCard,
            onClick = {
                download("Study text — unique words underlined", "bible-text-unique-words.pdf") {
                    api.bibleTextPdf(underlineUniqueWords = true)
                }
            },
        )

        GroupHeader("Flashcards")
        DownloadCard(
            title = "Question flashcards",
            subtitle = "Duplex deck built from the approved community questions." + scopeNote(chapter),
            busyCard = busyCard,
            onClick = { download("Question flashcards", "flashcards$chSuffix.pdf") { api.flashcardsPdf(chapter) } },
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
            )
        }
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
private fun DownloadCard(title: String, subtitle: String, busyCard: String?, onClick: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilledTonalButton(onClick = onClick, enabled = busyCard == null) {
                if (busyCard == title) CircularProgressIndicator(Modifier.height(16.dp))
                else Text("Download PDF")
            }
        }
    }
}
