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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.markdrew.biblebowl.model.Round
import net.markdrew.biblebowl.api.SubmitQuestionRequest
import net.markdrew.biblebowl.app.net.TbbApi
import net.markdrew.biblebowl.app.ui.LocalSeason

@Composable
fun ContributeScreen(api: TbbApi) {
    var roundType by remember { mutableStateOf(Round.crowdSourcedRounds.first()) }
    var prompt by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf("") }
    var referencesText by remember { mutableStateOf("") }
    var chapterText by remember { mutableStateOf("") }
    var choicesText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Contribute a question", style = MaterialTheme.typography.titleLarge)
        Text(
            "Fact Finder and Identification are crowd-sourced. Find the Verse, Quotations, and Headings are " +
                "generated from the ESV text, so they aren't submitted here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
        )

        // Round selector — single-tap chips (crowd-sourced rounds only).
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Round.crowdSourcedRounds.forEach { rt ->
                FilterChip(
                    selected = roundType == rt,
                    onClick = { roundType = rt },
                    label = { Text(rt.displayName) },
                )
            }
        }

        OutlinedTextField(
            value = prompt, onValueChange = { prompt = it },
            label = { Text("Question / prompt") },
            modifier = Modifier.fillMaxWidth(), minLines = 2,
        )
        OutlinedTextField(
            value = answer, onValueChange = { answer = it },
            label = { Text(answerLabel(roundType)) },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
        )
        if (roundType.multipleChoice) {
            OutlinedTextField(
                value = choicesText, onValueChange = { choicesText = it },
                label = { Text("Choices (one per line, include the correct answer)") },
                modifier = Modifier.fillMaxWidth(), minLines = 3,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = chapterText, onValueChange = { chapterText = it.filter(Char::isDigit) },
                label = { Text("Chapter") },
                modifier = Modifier.weight(1f), singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            OutlinedTextField(
                value = referencesText, onValueChange = { referencesText = it },
                label = { Text("Verse refs (e.g. ${LocalSeason.current.eventScripture} 2:38)") },
                modifier = Modifier.weight(2f), singleLine = true,
            )
        }

        Button(
            onClick = {
                busy = true; message = null
                scope.launch {
                    try {
                        api.submitQuestion(
                            SubmitQuestionRequest(
                                roundType = roundType,
                                prompt = prompt.trim(),
                                answer = answer.trim(),
                                references = referencesText.split(";", ",").map { it.trim() }.filter { it.isNotEmpty() },
                                choices = choicesText.lines().map { it.trim() }.filter { it.isNotEmpty() },
                                chapter = chapterText.toIntOrNull(),
                            )
                        )
                        isError = false
                        message = "Submitted! Your question is pending review."
                        prompt = ""; answer = ""; referencesText = ""; chapterText = ""; choicesText = ""
                    } catch (e: Throwable) {
                        isError = true
                        message = "Error: ${e.message}"
                    } finally {
                        busy = false
                    }
                }
            },
            enabled = !busy && prompt.isNotBlank() && answer.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (busy) CircularProgressIndicator(Modifier.height(18.dp)) else Text("Submit for review")
        }

        message?.let {
            Text(
                it,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }
    }
}

private fun answerLabel(roundType: Round): String = when (roundType) {
    Round.FIND_THE_VERSE -> "Answer (chapter:verse, e.g. 2:38)"
    Round.QUOTES, Round.EVENTS -> "Answer (chapter number)"
    else -> "Answer"
}
