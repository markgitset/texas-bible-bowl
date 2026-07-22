package net.markdrew.biblebowl.app.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.markdrew.biblebowl.api.ParticipationDto
import net.markdrew.biblebowl.api.PersonWithParticipationsDto
import net.markdrew.biblebowl.api.division
import net.markdrew.biblebowl.app.ui.LocalSeason
import net.markdrew.biblebowl.client.TbbApi

/**
 * Registrar tool: find and merge duplicate people. Global person matching (one `people` row per
 * human across seasons) makes duplicates likelier, and FK-everywhere makes the fix clean: the
 * survivor absorbs the other's participations, scores, tester ids, and claim, and the loser is
 * deleted. Gated on event-wide REGISTRATION_MANAGE and re-checked server-side; refused when the two
 * share a season. Compose port of the web AdminMergePeopleScreen.
 */
@Composable
fun AdminMergePeopleScreen(api: TbbApi) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<PersonWithParticipationsDto>>(emptyList()) }
    var searched by remember { mutableStateOf(false) }
    var keepId by remember { mutableStateOf<String?>(null) }
    var mergeId by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Relaunch-per-keystroke is the debounce; the delay restarts on every query change.
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) {
            results = emptyList()
            searched = false
            return@LaunchedEffect
        }
        delay(300)
        runCatching { api.searchPeople(q) }.onSuccess {
            results = it
            searched = true
            if (results.none { p -> p.person.id == keepId }) keepId = null
            if (results.none { p -> p.person.id == mergeId }) mergeId = null
        }
    }

    val keep = results.firstOrNull { it.person.id == keepId }
    val merge = results.firstOrNull { it.person.id == mergeId }
    val ready = keep != null && merge != null && keep.person.id != merge.person.id

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Merge duplicate people", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "Search for a person by name, then choose which record to keep and which to merge into it. " +
                "The merged record's registrations, scores, and claim move to the kept person, and the " +
                "duplicate is deleted. People registered in the same season can't be merged — fix the " +
                "duplicate registration first.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(query, { query = it }, placeholder = { Text("Search people by name…") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        if (results.isEmpty() && searched) {
            Text("No matching people.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (keep != null && merge != null) {
            Text(
                "Merge \"${merge.person.name}\" into \"${keep.person.name}\" — " +
                    "${merge.person.name}'s registrations and scores move to ${keep.person.name}, " +
                    "then ${merge.person.name} is deleted.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Button(
            enabled = ready && !busy,
            onClick = {
                if (keep == null || merge == null) return@Button
                busy = true
                message = null
                scope.launch {
                    try {
                        val result = api.mergePeople(keep.person.id, merge.person.id)
                        keepId = null
                        mergeId = null
                        results = results.filter { it.person.id != merge.person.id }
                            .map { if (it.person.id == result.person.person.id) result.person else it }
                        isError = false
                        message = "Merged into ${result.person.person.name} — now registered in " +
                            result.person.participations.joinToString(", ") { it.seasonYear } + "."
                    } catch (e: Throwable) {
                        isError = true
                        message = e.message ?: "Merge failed"
                    } finally {
                        busy = false
                    }
                }
            },
        ) { Text("Merge") }
        message?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
                fontWeight = if (isError) FontWeight.Normal else FontWeight.SemiBold)
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(results, key = { it.person.id }) { pwp ->
                PersonMergeCard(
                    pwp,
                    isKeep = pwp.person.id == keepId,
                    isMerge = pwp.person.id == mergeId,
                    onKeep = { keepId = pwp.person.id; if (mergeId == pwp.person.id) mergeId = null },
                    onMerge = { mergeId = pwp.person.id; if (keepId == pwp.person.id) keepId = null },
                )
            }
        }
    }
}

@Composable
private fun PersonMergeCard(
    pwp: PersonWithParticipationsDto,
    isKeep: Boolean,
    isMerge: Boolean,
    onKeep: () -> Unit,
    onMerge: () -> Unit,
) {
    val season = LocalSeason.current
    val person = pwp.person
    ElevatedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Keep", style = MaterialTheme.typography.labelSmall)
                RadioButton(selected = isKeep, onClick = onKeep)
                Text("Merge", style = MaterialTheme.typography.labelSmall)
                RadioButton(selected = isMerge, onClick = onMerge)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(person.name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                    person.division(season)?.let {
                        Text(it.displayName, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    person.birthdate?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (pwp.participations.isEmpty()) {
                    Text("No registrations.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    pwp.participations.forEach {
                        Text(participationLine(it), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

/** "2027 · First Baptist — Contestant · Team Lions · Tester #12" for one participation. */
private fun participationLine(p: ParticipationDto): String {
    val roles = buildList {
        if (p.isContestant) add("Contestant")
        if (p.isCoach) add("Coach")
        addAll(p.positions)
    }.joinToString(", ").ifEmpty { "Attendee" }
    return buildString {
        append("${p.seasonYear} · ${p.congregationName} — $roles")
        p.teamName?.let { append(" · Team $it") }
        p.testerId?.let { append(" · Tester #$it") }
    }
}
