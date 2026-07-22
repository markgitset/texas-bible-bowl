package net.markdrew.biblebowl.app.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.markdrew.biblebowl.api.RegistrationDeskResponse
import net.markdrew.biblebowl.api.SeasonDto
import net.markdrew.biblebowl.api.TribeDto
import net.markdrew.biblebowl.api.TribesResponse
import net.markdrew.biblebowl.api.UpsertTribeRequest
import net.markdrew.biblebowl.api.multiSite
import net.markdrew.biblebowl.api.siteFor
import net.markdrew.biblebowl.app.ui.LocalSeason
import net.markdrew.biblebowl.client.TbbApi

/**
 * Tribes & tribe leaders (item 16, F10; replaces the workbook's `Tribe leader assignment` tab):
 * a thin admin tool — tribes per site (2026: color names, two leaders each) with free-form leader
 * names. The leader input suggests adults who flagged willingness (item 8: adult guests and
 * individual contestants with `tribeLeaderWilling`, from the desk payload), and a roll-up lists
 * the willing not yet assigned anywhere. Gated like housing (event-wide REGISTRATION_MANAGE).
 * Compose port of the web AdminTribesScreen.
 */
internal class TribesModel(val api: TbbApi, val scope: CoroutineScope) {
    var tribes: TribesResponse? by mutableStateOf(null)
    var desk: RegistrationDeskResponse? by mutableStateOf(null)
    var message: String? by mutableStateOf(null)
    var loadError: String? by mutableStateOf(null)
    var editingTribeId: String? by mutableStateOf(null)

    suspend fun load() {
        try {
            tribes = api.tribes()
            desk = api.registrationDesk()
        } catch (e: Throwable) {
            loadError = e.message ?: "Something went wrong"
        }
    }

    /** Runs a tribe mutation and refreshes from its [TribesResponse]. */
    fun mutate(block: suspend TbbApi.() -> TribesResponse) {
        scope.launch {
            try {
                tribes = api.block()
                message = null
            } catch (e: Throwable) {
                message = e.message ?: "Something went wrong"
            }
        }
    }
}

/** A willing adult (item 8's flags), labeled with their congregation and site. */
private data class WillingLeader(val name: String, val congregation: String, val siteId: String?)

/** Adults who flagged tribe-leader willingness: adult guests and individual contestants. */
private fun willingLeaders(model: TribesModel, season: SeasonDto): List<WillingLeader> =
    model.desk?.rows.orEmpty().flatMap { row ->
        val reg = row.registration ?: return@flatMap emptyList<WillingLeader>()
        val siteId = season.siteFor(reg.siteId)?.id
        reg.guests.filter { it.participation.tribeLeaderWilling }
            .map { WillingLeader(it.person.name, row.congregation.name, siteId) } +
            reg.individuals.filter { it.participation.tribeLeaderWilling }
                .map { WillingLeader(it.person.name, row.congregation.name, siteId) }
    }.sortedBy { it.name.lowercase() }

@Composable
fun AdminTribesScreen(api: TbbApi, onOpenDesk: () -> Unit) {
    val scope = rememberCoroutineScope()
    val model = remember(api) { TribesModel(api, scope) }
    val season = LocalSeason.current

    LaunchedEffect(model) { model.load() }

    Column(
        Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Tribes", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        model.loadError?.let {
            Text("Could not load tribes: $it", color = MaterialTheme.colorScheme.error)
            return@Column
        }
        val t = model.tribes
        if (t == null || model.desk == null) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Column
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onOpenDesk) { Text("Registration desk") }
            if (t.tribes.isNotEmpty()) {
                SaveCsvButton("Tribes CSV", { "tbb-tribes-${t.seasonYear}.csv" }) { tribesCsv(season, t) }
            }
        }
        Text("Season ${t.seasonYear}", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        model.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text(
            "Define the event's tribes (2026 used color names, two leaders each) and assign " +
                "leaders — the name field suggests adults who volunteered on registration, but " +
                "any adult can be typed in.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // One section per event site (multi-site seasons); a lone null-site section otherwise.
        val siteIds: List<String?> = if (season.multiSite) season.sites.map { it.id } else listOf(null)
        siteIds.forEach { siteId ->
            if (season.multiSite) SectionHeader(season.siteFor(siteId)?.name ?: "Unknown site")
            val siteTribes = t.tribes.filter { it.siteId == siteId }
            if (siteTribes.isEmpty()) {
                Text("No tribes yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            siteTribes.forEach { tribe -> TribeCard(model, season, tribe) }
            AddTribeRow(model, siteId)
        }

        WillingPool(model, season, t)
    }
}

@Composable
private fun TribeCard(model: TribesModel, season: SeasonDto, tribe: TribeDto) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (model.editingTribeId == tribe.id) {
                TribeEditor(model, tribe)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tribe.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    // The 2026 convention was two leaders per tribe — a hint, not a rule.
                    if (tribe.leaders.size < 2) {
                        Text("needs leaders", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.SemiBold)
                    }
                    OutlinedButton(onClick = { model.editingTribeId = tribe.id }) { Text("Rename") }
                    OutlinedButton(onClick = { model.mutate { deleteTribe(tribe.id) } }) { Text("Delete") }
                }
            }
            tribe.leaders.forEach { leader ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(leader.name, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { model.mutate { deleteTribeLeader(leader.id) } }) {
                        Text("Remove")
                    }
                }
            }
            AddLeaderRow(model, season, tribe)
        }
    }
}

