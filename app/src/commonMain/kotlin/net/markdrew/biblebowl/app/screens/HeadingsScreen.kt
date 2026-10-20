package net.markdrew.biblebowl.app.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.markdrew.biblebowl.api.HeadingDto
import net.markdrew.biblebowl.api.resolvedStudySet
import net.markdrew.biblebowl.app.ui.LocalSeason
import net.markdrew.biblebowl.client.TbbApi
import net.markdrew.biblebowl.generation.quiz.headingChapterLabel

/**
 * Headings browser (docs/gui-redesign.md §5C) — the Round 5 material as a browsable list. Testing
 * yourself on it is the Quiz tab's job (Chapter headings source), so there's no self-check mode
 * here. Public.
 */
@Composable
fun HeadingsScreen(api: TbbApi) {
    var headings by remember { mutableStateOf<List<HeadingDto>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val multiBook = !LocalSeason.current.resolvedStudySet.isSingleBook

    LaunchedEffect(Unit) {
        try {
            headings = api.headings()
        } catch (e: Throwable) {
            error = e.message
        }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Chapter headings",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "Every ESV section heading in ${LocalSeason.current.eventScripture} — the Round 5 material.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when {
            error != null -> Text("Couldn't load headings: $error", color = MaterialTheme.colorScheme.error)
            headings == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(headings.orEmpty(), key = { it.index }) { heading ->
                    HeadingCard(heading, multiBook)
                }
            }
        }
    }
}

@Composable
private fun HeadingCard(heading: HeadingDto, multiBook: Boolean) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(heading.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${headingChapterLabel(heading.bookCode, heading.chapter, multiBook)} · ${heading.reference}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "${heading.index} of ${heading.total}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}
