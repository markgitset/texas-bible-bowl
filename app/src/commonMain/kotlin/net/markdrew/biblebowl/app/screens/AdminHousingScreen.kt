package net.markdrew.biblebowl.app.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import net.markdrew.biblebowl.api.AddCabinAssignmentRequest
import net.markdrew.biblebowl.api.AttendeeRow
import net.markdrew.biblebowl.api.CabinAssignmentDto
import net.markdrew.biblebowl.api.CabinDto
import net.markdrew.biblebowl.api.Gender
import net.markdrew.biblebowl.api.HousingResponse
import net.markdrew.biblebowl.api.RegistrationDeskResponse
import net.markdrew.biblebowl.api.SeasonDto
import net.markdrew.biblebowl.api.UpsertCabinRequest
import net.markdrew.biblebowl.api.deskAttendees
import net.markdrew.biblebowl.api.multiSite
import net.markdrew.biblebowl.api.siteFor
import net.markdrew.biblebowl.app.ui.LocalSeason
import net.markdrew.biblebowl.client.TbbApi

/**
 * Housing / cabin assignments (item 15, F9; replaces the workbook's `Housing Assignments` and
 * `Check out assignments` tabs): a thin, free-form assignment grid — cabins per site, each with
 * congregation × gender group rows (the 2026 pattern) and ad-hoc label rows for families/staff —
 * plus the per-congregation check-out duty roster. No optimizer: occupant counts are derived from
 * the registration desk payload purely as an eyeball check. Gated like the desk (event-wide
 * REGISTRATION_MANAGE). Compose port of the web AdminHousingScreen.
 */
internal class HousingModel(val api: TbbApi, val scope: CoroutineScope) {
    var housing: HousingResponse? by mutableStateOf(null)
    var desk: RegistrationDeskResponse? by mutableStateOf(null)
    var message: String? by mutableStateOf(null)
    var loadError: String? by mutableStateOf(null)
    var editingCabinId: String? by mutableStateOf(null)

    suspend fun load() {
        try {
            housing = api.housing()
            desk = api.registrationDesk()
        } catch (e: Throwable) {
            loadError = e.message ?: "Something went wrong"
        }
    }

    /** Runs a housing mutation and refreshes from its [HousingResponse]. */
    fun mutate(block: suspend TbbApi.() -> HousingResponse) {
        scope.launch {
            try {
                housing = api.block()
                message = null
            } catch (e: Throwable) {
                message = e.message ?: "Something went wrong"
            }
        }
    }
}

@Composable
fun AdminHousingScreen(api: TbbApi, onOpenDesk: () -> Unit) {
    val scope = rememberCoroutineScope()
    val model = remember(api) { HousingModel(api, scope) }
    val season = LocalSeason.current

    LaunchedEffect(model) { model.load() }

    Column(
        Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Housing", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        model.loadError?.let {
            Text("Could not load housing: $it", color = MaterialTheme.colorScheme.error)
            return@Column
        }
        val h = model.housing
        val d = model.desk
        if (h == null || d == null) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Column
        }
        val attendees = deskAttendees(season, d.rows)

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onOpenDesk) { Text("Registration desk") }
            if (h.cabins.isNotEmpty()) {
                SaveCsvButton("Housing CSV", { "tbb-housing-${h.seasonYear}.csv" }) {
                    housingCsv(season, h, attendees)
                }
            }
        }
        Text("Season ${h.seasonYear}", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        model.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text(
            "Assign each congregation's boys and girls (or the whole congregation) to cabins, and " +
                "add ad-hoc rows for families and staff. Counts come from the registration desk — " +
                "they're a sanity check, not a rule.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // One section per event site (multi-site seasons); a lone null-site section otherwise.
        val siteIds: List<String?> = if (season.multiSite) season.sites.map { it.id } else listOf(null)
        siteIds.forEach { siteId ->
            if (season.multiSite) SectionHeader(season.siteFor(siteId)?.name ?: "Unknown site")
            val cabins = h.cabins.filter { it.siteId == siteId }
            if (cabins.isEmpty()) {
                Text("No cabins yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            cabins.forEach { cabin -> CabinCard(model, season, cabin, attendees) }
            AddCabinRow(model, siteId)
        }

        Unhoused(h, d, attendees)
        Duties(model, h, d)
    }
}

@Composable
private fun CabinCard(model: HousingModel, season: SeasonDto, cabin: CabinDto, attendees: List<AttendeeRow>) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (model.editingCabinId == cabin.id) {
                CabinEditor(model, cabin)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(cabin.name, fontWeight = FontWeight.SemiBold)
                        val occupants = cabin.assignments.sumOf { occupantCount(it, attendees) ?: 0 }
                        val capacity = cabin.capacity?.let { " of $it beds" } ?: ""
                        val over = cabin.capacity != null && occupants > cabin.capacity!!
                        Text(
                            "$occupants assigned$capacity" + (if (over) " — over capacity" else ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (over) MaterialTheme.colorScheme.tertiary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(onClick = { model.editingCabinId = cabin.id }) { Text("Edit") }
                    OutlinedButton(onClick = { model.mutate { deleteCabin(cabin.id) } }) { Text("Delete") }
                }
            }
            cabin.assignments.forEach { a ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        assignmentLabel(a) +
                            (occupantCount(a, attendees)?.let { "  ($it)" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = { model.mutate { deleteCabinAssignment(a.id) } }) {
                        Text("Remove")
                    }
                }
            }
            AddAssignmentRow(model, season, cabin)
        }
    }
}

