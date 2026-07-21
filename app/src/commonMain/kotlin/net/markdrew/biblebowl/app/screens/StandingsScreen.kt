package net.markdrew.biblebowl.app.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import net.markdrew.biblebowl.api.DivisionStandingsDto
import net.markdrew.biblebowl.api.StandingRowDto
import net.markdrew.biblebowl.api.StandingsResponse
import net.markdrew.biblebowl.api.divisionLabel
import net.markdrew.biblebowl.api.ordinal
import net.markdrew.biblebowl.client.TbbApi

/**
 * Standings — the division tally (docs/gui-redesign.md §5F): every division bracket ranked as
 * grading progresses (ungraded rounds count 0). Permission-gated (and server-enforced) on
 * event-wide SCORE_VIEW_ALL, the same graders/admins who run the grading desk; this is a desk
 * tool, not a public results page — the public only ever sees the curated champions page.
 * Compose port of the web app's StandingsScreen.
 */
@Composable
fun StandingsScreen(api: TbbApi, onOpenGrading: () -> Unit) {
    var data by remember { mutableStateOf<StandingsResponse?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            data = api.standings()
        } catch (e: Throwable) {
            error = e.message
        }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Standings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        error?.let { Text("Could not load standings: $it", color = MaterialTheme.colorScheme.error) }

        when (val standings = data) {
            null -> if (error == null) {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            else -> StandingsContent(standings, onOpenGrading)
        }
    }
}

@Composable
private fun StandingsContent(data: StandingsResponse, onOpenGrading: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "Season ${data.seasonYear} · updates as grading progresses",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ReleasedBadge(data.releasedAt)
        }
        OutlinedButton(onClick = onOpenGrading) { Text("Grading desk") }
    }

    if (data.divisions.isEmpty()) {
        Text(
            "No contestants registered yet — nothing to rank.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        data.divisions.forEach { bracket -> bracketItems(bracket) }
    }
}

@Composable
internal fun ReleasedBadge(releasedAt: String?) {
    if (releasedAt != null) {
        Text(
            "Released ${releasedAt.take(10)}",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
    } else {
        Text(
            "Not released",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.bracketItems(bracket: DivisionStandingsDto) {
    item(key = "${bracket.division}-${bracket.inexperienced}") {
        Text(
            divisionLabel(bracket.division, bracket.inexperienced),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
    // A bracket can hold only teams (members individually bracketed lower) — skip then.
    if (bracket.individuals.isNotEmpty()) {
        item { SectionLabel("Individuals") }
        items(bracket.individuals.size) { i ->
            val row = bracket.individuals[i]
            StandingRow(row, subtitle = row.congregationName + (row.teamName?.let { " · $it" } ?: ""))
        }
    }
    if (bracket.teams.isNotEmpty()) {
        item { SectionLabel("Teams") }
        items(bracket.teams.size) { i ->
            val row = bracket.teams[i]
            StandingRow(row, subtitle = row.congregationName)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun StandingRow(row: StandingRowDto, subtitle: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            ordinal(row.rank),
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(end = 12.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(row.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text("${row.points} / ${row.maxPoints}", style = MaterialTheme.typography.bodyMedium)
    }
}
