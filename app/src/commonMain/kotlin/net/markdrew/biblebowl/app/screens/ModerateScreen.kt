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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.markdrew.biblebowl.api.QuestionDto
import net.markdrew.biblebowl.api.QuestionStatus
import net.markdrew.biblebowl.client.TbbApi
import net.markdrew.biblebowl.app.ui.LocalSeason

@Composable
fun ModerateScreen(api: TbbApi) {
    var pending by remember { mutableStateOf<List<QuestionDto>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun reload() {
        error = null
        try {
            pending = api.questions(status = QuestionStatus.PENDING)
        } catch (e: Throwable) {
            error = e.message
        }
    }

    LaunchedEffect(Unit) { reload() }

    fun act(q: QuestionDto, status: QuestionStatus) {
        scope.launch {
            try {
                api.moderate(q.id, status)
                reload()
            } catch (e: Throwable) {
                error = e.message
            }
        }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Pending review", style = MaterialTheme.typography.titleLarge)
        error?.let { Text("Error: $it", color = MaterialTheme.colorScheme.error) }

        when (val list = pending) {
            null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            else -> if (list.isEmpty()) {
                Text("Queue is clear — nothing pending. 🎉", style = MaterialTheme.typography.bodyLarge)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(list, key = { it.id }) { q ->
                        ElevatedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    AssistChip(
                                        onClick = {},
                                        label = {
                                            Text(q.roundType.displayName,
                                                style = MaterialTheme.typography.labelSmall)
                                        },
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
                                Text(
                                    "Answer: ${q.answer}",
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                if (q.references.isNotEmpty()) {
                                    Text(q.references.joinToString("; "),
                                        style = MaterialTheme.typography.bodySmall)
                                }
                                q.authorName?.let {
                                    Text("by $it", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = { act(q, QuestionStatus.APPROVED) },
                                        modifier = Modifier.weight(1f),
                                    ) { Text("Approve") }
                                    OutlinedButton(
                                        onClick = { act(q, QuestionStatus.REJECTED) },
                                        modifier = Modifier.weight(1f),
                                    ) { Text("Reject") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
