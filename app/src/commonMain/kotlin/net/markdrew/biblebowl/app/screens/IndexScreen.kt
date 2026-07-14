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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.markdrew.biblebowl.api.IndexEntryDto
import net.markdrew.biblebowl.client.TbbApi
import net.markdrew.biblebowl.app.platform.saveFile

/** The two study indices, each generated from the ESV text (word lists + curated overrides). */
private enum class IndexKind(val label: String, val pdfName: String) {
    NUMBERS("Numbers", "numbers-index.pdf"),
    NAMES("Names", "names-index.pdf"),
}

/**
 * Study indices: every number or proper name in the season book with the verses it occurs in. A segmented
 * toggle switches between the two; each has a text filter and a PDF export (alphabetical + by-frequency).
 */
@Composable
fun IndexScreen(api: TbbApi) {
    var kind by remember { mutableStateOf(IndexKind.NUMBERS) }
    var entries by remember { mutableStateOf<List<IndexEntryDto>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf("") }
    var pdfMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(kind) {
        entries = null; error = null
        try {
            entries = when (kind) {
                IndexKind.NUMBERS -> api.numbersIndex()
                IndexKind.NAMES -> api.namesIndex()
            }
        } catch (e: Throwable) {
            error = e.message
        }
    }

    Column(Modifier.fillMaxSize().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            IndexKind.entries.forEach { k ->
                FilterChip(selected = kind == k, onClick = { if (kind != k) { kind = k; filter = ""; pdfMessage = null } }, label = { Text(k.label) })
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                label = { Text("Filter ${kind.label.lowercase()}") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = {
                pdfMessage = null
                scope.launch {
                    pdfMessage = try {
                        saveFile(kind.pdfName, if (kind == IndexKind.NUMBERS) api.numbersIndexPdf() else api.namesIndexPdf())
                    } catch (e: Throwable) {
                        "PDF failed: ${e.message}"
                    }
                }
            }) { Text("PDF") }
        }
        pdfMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }

        val list = entries
        when {
            error != null -> Text("Couldn't load the ${kind.label.lowercase()} index: $error", color = MaterialTheme.colorScheme.error)
            list == null -> CircularProgressIndicator()
            else -> {
                val shown = list.filter { filter.isBlank() || it.key.contains(filter.trim(), ignoreCase = true) }
                Text(
                    "${shown.size} of ${list.size} ${kind.label.lowercase()}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(shown, key = { it.key }) { entry -> IndexEntryCard(entry) }
                }
            }
        }
    }
}

@Composable
private fun IndexEntryCard(entry: IndexEntryDto) {
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
