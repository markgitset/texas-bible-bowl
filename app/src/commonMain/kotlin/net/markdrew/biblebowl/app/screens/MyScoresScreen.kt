package net.markdrew.biblebowl.app.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import net.markdrew.biblebowl.api.MyScoresResponse
import net.markdrew.biblebowl.api.ScoreRowDto
import net.markdrew.biblebowl.api.divisionLabel
import net.markdrew.biblebowl.api.maxScore
import net.markdrew.biblebowl.api.ordinal
import net.markdrew.biblebowl.api.rounds
import net.markdrew.biblebowl.api.totalPoints
import net.markdrew.biblebowl.client.TbbApi

/**
 * My Scores (docs/gui-redesign.md §5F): the released scores of every contestant the signed-in
 * user owns (claimed entries) or coaches (their congregations' rosters). The server returns
 * nothing until a SCORE_RELEASE holder releases the season, and this screen says so plainly.
 * Compose port of the web app's MyScoresScreen — one card per contestant instead of the wide
 * table, so it reads on a phone.
 */
@Composable
fun MyScoresScreen(api: TbbApi) {
    var data by remember { mutableStateOf<MyScoresResponse?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            data = api.myScores()
        } catch (e: Throwable) {
            error = e.message
        }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("My scores", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        error?.let { Text("Could not load scores: $it", color = MaterialTheme.colorScheme.error) }

        when (val scores = data) {
            null -> if (error == null) {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            else -> ScoresContent(scores)
        }
    }
}

@Composable
private fun ScoresContent(data: MyScoresResponse) {
    if (!data.released) {
        Text(
            "Season ${data.seasonYear} scores haven't been released yet — check back after the event!",
            style = MaterialTheme.typography.bodyLarge,
        )
        return
    }
    if (data.rows.isEmpty()) {
        Text(
            "No contestant entries are linked to your account for season ${data.seasonYear}.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            "Contestants and parents: claim your roster entry with the code from your coach on your " +
                "Account page. Coaches see their whole congregation here automatically.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    Text(
        "Season ${data.seasonYear} · released scores",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(data.rows, key = { it.rosterEntryId }) { row -> ScoreCard(row) }
    }
}

@Composable
private fun ScoreCard(row: ScoreRowDto) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.contestantName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                row.division?.let {
                    Text(
                        divisionLabel(it, row.inexperienced),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                row.congregationName + " · " + (row.teamName ?: "Individual"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(roundsLine(row), style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Total ${row.totalPoints}", fontWeight = FontWeight.SemiBold)
                row.division?.let {
                    Text(
                        " / ${it.maxScore}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            PlacementLines(row)
        }
    }
}

/** One line of per-round scores: eligible rounds show "R1 45" (— when ungraded), others are skipped. */
private fun roundsLine(row: ScoreRowDto): String =
    (row.division?.rounds ?: allRounds).joinToString("   ") { r ->
        "${roundLabel(r)} ${row.scores[r]?.toString() ?: "—"}"
    }

@Composable
private fun PlacementLines(row: ScoreRowDto) {
    val rank = row.rank ?: return
    Text("${ordinal(rank)} of ${row.rankOf}", fontWeight = FontWeight.SemiBold)
    val teamRank = row.teamRank ?: return
    // The team may compete in a higher bracket than the contestant's own.
    val teamDivision = row.teamDivision
    val elevated = teamDivision != null &&
        (teamDivision != row.division || row.teamInexperienced != row.inexperienced)
    val teamLabel =
        if (elevated) "Team (${divisionLabel(teamDivision, row.teamInexperienced)})" else "Team"
    Text(
        "$teamLabel: ${ordinal(teamRank)} of ${row.teamRankOf} · ${row.teamPoints} pts",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
