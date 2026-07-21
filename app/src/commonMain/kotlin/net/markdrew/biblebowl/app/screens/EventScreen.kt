package net.markdrew.biblebowl.app.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.markdrew.biblebowl.model.Round
import net.markdrew.biblebowl.app.ui.LocalSeason
import net.markdrew.biblebowl.api.Permission
import net.markdrew.biblebowl.api.UserDto
import net.markdrew.biblebowl.api.feesNote
import net.markdrew.biblebowl.api.formatCents
import net.markdrew.biblebowl.api.formatIsoDate
import net.markdrew.biblebowl.api.hasEventWidePermission
import net.markdrew.biblebowl.api.isGlobalAdmin
import net.markdrew.biblebowl.api.schoolYear

/**
 * Season/event hub (docs/gui-redesign.md §5F) — public season info plus role-aware cards for the
 * scoring screens (registration docks here next). The scores card follows the season's
 * `gradingEnabled` launch toggle, with the global-admin dark-feature preview the web app has.
 */
@Composable
fun EventScreen(
    user: UserDto?,
    onOpenMyScores: () -> Unit,
    onOpenGrading: () -> Unit,
    onOpenStandings: () -> Unit,
) {
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

        // Scores card — mirrors the web app's gate: the season toggle, or an admin previewing
        // the dark-deployed feature. My Scores is sign-in-only (the route gate handles anon);
        // the desk buttons need event-wide grants.
        val gradingVisible = season.gradingEnabled || (user != null && isGlobalAdmin(user.roles))
        if (gradingVisible) {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Scores", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold)
                    if (!season.gradingEnabled) {
                        Text(
                            "Hidden until launch — visible to you as a global admin.",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    Text(
                        "Released scores for contestants you've claimed or coach.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onOpenMyScores) { Text("My scores") }
                        if (user != null && hasEventWidePermission(user.roles, Permission.SCORE_ENTER)) {
                            OutlinedButton(onClick = onOpenGrading) { Text("Grading") }
                        }
                        if (user != null && hasEventWidePermission(user.roles, Permission.SCORE_VIEW_ALL)) {
                            OutlinedButton(onClick = onOpenStandings) { Text("Standings") }
                        }
                    }
                }
            }
        }

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
