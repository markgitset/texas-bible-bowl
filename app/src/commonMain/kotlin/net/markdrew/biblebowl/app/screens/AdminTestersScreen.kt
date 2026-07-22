package net.markdrew.biblebowl.app.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
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
import net.markdrew.biblebowl.api.TesterListResponse
import net.markdrew.biblebowl.api.TesterRowDto
import net.markdrew.biblebowl.api.divisionLabel
import net.markdrew.biblebowl.api.multiSite
import net.markdrew.biblebowl.api.zipGradeFirstName
import net.markdrew.biblebowl.api.zipGradeLastName
import net.markdrew.biblebowl.app.ui.LocalSeason
import net.markdrew.biblebowl.client.TbbApi

/**
 * Tester IDs + ZipGrade export (registration backlog item 13, F7): every contestant this season
 * with their stable season-wide tester ID, workbook-style external ID, and class (congregation code),
 * plus the per-site ZipGrade import CSV — ZipGrade is still the primary scan-grading path for
 * 2027. Loading the screen is what assigns numbers to new testers (append-only; existing numbers
 * never change, so nametags can print early). Gated (and server-enforced) on event-wide
 * REGISTRATION_MANAGE or SCORE_ENTER. Compose port of the web AdminTestersScreen.
 */
@Composable
fun AdminTestersScreen(api: TbbApi) {
    val season = LocalSeason.current
    var data by remember { mutableStateOf<TesterListResponse?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var siteFilter by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            data = api.adminTesters()
        } catch (e: Throwable) {
            error = e.message
        }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Tester IDs", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        error?.let { Text("Could not load the tester list: $it", color = MaterialTheme.colorScheme.error) }

        val testers = data
        if (testers == null) {
            if (error == null) {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            return@Column
        }
        if (testers.rows.isEmpty()) {
            Text("No contestants registered yet this season.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }

        val rows = testers.rows.filter { siteFilter == null || it.siteId == siteFilter }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (season.multiSite) SiteFilterPicker(season, siteFilter) { siteFilter = it }
            // ZipGrade imports one roster per site, so the export follows the site filter.
            SaveCsvButton(
                "ZipGrade CSV",
                {
                    val site = siteFilter?.let { id -> season.sites.firstOrNull { it.id == id } }
                    "tbb-zipgrade-${testers.seasonYear}-${site?.let { siteSlug(it.name) } ?: "all-sites"}.csv"
                },
            ) { zipGradeCsv(rows) }
        }
        Text(
            "Season ${testers.seasonYear} — ${rows.size} testers.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Warnings(season.multiSite, rows)

        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(rows, key = { it.rosterEntryId }) { row -> TesterRow(season.multiSite, row) }
        }
    }
}

@Composable
private fun TesterRow(multiSite: Boolean, row: TesterRowDto) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.width(48.dp)) {
            if (row.testerId != null) {
                Text(row.testerId.toString(), fontWeight = FontWeight.SemiBold)
            } else {
                Text("—", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(row.name)
            val parts = listOfNotNull(
                row.congregationName,
                row.congregationCode.ifBlank { null }?.let { "class $it" },
                row.division?.let { divisionLabel(it, row.inexperienced) },
                row.teamName,
                row.siteName.ifBlank { null }.takeIf { multiSite },
            )
            Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(row.externalId ?: "—", style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold)
    }
}

/** Data problems a registrar should fix before exporting: missing site pins or class codes. */
@Composable
private fun Warnings(multiSite: Boolean, rows: List<TesterRowDto>) {
    val unpinned = if (multiSite) rows.count { it.siteId == null } else 0
    if (unpinned > 0) {
        Text(
            "$unpinned tester(s) have no event site — ZipGrade rosters and nametag sheets " +
                "are per site. Pin the site on the registration desk.",
            color = MaterialTheme.colorScheme.error,
        )
    }
    val codeless = rows.filter { it.congregationCode.isBlank() }
        .map { it.congregationName }.distinct()
    if (codeless.isNotEmpty()) {
        Text(
            "No two-letter code yet for ${codeless.joinToString()} — external IDs show ?? " +
                "until one is set on the registration desk.",
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/**
 * The ZipGrade student-import sheet, exactly the 2026 workbook's ZipGrade-tab columns. Rows
 * without a tester ID (only possible against an old server) are left out — they aren't importable.
 */
private fun zipGradeCsv(rows: List<TesterRowDto>): String {
    val header = listOf("Zip Grade ID", "external id", "First Name", "Last Name", "Class")
    val body = rows.filter { it.testerId != null }.map { row ->
        listOf(
            row.testerId.toString(),
            row.externalId ?: "",
            zipGradeFirstName(row.name),
            zipGradeLastName(row.name),
            row.congregationCode,
        )
    }
    return csvText(listOf(header) + body)
}
