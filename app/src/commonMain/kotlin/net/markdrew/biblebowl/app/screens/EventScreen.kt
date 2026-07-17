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
import net.markdrew.biblebowl.app.ui.LocalSeason
import net.markdrew.biblebowl.api.feesNote
import net.markdrew.biblebowl.api.formatCents
import net.markdrew.biblebowl.api.formatIsoDate
import net.markdrew.biblebowl.api.schoolYear

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
        val season = LocalSeason.current
        Text(
            "${season.schoolYear} Season",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            "This season's book is ${season.eventScripture} (ESV)." +
                (season.eventTheme.takeIf { it.isNotBlank() && it != "TBD" }?.let { " Theme: $it." } ?: ""),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            "Event: ${season.eventDateRange}, ${season.eventYear}",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
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
                    "Registration opens ${formatIsoDate(season.registrationOpensOn)}; " +
                        "deadline: ${formatIsoDate(season.registrationClosesOn)}.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text("Contestants: ${formatCents(season.priceContestantCents)} (t-shirt included)",
                    style = MaterialTheme.typography.bodyMedium)
                Text("Volunteers/adults: ${formatCents(season.priceVolunteerCents)}",
                    style = MaterialTheme.typography.bodyMedium)
                Text("Children (3–8): ${formatCents(season.priceChildCents)}",
                    style = MaterialTheme.typography.bodyMedium)
                Text("Extra t-shirts: ${formatCents(season.priceTshirtCents)}",
                    style = MaterialTheme.typography.bodyMedium)
                if (season.feesNote.isNotEmpty()) {
                    Text(season.feesNote, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    "Locations and team registration are on texasbiblebowl.org for now. " +
                        "Registration moves into the app in an upcoming season.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
