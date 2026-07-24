package net.markdrew.biblebowl.app.screens

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.markdrew.biblebowl.api.GradingSheetResponse
import net.markdrew.biblebowl.api.Permission
import net.markdrew.biblebowl.api.ScoreEntryDto
import net.markdrew.biblebowl.api.ScoreRowDto
import net.markdrew.biblebowl.api.UserDto
import net.markdrew.biblebowl.api.divisionLabel
import net.markdrew.biblebowl.api.hasEventWidePermission
import net.markdrew.biblebowl.api.rounds
import net.markdrew.biblebowl.api.totalPoints
import net.markdrew.biblebowl.client.TbbApi
import net.markdrew.biblebowl.model.Round

/**
 * Grading desk (docs/gui-redesign.md §5F): pick a round, enter every contestant's points, save
 * the batch, and — for SCORE_RELEASE holders — flip the season's release switch. Permission-gated
 * (and server-enforced) on an event-wide SCORE_ENTER grant. Ineligible cells (Power Round for
 * Elementary contestants) render as dashes, mirroring the server's validation. Compose port of
 * the web app's GradingScreen: the round picker is a chip row and each contestant is a list row
 * with one score field.
 */
@Composable
fun GradingScreen(api: TbbApi, user: UserDto?, onOpenStandings: () -> Unit) {
    var sheet by remember { mutableStateOf<GradingSheetResponse?>(null) }
    var round by remember { mutableStateOf(allRounds.first()) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    // Sort the grid by tester ID (the order the hand-graded paper stack is in) rather than desk order.
    var sortByTesterId by remember { mutableStateOf(false) }
    var jumpText by remember { mutableStateOf("") }
    // Narrows the grid to one event site (a multi-site season); null = all sites.
    var siteFilter by remember { mutableStateOf<String?>(null) }
    // Unsaved edits keyed by (rosterEntryId, round), so switching rounds keeps pending cells.
    val cells = remember { mutableStateMapOf<Pair<String, Round>, String>() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    fun report(text: String, isError: Boolean) {
        message = text
        messageIsError = isError
    }

    // Reloads whenever the site filter changes; the server scopes the rows.
    LaunchedEffect(siteFilter) {
        sheet = null
        try {
            sheet = api.gradingSheet(siteFilter)
        } catch (e: Throwable) {
            report("Could not load the grading sheet: ${e.message}", isError = true)
        }
    }

    /** Collects the changed cells of the selected round and batch-saves them. */
    fun saveRound(data: GradingSheetResponse) {
        val edits = mutableListOf<ScoreEntryDto>()
        for (row in data.rows) {
            val text = cells[row.rosterEntryId to round]?.trim() ?: continue
            val existing = row.scores[round]
            if (text.isEmpty()) {
                if (existing != null) edits += ScoreEntryDto(row.rosterEntryId, round, null)
                continue
            }
            val points = text.toIntOrNull()
            if (points == null || points !in 0..round.maxPoints) {
                report("Scores must be whole numbers from 0 to ${round.maxPoints}.", isError = true)
                return
            }
            if (points != existing) edits += ScoreEntryDto(row.rosterEntryId, round, points)
        }
        if (edits.isEmpty()) {
            report("Nothing to save — no ${roundLabel(round)} cells changed.", isError = false)
            return
        }
        saving = true
        scope.launch {
            try {
                sheet = api.saveScores(edits)
                cells.keys.filter { it.second == round }.forEach { cells.remove(it) }
                report("Saved ${edits.size} ${roundLabel(round)} score(s).", isError = false)
            } catch (e: Throwable) {
                report("Save failed: ${e.message}", isError = true)
            }
            saving = false
        }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Grading", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        val data = sheet
        if (data == null) {
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (message == null) {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            return@Column
        }

        ReleaseBar(
            data = data,
            user = user,
            onOpenStandings = onOpenStandings,
            onReleaseChange = { siteId, releasing ->
                scope.launch {
                    try {
                        sheet = api.setScoresReleased(releasing, siteId.ifEmpty { null })
                        report(if (releasing) "Scores released." else "Release retracted.", isError = false)
                    } catch (e: Throwable) {
                        report("Could not update the release: ${e.message}", isError = true)
                    }
                }
            },
        )
        message?.let {
            Text(
                it,
                color = if (messageIsError) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
        }

        if (data.rows.isEmpty()) {
            Text(
                "No contestants registered for season ${data.seasonYear} yet.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            allRounds.forEach { r ->
                FilterChip(
                    selected = r == round,
                    onClick = {
                        round = r
                        message = null
                    },
                    label = { Text(roundLabel(r)) },
                )
            }
        }
        Text(
            "${round.displayName} · max ${round.maxPoints} points · " +
                "season ${data.seasonYear} · ${data.rows.size} contestants",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val rows = if (sortByTesterId) {
            data.rows.sortedWith(compareBy({ it.testerId == null }, { it.testerId }))
        } else {
            data.rows
        }
        // Site filter — only meaningful in a multi-site season; graders scope to their stack.
        if (data.releases.any { it.siteId.isNotEmpty() }) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilterChip(
                    selected = siteFilter == null,
                    onClick = { siteFilter = null },
                    label = { Text("All sites") },
                )
                data.releases.forEach { site ->
                    FilterChip(
                        selected = siteFilter == site.siteId,
                        onClick = { siteFilter = site.siteId },
                        label = { Text(site.siteName.ifBlank { site.siteId }) },
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = !sortByTesterId,
                onClick = { sortByTesterId = false },
                label = { Text("Desk order") },
            )
            FilterChip(
                selected = sortByTesterId,
                onClick = { sortByTesterId = true },
                label = { Text("Tester ID") },
            )
            OutlinedTextField(
                value = jumpText,
                onValueChange = { jumpText = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("Jump to ID") },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Go,
                ),
                // The grader types the ID off the paper in front of them and the list scrolls to it.
                keyboardActions = KeyboardActions(onGo = {
                    val target = jumpText.trim().toIntOrNull()
                    val index = rows.indexOfFirst { it.testerId == target }
                    if (index >= 0) scope.launch { listState.animateScrollToItem(index) }
                    else if (target != null) report("No tester #$target on this sheet.", isError = true)
                }),
            )
        }

        LazyColumn(
            Modifier.weight(1f),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(rows, key = { it.rosterEntryId }) { row ->
                GradingRow(
                    row = row,
                    round = round,
                    value = cells[row.rosterEntryId to round] ?: row.scores[round]?.toString() ?: "",
                    onValueChange = { cells[row.rosterEntryId to round] = it },
                )
            }
        }

        Button(onClick = { saveRound(data) }, enabled = !saving) {
            Text("Save ${roundLabel(round)} scores")
        }
    }
}

@Composable
private fun GradingRow(row: ScoreRowDto, round: Round, value: String, onValueChange: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // Tester ID (the number on the paper), with the ZipGrade external ID beneath it.
        Column(Modifier.width(64.dp)) {
            Text(
                row.testerId?.let { "#$it" } ?: "—",
                fontWeight = FontWeight.SemiBold,
                color = if (row.testerId == null) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
            )
            row.externalId?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(row.contestantName, fontWeight = FontWeight.SemiBold)
            Text(
                row.congregationName + " · " + (row.teamName ?: "Individual"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                divisionLine(row) + " · total " + (if (row.scores.isEmpty()) "—" else row.totalPoints.toString()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (row.takes(round)) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.width(96.dp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        } else {
            // This division doesn't take the selected round (e.g. Power for Elementary).
            Box(Modifier.width(96.dp), contentAlignment = Alignment.Center) {
                Text("—", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** The contestant's own bracket, plus the team's when the team competes above it. */
private fun divisionLine(row: ScoreRowDto): String {
    val own = row.division?.let { divisionLabel(it, row.inexperienced) } ?: "Unknown"
    val teamDivision = row.teamDivision
    val elevated = teamDivision != null &&
        (teamDivision != row.division || row.teamInexperienced != row.inexperienced)
    return if (elevated) "$own · Team: ${divisionLabel(teamDivision, row.teamInexperienced)}" else own
}

/** Whether this contestant takes [r] — unknown divisions grade everything (server allows it). */
private fun ScoreRowDto.takes(r: Round): Boolean = division?.let { r in it.rounds } ?: true

/**
 * Per-site release state plus — for SCORE_RELEASE holders — a release/retract switch per site
 * (each site releases on its own clock). A site-less season shows one unlabelled row.
 */
@Composable
private fun ReleaseBar(
    data: GradingSheetResponse,
    user: UserDto?,
    onOpenStandings: () -> Unit,
    onReleaseChange: (siteId: String, releasing: Boolean) -> Unit,
) {
    val canRelease = user != null && hasEventWidePermission(user.roles, Permission.SCORE_RELEASE)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        data.releases.forEach { site ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    if (site.siteName.isNotBlank()) {
                        Text(site.siteName, fontWeight = FontWeight.SemiBold)
                    }
                    ReleasedBadge(site.releasedAt)
                }
                if (canRelease) {
                    val releasing = site.releasedAt == null
                    OutlinedButton(onClick = { onReleaseChange(site.siteId, releasing) }) {
                        Text(if (releasing) "Release" else "Retract")
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Each site's families see scores once that site is released.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (user != null && hasEventWidePermission(user.roles, Permission.SCORE_VIEW_ALL)) {
                OutlinedButton(onClick = onOpenStandings) { Text("Standings") }
            }
        }
    }
}
