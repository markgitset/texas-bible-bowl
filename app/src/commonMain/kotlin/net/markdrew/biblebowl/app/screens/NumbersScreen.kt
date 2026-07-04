package net.markdrew.biblebowl.app.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.markdrew.biblebowl.api.IndexEntryDto
import net.markdrew.biblebowl.app.net.TbbApi
import net.markdrew.biblebowl.app.platform.savePdf

/**
 * The Numbers index: every numeral, cardinal, ordinal, and fraction in the season book with the verses it
 * appears in. Generated deterministically from the ESV text (like R1/R4/R5), with a text filter and a PDF
 * export of the full alphabetical + by-frequency index.
 */
@Composable
fun NumbersScreen(api: TbbApi) {
    var entries by remember { mutableStateOf<List<IndexEntryDto>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf("") }
    var pdfMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        try {
            entries = api.numbersIndex()
        } catch (e: Throwable) {
            error = e.message
        }
    }

    Column(Modifier.fillMaxSize().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                label = { Text("Filter numbers") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = {
                pdfMessage = null
                scope.launch {
                    pdfMessage = try {
                        savePdf("numbers-index.pdf", api.numbersIndexPdf())
                    } catch (e: Throwable) {
                        "PDF failed: ${e.message}"
                    }
                }
            }) { Text("PDF") }
        }
        pdfMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }

        val list = entries
        when {
            error != null -> Text(
                "Couldn't load the numbers index: $error",
                color = MaterialTheme.colorScheme.error,
            )

            list == null -> CircularProgressIndicator()

            else -> {
                val shown = list.filter { filter.isBlank() || it.key.contains(filter.trim(), ignoreCase = true) }
                Text(
                    "${shown.size} of ${list.size} numbers",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(shown, key = { it.key }) { entry -> NumberEntryCard(entry) }
                }
            }
        }
    }
}

@Composable
private fun NumberEntryCard(entry: IndexEntryDto) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(entry.key, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("×${entry.total}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            Text(
                entry.references.joinToString(", ") { r -> r.reference + (if (r.count > 1) " (×${r.count})" else "") },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
