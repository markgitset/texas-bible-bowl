package net.markdrew.biblebowl.app.screens

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.markdrew.biblebowl.api.RoundType
import net.markdrew.biblebowl.app.net.TbbApi
import net.markdrew.biblebowl.generation.quiz.QuizEngine

private const val ACTS_CHAPTERS = 28

/** Quiz mode: setup → stepper with instant feedback → results. All quiz logic lives in QuizEngine. */
@Composable
fun QuizScreen(api: TbbApi) {
    var engine by remember { mutableStateOf<QuizEngine?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var round by remember { mutableStateOf<RoundType?>(null) }
    var chapter by remember { mutableStateOf<Int?>(null) }
    // Bumped on every answer/advance so Compose re-reads the engine's state.
    var tick by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    val quiz = engine
    when {
        loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

        quiz == null -> QuizSetup(
            round = round, onRound = { round = it },
            chapter = chapter, onChapter = { chapter = it },
            error = error,
            onStart = {
                loading = true; error = null
                scope.launch {
                    try {
                        val pool = api.questions(chapter = chapter)
                            .filter { round == null || it.roundType == round }
                        val e = QuizEngine(pool)
                        if (e.isEmpty) error = "No approved questions match — loosen the filters or contribute some!"
                        else engine = e
                    } catch (t: Throwable) {
                        error = t.message
                    } finally {
                        loading = false
                    }
                }
            },
        )

        quiz.isFinished -> QuizResults(quiz, onRestart = { engine = null })

        else -> QuizStepper(quiz, tick, onTick = { tick++ })
    }
}

@Composable
private fun QuizSetup(
    round: RoundType?, onRound: (RoundType?) -> Unit,
    chapter: Int?, onChapter: (Int?) -> Unit,
    error: String?,
    onStart: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Quiz me", style = MaterialTheme.typography.titleLarge)
        Text("Round", style = MaterialTheme.typography.labelLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = round == null, onClick = { onRound(null) }, label = { Text("All") })
            RoundType.entries.forEach { rt ->
                FilterChip(
                    selected = round == rt,
                    onClick = { onRound(if (round == rt) null else rt) },
                    label = { Text(rt.displayName) },
                )
            }
        }
        Text("Chapter", style = MaterialTheme.typography.labelLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = chapter == null, onClick = { onChapter(null) }, label = { Text("All") })
            (1..ACTS_CHAPTERS).forEach { ch ->
                FilterChip(
                    selected = chapter == ch,
                    onClick = { onChapter(if (chapter == ch) null else ch) },
                    label = { Text("$ch") },
                )
            }
        }
        Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text("Start quiz") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun QuizStepper(quiz: QuizEngine, tick: Int, onTick: () -> Unit) {
    // tick is read so answering/advancing recomposes this screen.
    @Suppress("UNUSED_EXPRESSION") tick
    val item = quiz.current ?: return
    var picked by remember(item) { mutableStateOf<Int?>(null) }
    var revealed by remember(item) { mutableStateOf(false) }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        LinearProgressIndicator(
            progress = { (quiz.position - 1).toFloat() / quiz.total },
            modifier = Modifier.fillMaxWidth(),
        )
        Row {
            Text("${quiz.position} of ${quiz.total}", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.weight(1f))
            Text("Score: ${quiz.scoreSoFar}", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.secondary)
        }

        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(item.question.roundType.displayName, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary)
                Text(item.question.prompt, style = MaterialTheme.typography.titleMedium)
            }
        }

        if (item.isMultipleChoice) {
            item.choices.forEachIndexed { i, choice ->
                val answered = picked != null
                val isCorrect = i == item.correctIndex
                val colors = when {
                    !answered -> MaterialTheme.colorScheme.surface
                    isCorrect -> MaterialTheme.colorScheme.primaryContainer
                    i == picked -> MaterialTheme.colorScheme.errorContainer
                    else -> MaterialTheme.colorScheme.surface
                }
                OutlinedButton(
                    onClick = {
                        if (picked == null) {
                            picked = i
                            quiz.answerChoice(i)
                            onTick()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(containerColor = colors),
                ) {
                    Text("${'A' + i}. $choice", modifier = Modifier.fillMaxWidth())
                }
            }
            if (picked != null) {
                Button(onClick = { quiz.next(); onTick() }, modifier = Modifier.fillMaxWidth()) { Text("Next") }
            }
        } else {
            if (!revealed) {
                Button(onClick = { revealed = true }, modifier = Modifier.fillMaxWidth()) { Text("Reveal answer") }
            } else {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(item.question.answer, style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                        if (item.question.references.isNotEmpty()) {
                            Text(item.question.references.joinToString("; "),
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { quiz.answerSelf(true); quiz.next(); onTick() },
                        modifier = Modifier.weight(1f),
                    ) { Text("I got it") }
                    OutlinedButton(
                        onClick = { quiz.answerSelf(false); quiz.next(); onTick() },
                        modifier = Modifier.weight(1f),
                    ) { Text("I missed it") }
                }
            }
        }
    }
}

@Composable
private fun QuizResults(quiz: QuizEngine, onRestart: () -> Unit) {
    val result = quiz.result()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(12.dp))
        Text("Quiz complete!", style = MaterialTheme.typography.headlineSmall)
        Text(
            "${result.score} / ${result.total}",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        if (result.missed.isNotEmpty()) {
            Text("Review these:", style = MaterialTheme.typography.titleMedium)
            result.missed.forEach { item ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(item.question.prompt, style = MaterialTheme.typography.bodyLarge)
                        Text(item.question.answer, fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary)
                        if (item.question.references.isNotEmpty()) {
                            Text(item.question.references.joinToString("; "),
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        Button(onClick = onRestart, modifier = Modifier.fillMaxWidth()) { Text("New quiz") }
        Spacer(Modifier.height(12.dp))
    }
}