/** Inline name/capacity editor shown in the cabin card while editing. */
@Composable
private fun CabinEditor(model: HousingModel, cabin: CabinDto) {
    var name by remember(cabin.id) { mutableStateOf(cabin.name) }
    var capacity by remember(cabin.id) { mutableStateOf(cabin.capacity?.toString() ?: "") }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(name, { name = it }, label = { Text("Name") },
            singleLine = true, modifier = Modifier.weight(1f))
        OutlinedTextField(capacity, { capacity = it }, label = { Text("Beds") },
            singleLine = true, modifier = Modifier.width(80.dp))
        Button(onClick = {
            model.editingCabinId = null
            model.mutate { updateCabin(cabin.id, UpsertCabinRequest(name, cabin.siteId, capacity.toIntOrNull())) }
        }) { Text("Save") }
        OutlinedButton(onClick = { model.editingCabinId = null }) { Text("Cancel") }
    }
}

@Composable
private fun AddCabinRow(model: HousingModel, siteId: String?) {
    var name by remember(siteId) { mutableStateOf("") }
    var capacity by remember(siteId) { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(name, { name = it }, placeholder = { Text("New cabin (or RV site, duplex…)") },
            singleLine = true, modifier = Modifier.weight(1f))
        OutlinedTextField(capacity, { capacity = it }, label = { Text("Beds") },
            singleLine = true, modifier = Modifier.width(80.dp))
        Button(enabled = name.isNotBlank(), onClick = {
            model.mutate { addCabin(UpsertCabinRequest(name, siteId, capacity.toIntOrNull())) }
            name = ""
            capacity = ""
        }) { Text("Add cabin") }
    }
}

/** The add-assignment row: congregation and gender pickers plus a free-text label. */
@Composable
private fun AddAssignmentRow(model: HousingModel, season: SeasonDto, cabin: CabinDto) {
    var congregationId by remember(cabin.id) { mutableStateOf<String?>(null) }
    var gender by remember(cabin.id) { mutableStateOf<Gender?>(null) }
    var label by remember(cabin.id) { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val congregations: List<Pair<String?, String>> =
                listOf<Pair<String?, String>>(null to "No congregation (ad-hoc row)") +
                    registeredCongregations(model, season, cabin.siteId)
            DropdownPicker(
                congregations, congregations.firstOrNull { it.first == congregationId }, { it.second },
                enabled = true, placeholder = "Congregation…",
            ) { congregationId = it.first }
            val genders: List<Pair<Gender?, String>> =
                listOf<Pair<Gender?, String>>(null to "Everyone") + Gender.entries.map { it to it.displayName }
            DropdownPicker(
                genders, genders.firstOrNull { it.first == gender }, { it.second },
                enabled = true, placeholder = "Everyone",
            ) { gender = it.first }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(label, { label = it },
                placeholder = { Text("Label / note (e.g. Smith family — RV 3)") },
                singleLine = true, modifier = Modifier.weight(1f))
            Button(onClick = {
                if (congregationId == null && label.isBlank()) {
                    model.message = "Pick a congregation or enter a label"
                    return@Button
                }
                model.mutate {
                    addCabinAssignment(
                        cabin.id,
                        AddCabinAssignmentRequest(congregationId = congregationId, gender = gender, label = label),
                    )
                }
                congregationId = null
                gender = null
                label = ""
            }) { Text("Add") }
        }
    }
}

