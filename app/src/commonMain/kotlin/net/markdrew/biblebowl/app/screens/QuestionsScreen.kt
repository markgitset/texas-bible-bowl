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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
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
import net.markdrew.biblebowl.api.Permission
import net.markdrew.biblebowl.api.QuestionDto
import net.markdrew.biblebowl.api.UserDto
import net.markdrew.biblebowl.app.ui.LocalSeason
import net.markdrew.biblebowl.app.net.TbbApi
import net.markdrew.biblebowl.app.ui.ChapterChips

/**
 * Community question bank — browse is public (docs/gui-redesign.md §5D). Voting requires sign-in:
 * an anonymous tap on the vote button routes to contextual sign-in via [onRequireSignIn].
 * Submit/moderate affordances render only for permission holders (§7.8 — never disabled-but-visible).
 */
@Composable
fun QuestionsScreen(
    api: TbbApi,
    user: UserDto?,
    onRequireSignIn: () -> Unit,
    onNewQuestion: () -> Unit,
    onModerate: () -> Unit,
) {
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

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val canSubmit = user != null && Permission.QUESTION_SUBMIT in user.permissions
        val canModerate = user != null && Permission.QUESTION_MODERATE in user.permissions
        if (canSubmit || canModerate) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (canSubmit) {
                    FilledTonalButton(onClick = onNewQuestion) { Text("New question") }
                }
                if (canModerate) {
                    OutlinedButton(onClick = onModerate) { Text("Moderate") }
                }
            }
        }

        // One-tap chapter filter; "All" plus 1..28.
        ChapterChips(selected = chapter, onSelect = { chapter = it })

        error?.let { Text("Error: $it", color = MaterialTheme.colorScheme.error) }

        when (val list = questions) {
            null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            else -> if (list.isEmpty()) {
                Text(
                    "No approved questions yet" + (chapter?.let { " for ${LocalSeason.current.eventScripture} $it" } ?: "") + ".",
                    style = MaterialTheme.typography.bodyLarge,
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(list, key = { it.id }) { q ->
                        QuestionCard(q, onVote = {
                            if (user == null) {
                                onRequireSignIn()
                            } else scope.launch {
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
                    Text("${LocalSeason.current.eventScripture} $it", style = MaterialTheme.typography.labelMedium,
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
