package net.markdrew.biblebowl.app.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.markdrew.biblebowl.model.Round

/**
 * Season/event hub (docs/gui-redesign.md §5F) — public season info now; registration, grading,
 * and My Scores dock here as role-aware cards in later phases. Dates/fees go live once the
 * seasons endpoint exists (Phase 3); until then this points at texasbiblebowl.org.
 */
@Composable
fun EventScreen() {
    Column(
        Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "2026–27 Season",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            "This season's book is Acts (ESV).",
            style = MaterialTheme.typography.bodyLarge,
        )

        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Competition rounds", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
                Round.entries.forEach { round ->
                    Text(
                        "R${round.number} · ${round.displayName} — ${round.questions} questions, " +
                            "${round.minutes} min" + (if (round.openBible) ", open Bible" else ""),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Dates, fees & registration", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
                Text(
                    "Event dates, locations, fees, and team registration are on texasbiblebowl.org " +
                        "for now. Registration moves into the app in an upcoming season.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