/**
 * Registered congregations for the picker, [siteId]-scoped on multi-site seasons (a cabin only
 * ever houses congregations attending its site); (id, name) pairs sorted by name.
 */
private fun registeredCongregations(model: HousingModel, season: SeasonDto, siteId: String?): List<Pair<String?, String>> =
    model.desk?.rows.orEmpty()
        .filter { it.registration != null }
        .filter { siteId == null || season.siteFor(it.registration?.siteId)?.id == siteId }
        .map { it.congregation.id as String? to it.congregation.name }
        .sortedBy { it.second.lowercase() }

private fun assignmentLabel(a: CabinAssignmentDto): String {
    val group = when {
        a.congregationId == null -> null
        a.gender == null -> a.congregationName ?: "?"
        else -> "${a.congregationName ?: "?"} — ${a.gender!!.displayName.lowercase()}"
    }
    return listOfNotNull(group, a.label.ifBlank { null }).joinToString(" · ").ifEmpty { "—" }
}

/** Derived head count for a congregation × gender row; null for an ad-hoc (label-only) row. */
private fun occupantCount(a: CabinAssignmentDto, attendees: List<AttendeeRow>): Int? {
    val congregationId = a.congregationId ?: return null
    return attendees.count {
        it.congregationId == congregationId && (a.gender == null || it.gender == a.gender)
    }
}

/** Registered congregations with attendees who appear in no assignment row yet — a to-do hint. */
@Composable
private fun Unhoused(h: HousingResponse, d: RegistrationDeskResponse, attendees: List<AttendeeRow>) {
    val housed = h.cabins.flatMap { it.assignments }.mapNotNull { it.congregationId }.toSet()
    val unhoused = d.rows
        .filter { it.registration != null && it.congregation.id !in housed }
        .map { row -> row.congregation.name to attendees.count { it.congregationId == row.congregation.id } }
        .filter { it.second > 0 }
        .sortedBy { it.first.lowercase() }
    if (unhoused.isEmpty()) return
    SectionHeader("Not yet housed")
    unhoused.forEach { (name, count) ->
        Text("$name — $count attendee${if (count == 1) "" else "s"}",
            style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * The check-out duty roster (one adult per congregation walks their cabin at departure): a row
 * per registered congregation, free-form name, saved per row. Congregations with a duty set but
 * no registration this season still show, so stale rows stay clearable.
 */
@Composable
private fun Duties(model: HousingModel, h: HousingResponse, d: RegistrationDeskResponse) {
    SectionHeader("Cabin check-out duty")
    Text(
        "The one adult per congregation responsible for their cabin walk-through at departure.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val byId = h.duties.associateBy { it.congregationId }
    val rows = d.rows.filter { it.registration != null }.map { it.congregation.id to it.congregation.name } +
        h.duties.filter { duty -> d.rows.none { it.registration != null && it.congregation.id == duty.congregationId } }
            .map { it.congregationId to it.congregationName }
    rows.sortedBy { it.second.lowercase() }.forEach { (congregationId, name) ->
        var adult by remember(congregationId, byId[congregationId]?.adultName) {
            mutableStateOf(byId[congregationId]?.adultName ?: "")
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.8f))
            OutlinedTextField(adult, { adult = it }, placeholder = { Text("Adult's name") },
                singleLine = true, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { model.mutate { setCheckoutDuty(congregationId, adult) } }) {
                Text("Save")
            }
        }
    }
}

/** One CSV row per assignment (site, cabin, capacity, group, count), then the duty roster. */
private fun housingCsv(season: SeasonDto, h: HousingResponse, attendees: List<AttendeeRow>): String {
    val multiSite = season.multiSite
    val header = listOfNotNull("Site".takeIf { multiSite }, "Cabin", "Capacity", "Assignment", "Count")
    val body = h.cabins.flatMap { cabin ->
        val site = season.siteFor(cabin.siteId)?.name ?: ""
        val rows = cabin.assignments.ifEmpty { listOf(null) }
        rows.map { a ->
            listOfNotNull(
                site.takeIf { multiSite },
                cabin.name,
                cabin.capacity?.toString() ?: "",
                a?.let { assignmentLabel(it) } ?: "",
                a?.let { occupantCount(it, attendees)?.toString() } ?: "",
            )
        }
    }
    val duties = listOf(emptyList(), listOfNotNull("".takeIf { multiSite }, "Check-out duty", "", "", "")) +
        h.duties.map { listOfNotNull("".takeIf { multiSite }, it.congregationName, "", it.adultName, "") }
    return csvText(listOf(header) + body + duties)
}