/** Inline rename editor shown in the tribe card while editing. */
@Composable
private fun TribeEditor(model: TribesModel, tribe: TribeDto) {
    var name by remember(tribe.id) { mutableStateOf(tribe.name) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(name, { name = it }, label = { Text("Name") },
            singleLine = true, modifier = Modifier.weight(1f))
        Button(onClick = {
            model.editingTribeId = null
            model.mutate { updateTribe(tribe.id, UpsertTribeRequest(name, tribe.siteId)) }
        }) { Text("Save") }
        OutlinedButton(onClick = { model.editingTribeId = null }) { Text("Cancel") }
    }
}

@Composable
private fun AddTribeRow(model: TribesModel, siteId: String?) {
    var name by remember(siteId) { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(name, { name = it }, placeholder = { Text("New tribe (e.g. Red, Turquoise…)") },
            singleLine = true, modifier = Modifier.weight(1f))
        Button(enabled = name.isNotBlank(), onClick = {
            model.mutate { addTribe(UpsertTribeRequest(name, siteId)) }
            name = ""
        }) { Text("Add tribe") }
    }
}

/**
 * The add-leader row: a free-text name plus tappable suggestions of the site's willing adults
 * (Compose has no datalist; suggestions filter as the name is typed and disappear once it
 * matches one exactly).
 */
@Composable
private fun AddLeaderRow(model: TribesModel, season: SeasonDto, tribe: TribeDto) {
    var name by remember(tribe.id) { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, placeholder = { Text("Leader's name") },
                singleLine = true, modifier = Modifier.weight(1f))
            Button(enabled = name.isNotBlank(), onClick = {
                model.mutate { addTribeLeader(tribe.id, name) }
                name = ""
            }) { Text("Add leader") }
        }
        // A tribe draws from its own site's willing adults (or all, single-site).
        val suggestions = willingLeaders(model, season)
            .filter { tribe.siteId == null || it.siteId == tribe.siteId }
            .filter { name.isBlank() || it.name.contains(name.trim(), ignoreCase = true) }
            .filter { !it.name.equals(name.trim(), ignoreCase = true) }
            .take(5)
        if (name.isNotBlank() && suggestions.isNotEmpty()) {
            suggestions.forEach { willing ->
                OutlinedButton(onClick = { name = willing.name }) {
                    Text("${willing.name} — ${willing.congregation}")
                }
            }
        }
    }
}

/** Willing adults not yet assigned to any tribe (matched by name) — the recruiting pool. */
@Composable
private fun WillingPool(model: TribesModel, season: SeasonDto, t: TribesResponse) {
    val assigned = t.tribes.flatMap { it.leaders }.map { it.name.trim().lowercase() }.toSet()
    val unassigned = willingLeaders(model, season).filter { it.name.trim().lowercase() !in assigned }
    if (unassigned.isEmpty()) return
    SectionHeader("Willing, not yet assigned (${unassigned.size})")
    unassigned.forEach { willing ->
        val site = willing.siteId?.takeIf { season.multiSite }
            ?.let { id -> season.siteFor(id)?.name }?.let { " ($it)" } ?: ""
        Text("${willing.name} — ${willing.congregation}$site", style = MaterialTheme.typography.bodySmall)
    }
}

/** One CSV row per leader (leaderless tribes still get a row), like the workbook tab. */
private fun tribesCsv(season: SeasonDto, t: TribesResponse): String {
    val multiSite = season.multiSite
    val header = listOfNotNull("Site".takeIf { multiSite }, "Tribe", "Leader")
    val body = t.tribes.flatMap { tribe ->
        val site = season.siteFor(tribe.siteId)?.name ?: ""
        val leaders = tribe.leaders.ifEmpty { listOf(null) }
        leaders.map { leader ->
            listOfNotNull(site.takeIf { multiSite }, tribe.name, leader?.name ?: "")
        }
    }
    return csvText(listOf(header) + body)
}
