package net.markdrew.biblebowl.app.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import net.markdrew.biblebowl.api.SeasonDto
import net.markdrew.biblebowl.app.platform.saveFile

/**
 * Shared bits for the admin/event-ops screens (registration desk, counts, testers, housing,
 * tribes): CSV building + saving via the platform [saveFile], the per-site filter picker, and a
 * horizontally scrollable matrix table for the workbook-style count grids.
 */

/** Quote-escapes a CSV field, guarding user-entered text against spreadsheet formula injection. */
internal fun csvField(raw: String): String {
    val guarded = if (raw.firstOrNull() in listOf('=', '+', '-', '@')) "'$raw" else raw
    return "\"" + guarded.replace("\"", "\"\"") + "\""
}

/** Joins pre-escaped lines of fields into CSV text (CRLF rows, every field [csvField]-quoted). */
internal fun csvText(lines: List<List<String>>): String =
    lines.joinToString("\r\n") { line -> line.joinToString(",") { csvField(it) } }

/** Saves CSV in the platform-idiomatic place (BOM so Excel detects UTF-8); returns where it went. */
internal suspend fun saveCsv(fileName: String, csv: String): String =
    saveFile(fileName, "﻿$csv".encodeToByteArray(), "text/csv")

/** "bandina" from "Bandina!" — file-name slug for per-site exports. */
internal fun siteSlug(name: String): String =
    name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

/** The multi-site season's site filter: "All sites" or one [SeasonDto.sites] id. */
@Composable
internal fun SiteFilterPicker(season: SeasonDto, selected: String?, onSelect: (String?) -> Unit) {
    val options: List<Pair<String?, String>> =
        listOf<Pair<String?, String>>(null to "All sites") + season.sites.map { it.id to it.name }
    DropdownPicker(
        options, options.firstOrNull { it.first == selected }, { it.second },
        enabled = true, placeholder = "All sites",
    ) { onSelect(it.first) }
}

@Composable
internal fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp),
    )
}

/**
 * A workbook-style matrix that scrolls horizontally when it outgrows a phone: a sticky-feeling
 * wide first column plus fixed-width value columns. [rows] pairs each row label with its cells;
 * the last row is bolded when [boldLastRow] (totals rows).
 */
@Composable
internal fun ScrollTable(
    headers: List<String>,
    rows: List<Pair<String, List<String>>>,
    boldLastRow: Boolean = false,
    labelWidth: Int = 160,
    cellWidth: Int = 64,
) {
    Column(Modifier.horizontalScroll(rememberScrollState())) {
        Row {
            headers.forEachIndexed { i, header ->
                Text(
                    header,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = if (i == 0) TextAlign.Start else TextAlign.End,
                    modifier = Modifier.width(if (i == 0) labelWidth.dp else cellWidth.dp).padding(2.dp),
                )
            }
        }
        rows.forEachIndexed { rowIndex, (label, cells) ->
            val bold = boldLastRow && rowIndex == rows.lastIndex
            Row {
                Text(
                    label,
                    fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.width(labelWidth.dp).padding(2.dp),
                )
                cells.forEach { cell ->
                    Text(
                        cell,
                        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(cellWidth.dp).padding(2.dp),
                    )
                }
            }
        }
    }
}

/** A "Download CSV"-style button that runs [build], saves, and reports where the file went. */
@Composable
internal fun SaveCsvButton(label: String, fileName: () -> String, build: () -> String) {
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column {
        Box {
            OutlinedButton(enabled = !busy, onClick = {
                busy = true
                status = null
                scope.launch {
                    status = try {
                        saveCsv(fileName(), build())
                    } catch (e: Throwable) {
                        "Save failed: ${e.message}"
                    }
                    busy = false
                }
            }) { Text(label) }
        }
        status?.let {
            Text(it, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
