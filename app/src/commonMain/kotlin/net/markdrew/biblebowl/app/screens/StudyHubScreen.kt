package net.markdrew.biblebowl.app.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.markdrew.biblebowl.app.ui.LocalSeason
import net.markdrew.biblebowl.app.ui.schoolYear


/**
 * Study hub — the app's default landing (docs/gui-redesign.md §5C): compact cards, one tap to
 * everything, zero auth. Reading the text links out to dedicated ESV readers rather than an
 * in-app view — better reading tools, and no ESV text on the client.
 */
@Composable
fun StudyHubScreen(
    onOpenIndices: () -> Unit,
    onOpenHeadings: () -> Unit,
    onOpenQuiz: () -> Unit,
    onOpenDownloads: () -> Unit,
) {
    Column(
        Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val season = LocalSeason.current
        Text(
            "This season: ${season.eventScripture}",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            "${season.schoolYear} · all ${season.chapterCount} chapters (ESV)",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.secondary,
        )

        ReadOnlineCard()
        HubCard(
            title = "Names & numbers indices",
            subtitle = "Every proper name and number in ${season.eventScripture}, with all its verses. Search or browse.",
            onClick = onOpenIndices,
        )
        HubCard(
            title = "Chapter headings",
            subtitle = "Browse every ESV section heading (the Round 5 material) or flip to self-check mode.",
            onClick = onOpenHeadings,
        )
        HubCard(
            title = "Quiz yourself",
            subtitle = "Drill the community question bank or chapter headings, with instant feedback.",
            onClick = onOpenQuiz,
        )
        HubCard(
            title = "Download study PDFs",
            subtitle = "The highlighted study text, flashcards, indices, and practice tests.",
            onClick = onOpenDownloads,
        )
    }
}

@Composable
private fun ReadOnlineCard() {
    val uriHandler = LocalUriHandler.current
    val season = LocalSeason.current
    // Good ESV readers to link out to — the app deliberately hosts no reading view (per Mark).
    val readingLinks = listOf(
        "ESV.org" to "https://www.esv.org/${season.eventScripture}+1/",
        "YouVersion" to "https://www.bible.com/bible/59/${season.bookCode}.1.ESV",
        "BibleGateway" to "https://www.biblegateway.com/passage/?search=${season.eventScripture}+1&version=ESV",
    )
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Read ${season.eventScripture} online", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold)
            Text(
                "Read the ESV text in a dedicated Bible app or site:",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                readingLinks.forEach { (name, url) ->
                    AssistChip(onClick = { uriHandler.openUri(url) }, label = { Text(name) })
                }
            }
        }
    }
}

@Composable
private fun HubCard(title: String, subtitle: String, onClick: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
