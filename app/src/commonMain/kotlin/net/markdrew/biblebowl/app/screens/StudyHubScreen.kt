package net.markdrew.biblebowl.app.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Study hub — the app's default landing (docs/gui-redesign.md §5C): compact cards, one tap to
 * everything, zero auth. The online reading view joins these cards in a later phase.
 */
@Composable
fun StudyHubScreen(
    onOpenIndices: () -> Unit,
    onOpenQuiz: () -> Unit,
    onOpenDownloads: () -> Unit,
) {
    Column(
        Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "This season: Acts",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            "2026–27 · all 28 chapters (ESV)",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.secondary,
        )

        HubCard(
            title = "Names & numbers indices",
            subtitle = "Every proper name and number in Acts, with all its verses. Search or browse.",
            onClick = onOpenIndices,
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
