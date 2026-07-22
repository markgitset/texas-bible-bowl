package net.markdrew.biblebowl.app.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.markdrew.biblebowl.api.ContactInfoDto
import net.markdrew.biblebowl.api.Division
import net.markdrew.biblebowl.api.Gender
import net.markdrew.biblebowl.api.PersonWithParticipationsDto
import net.markdrew.biblebowl.api.RegistrationDeskResponse
import net.markdrew.biblebowl.api.RegistrationDeskRowDto
import net.markdrew.biblebowl.api.RegistrationDto
import net.markdrew.biblebowl.api.RegistrationStatus
import net.markdrew.biblebowl.api.RegistrationUpdateResponse
import net.markdrew.biblebowl.api.ReturningContestantDto
import net.markdrew.biblebowl.api.Role
import net.markdrew.biblebowl.api.ScopeType
import net.markdrew.biblebowl.api.SeasonDto
import net.markdrew.biblebowl.api.ShirtSize
import net.markdrew.biblebowl.api.TeamDto
import net.markdrew.biblebowl.api.UpdateCongregationRequest
import net.markdrew.biblebowl.api.UserDto
import net.markdrew.biblebowl.api.ageTierFor
import net.markdrew.biblebowl.api.contestantCount
import net.markdrew.biblebowl.api.division
import net.markdrew.biblebowl.api.divisionForBirthdate
import net.markdrew.biblebowl.api.formatCents
import net.markdrew.biblebowl.api.gradeForGraduationYear
import net.markdrew.biblebowl.api.isSeededYouth
import net.markdrew.biblebowl.api.isValidBirthdate
import net.markdrew.biblebowl.api.multiSite
import net.markdrew.biblebowl.api.shirtSizes
import net.markdrew.biblebowl.api.siteFor
import net.markdrew.biblebowl.app.platform.Mime
import net.markdrew.biblebowl.app.platform.saveFile
import net.markdrew.biblebowl.app.ui.LocalSeason
import net.markdrew.biblebowl.client.TbbApi

/**
 * Registration desk (docs/gui-redesign.md §5E): every congregation's current-season registration —
 * status, counts, totals, coach contacts, roster detail on tap, a payment-received toggle, and CSV
 * / nametags-PDF exports. Gated (and server-enforced) on an *event-wide* REGISTRATION_MANAGE
 * grant. Claim codes are deliberately not shown — they're the secret a contestant uses to claim
 * their entry. Compose port of the web AdminRegistrationsScreen: one card per congregation
 * instead of the wide table.
 */

/** Desk state + mutation helpers, shared by the card/detail composables. */
internal class DeskModel(val api: TbbApi, val scope: CoroutineScope) {
    var season: SeasonDto by mutableStateOf(net.markdrew.biblebowl.api.FALLBACK_SEASON)
    var data: RegistrationDeskResponse? by mutableStateOf(null)
    var message: String? by mutableStateOf(null)
    var loadError: String? by mutableStateOf(null)
    var siteFilter: String? by mutableStateOf(null)
    var year: String? by mutableStateOf(null) // season year under review, or null = the current season
    val expanded = mutableStateListOf<String>() // congregation ids with the roster detail open

    /** True when reviewing a past season's data — everything renders read-only. */
    val viewingPast: Boolean get() = data?.let { it.seasonYear != season.eventYear.toString() } == true

    /**
     * Multi-site seasons add a site filter and per-row site editing. Suppressed for a past year:
     * site ids only resolve against the *current* season's site list.
     */
    val multiSite: Boolean get() = season.multiSite && !viewingPast

    suspend fun load() {
        try {
            data = api.registrationDesk(year)
        } catch (e: Throwable) {
            loadError = e.message ?: "Something went wrong"
        }
    }

    /** Switches the desk to [selected] (null = current season) and refetches. */
    fun selectYear(selected: String?) {
        year = selected
        data = null
        message = null
        loadError = null
        siteFilter = null
        expanded.clear()
        scope.launch { load() }
    }

    /** Swaps [updated] into its congregation's row. */
    fun swapRegistration(congregationId: String, updated: RegistrationDto) {
        data = data?.let { desk ->
            desk.copy(rows = desk.rows.map { row ->
                if (row.congregation.id == congregationId) row.copy(registration = updated) else row
            })
        }
    }

    /** Swaps a mutation response (registration + pared candidate list) into its congregation's row. */
    private fun swapRow(congregationId: String, updated: RegistrationUpdateResponse) {
        data = data?.let { desk ->
            desk.copy(rows = desk.rows.map { row ->
                if (row.congregation.id == congregationId) row.copy(
                    registration = updated.registration,
                    returningCandidates = updated.returningCandidates,
                ) else row
            })
        }
    }

    /** Runs a desk-side registration mutation and swaps the refreshed registration into its row. */
    fun mutateRow(congregationId: String, call: suspend TbbApi.() -> RegistrationUpdateResponse) {
        message = null
        scope.launch {
            try {
                swapRow(congregationId, api.call())
            } catch (e: Throwable) {
                message = "Could not update the roster: ${e.message}"
            }
        }
    }

    /**
     * Runs a team assignment and reloads the whole desk: a combo placement touches two
     * congregations' rows (the member's and the hosting team's), so a single-row swap isn't enough.
     */
    fun assignAndReload(call: suspend TbbApi.() -> RegistrationUpdateResponse) {
        message = null
        scope.launch {
            try {
                api.call()
                data = api.registrationDesk()
            } catch (e: Throwable) {
                message = "Could not update the roster: ${e.message}"
            }
        }
    }

    /** Enrolls a returning candidate — the response carries the row's pared candidate list. */
    fun enroll(congregationId: String, call: suspend TbbApi.() -> RegistrationUpdateResponse) {
        message = null
        scope.launch {
            try {
                swapRow(congregationId, api.call())
            } catch (e: Throwable) {
                message = "Could not enroll: ${e.message}"
            }
        }
    }
}

@Composable
fun AdminRegistrationsScreen(
    api: TbbApi,
    user: UserDto?,
    onOpenCounts: () -> Unit,
    onOpenHousing: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val model = remember(api) { DeskModel(api, scope) }
    model.season = LocalSeason.current
    // Only a globally-scoped admin may change a congregation's two-letter code once it's set.
    val isAdmin = user?.roles?.any { it.role == Role.ADMIN && it.scopeType == ScopeType.GLOBAL } == true

    LaunchedEffect(model) { model.load() }

    Column(
        Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Registration desk", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        model.loadError?.let {
            Text("Could not load the registration desk: $it", color = MaterialTheme.colorScheme.error)
            return@Column
        }
        val desk = model.data
        if (desk == null) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Column
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            YearPicker(model, desk)
            if (model.multiSite) {
                SiteFilterPicker(model.season, model.siteFilter) { model.siteFilter = it }
            }
            OutlinedButton(onClick = onOpenCounts) { Text("Counts") }
            OutlinedButton(onClick = onOpenHousing) { Text("Housing") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SaveCsvButton("Registrations CSV", { "tbb-registrations-${desk.seasonYear}.csv" }) {
                deskCsv(model, desk)
            }
            // Nametags are a current-event artifact — the endpoint always generates for the
            // current season, so the button hides in a past-year review.
            if (!model.viewingPast) NametagsButton(model, desk.seasonYear)
        }
        Text(
            "Season ${desk.seasonYear} — tap a congregation for its roster." +
                (" Past season — read-only.".takeIf { model.viewingPast } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        model.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        if (desk.rows.isEmpty()) {
            Text("No congregations yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        // Everything below (cards and the summary) respects the site filter, so a desk worker
        // gets per-site counts and totals just by picking a site.
        val rows = desk.rows.filter { row ->
            model.siteFilter == null || model.season.siteFor(row.registration?.siteId)?.id == model.siteFilter
        }
        if (rows.isEmpty()) {
            Text("No congregations at this site yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }

        rows.forEach { row -> CongregationCard(model, row, isAdmin) }

        DeskSummary(rows)
        VolunteersSection(model, rows)
        ShirtOrderSection(model, rows, desk.seasonYear)
    }
}

/**
 * The season under review: hidden when only one year has data, otherwise a picker over every year
 * with registrations — the current event year is the default, past ones are read-only reviews.
 */
@Composable
private fun YearPicker(model: DeskModel, desk: RegistrationDeskResponse) {
    if (desk.availableYears.size < 2) return
    val currentYear = model.season.eventYear.toString()
    val options = desk.availableYears.map { y ->
        y to (if (y == currentYear) "$y (current)" else y)
    }
    DropdownPicker(
        options, options.firstOrNull { it.first == desk.seasonYear }, { it.second },
        enabled = true, placeholder = "Season…",
    ) { (y, _) -> model.selectYear(y.takeIf { it != currentYear }) }
}

@Composable
private fun DeskSummary(rows: List<RegistrationDeskRowDto>) {
    val regs = rows.mapNotNull { it.registration }
    Text(
        "${regs.size} of ${rows.size} congregations registered · " +
            "${regs.sumOf { it.contestantCount }} contestants · " +
            "${regs.sumOf { it.guests.size }} guests · " +
            "${formatCents(regs.mapNotNull { it.totalCents }.sum())} total due · " +
            "${regs.count { it.paidAt != null }} paid",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun CongregationCard(model: DeskModel, row: RegistrationDeskRowDto, isAdmin: Boolean) {
    val reg = row.registration
    val open = row.congregation.id in model.expanded
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .clickable {
                    if (!model.expanded.remove(row.congregation.id)) model.expanded.add(row.congregation.id)
                }
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(row.congregation.name, fontWeight = FontWeight.SemiBold)
                    val cityState =
                        if (row.congregation.state.isBlank()) row.congregation.city
                        else "${row.congregation.city}, ${row.congregation.state}"
                    Text(
                        listOf(cityState, row.congregation.phone).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                CodeCell(model, row, isAdmin)
                StatusBadge(reg?.status)
            }
            val flags = buildList {
                if (model.multiSite) add("site: " + (model.season.siteFor(reg?.siteId)?.name ?: "—"))
                val unassigned = reg?.unassigned?.size ?: 0
                if (unassigned > 0) add("$unassigned unassigned")
                if (row.returningCandidates.isNotEmpty()) add("${row.returningCandidates.size} returning")
            }
            if (flags.isNotEmpty()) {
                Text(flags.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary)
            }
            Text(
                if (reg == null) "No registration started this season."
                else "${reg.teams.size} teams · ${reg.contestantCount} contestants · " +
                    "${reg.individuals.size} individuals · ${reg.guests.size} guests · " +
                    "due ${formatCents(reg.totalCents)}" +
                    (reg.submittedAt?.let { " · submitted ${it.take(10)}" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
            )
            if (reg != null) PaidSwitch(model, row.congregation.id, reg)
            row.coaches.forEach { coach ->
                Text(
                    "Coach: ${coach.displayName} <${coach.email}>",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (open) DeskDetail(model, row)
        }
    }
}

/**
 * The two-letter code: an inline editor for admins (the only ones allowed to change a set code),
 * plain text otherwise. Saving reuses `PUT /congregations/{id}`, resending the other fields.
 */
@Composable
private fun CodeCell(model: DeskModel, row: RegistrationDeskRowDto, isAdmin: Boolean) {
    val cong = row.congregation
    if (!isAdmin || model.viewingPast) {
        Text(
            cong.code.ifBlank { "—" },
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.secondary,
        )
        return
    }
    var code by remember(cong.id, cong.code) { mutableStateOf(cong.code) }
    OutlinedTextField(
        value = code,
        onValueChange = { typed ->
            code = typed.uppercase().take(2)
            if (code.length == 2 && code != cong.code) {
                model.message = null
                model.scope.launch {
                    try {
                        val updated = model.api.updateCongregation(
                            cong.id,
                            UpdateCongregationRequest(
                                name = cong.name, city = cong.city, state = cong.state,
                                mailingAddress = cong.mailingAddress, zip = cong.zip,
                                phone = cong.phone, code = code,
                            ),
                        )
                        model.data = model.data?.let { desk ->
                            desk.copy(rows = desk.rows.map { r ->
                                if (r.congregation.id == cong.id) r.copy(congregation = updated) else r
                            })
                        }
                    } catch (e: Throwable) {
                        model.message = "Could not update code: ${e.message}"
                    }
                }
            }
        },
        label = { Text("Code") },
        singleLine = true,
        modifier = Modifier.width(90.dp),
    )
}

@Composable
private fun StatusBadge(status: RegistrationStatus?) {
    val (text, color) = when (status) {
        RegistrationStatus.SUBMITTED -> "Submitted" to MaterialTheme.colorScheme.primary
        RegistrationStatus.DRAFT -> "Draft" to MaterialTheme.colorScheme.onSurfaceVariant
        null -> "—" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = color)
}

@Composable
private fun PaidSwitch(model: DeskModel, congregationId: String, reg: RegistrationDto) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Switch(
            checked = reg.paidAt != null,
            enabled = !model.viewingPast,
            onCheckedChange = { paid ->
                model.message = null
                model.scope.launch {
                    try {
                        model.swapRegistration(congregationId, model.api.setRegistrationPaid(reg.id, paid))
                    } catch (e: Throwable) {
                        model.message = "Could not update payment: ${e.message}"
                    }
                }
            },
        )
        Text(
            "Payment received" + (reg.paidAt?.let { " ${it.take(10)}" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

// --- expanded roster detail -------------------------------------------------------------

@Composable
private fun DeskDetail(model: DeskModel, row: RegistrationDeskRowDto) {
    val reg = row.registration
    Column(
        Modifier.padding(top = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (reg != null) {
            if (model.multiSite) SitePin(model, row.congregation.id, reg)
            reg.teams.forEach { team -> TeamDetail(model.season, team) }
            UnassignedDetail(model, row.congregation.id, reg)
            if (reg.awayMembers.isNotEmpty()) {
                DetailHeader("On combo teams")
                reg.awayMembers.forEach { away ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "${away.entry.name} — on ${away.teamName} (${away.congregationName})",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        // Pulling a member back home is the desk-side undo for a combo placement.
                        if (!model.viewingPast) OutlinedButton(onClick = {
                            model.assignAndReload { assignMemberTeam(away.entry.id, null) }
                        }) { Text("Unassign") }
                    }
                }
            }
            if (reg.individuals.isNotEmpty()) {
                DetailHeader("Individual contestants (adults)")
                reg.individuals.forEach { entry ->
                    Text(
                        "${entry.name} — shirt ${entry.shirtSize.name}" +
                            (if (entry.tribeLeaderWilling) " · tribe leader" else ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (reg.guests.isNotEmpty()) {
                DetailHeader("Guests & volunteers")
                reg.guests.forEach { guest ->
                    val details = listOfNotNull(
                        model.season.ageTierFor(guest.birthdate).displayName.lowercase(),
                        guest.gender?.displayName?.lowercase(),
                        guest.shirtSize?.let { "shirt ${it.name}" } ?: "no shirt",
                    ) + guest.positions + listOfNotNull("tribe leader".takeIf { guest.tribeLeaderWilling })
                    Column {
                        Text("${guest.name} — ${details.joinToString(", ")}",
                            style = MaterialTheme.typography.bodySmall)
                        guest.contact?.let {
                            Text(contactSummary(it), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                }
            }
        }
        // Coach contact info (item 9, F3) — collected on their accounts; shown where a registrar acts on it.
        row.coaches.filter { it.contact != null }.takeIf { it.isNotEmpty() }?.let { withContact ->
            DetailHeader("Coach contact info")
            withContact.forEach { coach ->
                Text("${coach.displayName} — ${contactSummary(coach.contact!!)}",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        ReturningCandidatesDetail(model, row)
        AttachPersonDetail(model, row)
        val rosterEmpty = reg == null ||
            (reg.teams.isEmpty() && reg.individuals.isEmpty() && reg.unassigned.isEmpty() &&
                reg.awayMembers.isEmpty())
        if (rosterEmpty && row.returningCandidates.isEmpty() && reg != null) {
            Text("Nothing on the roster yet.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DetailHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun TeamDetail(season: SeasonDto, team: TeamDto) {
    val division = team.division(season)
    DetailHeader("${team.name} — ${division?.displayName ?: "Empty"}")
    if (team.members.isEmpty()) {
        Text("No members yet.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    team.members.forEach { member ->
        val memberDivision = member.division(season)?.displayName ?: "division unknown"
        Text(
            "${member.name} — $memberDivision, shirt ${member.shirtSize.name}" +
                // A visiting (combo-team) member — registered and paid for by their own congregation.
                (member.congregationName?.let { " · from $it" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * Desk-side site pin (multi-site seasons): a registrar sets or fixes which event site the
 * congregation attends — the same endpoint the coach uses, accepted for an event-wide grant.
 */
@Composable
private fun SitePin(model: DeskModel, congregationId: String, reg: RegistrationDto) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Event site:", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        val chosen = model.season.siteFor(reg.siteId)
        DropdownPicker(
            options = model.season.sites,
            selected = chosen,
            display = { it.name },
            enabled = true,
            placeholder = "— not chosen —",
        ) { site -> model.mutateRow(congregationId) { setRegistrationSite(congregationId, site.id) } }
    }
}

/**
 * Contestants a coach left unassigned — the registrar places each on a team here (or adds a team
 * first when none exist), including another congregation's team (a combo team — that placement is
 * registrar-only, which is exactly who's on this screen). Uses the same endpoints as the coach
 * flow; an event-wide grant is accepted server-side and isn't window-gated.
 */
@Composable
private fun UnassignedDetail(model: DeskModel, congregationId: String, reg: RegistrationDto) {
    if (reg.unassigned.isEmpty()) return
    if (model.viewingPast) {
        // Review only: who was never placed that year, without the placement controls.
        DetailHeader("Unassigned contestants (${reg.unassigned.size})")
        reg.unassigned.forEach { member ->
            val division = member.division(model.season)?.displayName ?: "division unknown"
            Text(
                "${member.name} — $division, shirt ${member.shirtSize.name}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        return
    }
    // Other congregations' teams, for combo placements ("Team — Congregation").
    val comboTargets = model.data?.rows.orEmpty()
        .filter { it.congregation.id != congregationId }
        .flatMap { row -> row.registration?.teams.orEmpty().map { it to row.congregation.name } }
    DetailHeader("Unassigned contestants (${reg.unassigned.size})")
    reg.unassigned.forEach { member ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val division = member.division(model.season)?.displayName ?: "division unknown"
            Text(
                "${member.name} — $division, shirt ${member.shirtSize.name}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            if (reg.teams.isEmpty() && comboTargets.isEmpty()) {
                Text("add a team below to place them", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val options: List<Pair<String, String>> =
                    reg.teams.map { it.id to it.name } +
                        comboTargets.map { (team, congName) -> team.id to "${team.name} — $congName" }
                DropdownPicker(
                    options, selected = null, display = { it.second },
                    enabled = true, placeholder = "Assign to team…",
                ) { (teamId, _) ->
                    // A combo placement changes the hosting row too — reload the whole desk.
                    model.assignAndReload { assignMemberTeam(member.id, teamId) }
                }
            }
        }
    }
    // A registrar can create a team to place these on (e.g. the coach deleted all their teams).
    var newTeam by remember(congregationId) { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(newTeam, { newTeam = it }, placeholder = { Text("New team name") },
            singleLine = true, modifier = Modifier.weight(1f))
        Button(enabled = newTeam.isNotBlank(), onClick = {
            model.mutateRow(congregationId) { addTeam(congregationId, newTeam) }
            newTeam = ""
        }) { Text("Add team") }
    }
}

/**
 * Prior-year contestants still eligible but not on this season's roster. A registrar may enroll
 * each here (youth onto a team or the unassigned pool, adults as individuals) — the same enroll
 * endpoint the coach uses, accepted for an event-wide grant. Enrolling creates the registration
 * if it doesn't exist yet.
 */
@Composable
private fun ReturningCandidatesDetail(model: DeskModel, row: RegistrationDeskRowDto) {
    val candidates = row.returningCandidates
    if (candidates.isEmpty()) return
    DetailHeader("Returning contestants (${candidates.size})")
    candidates.forEach { candidate ->
        ReturningCandidateRow(model, row.congregation.id, row.registration?.teams.orEmpty(), candidate)
    }
}

@Composable
private fun ReturningCandidateRow(
    model: DeskModel,
    congregationId: String,
    teams: List<TeamDto>,
    candidate: ReturningContestantDto,
) {
    val isAdult = candidate.birthdate == null && !candidate.isSeededYouth
    // A seeded youth's first enrollment records their real birthdate.
    var birthdate by remember(candidate.contestantId) { mutableStateOf("") }
    var shirt by remember(candidate.contestantId) {
        mutableStateOf(candidate.lastShirtSize ?: if (isAdult) ShirtSize.AM else ShirtSize.YM)
    }
    var teamId by remember(candidate.contestantId) { mutableStateOf<String?>(null) }

    val divisionLabel = when {
        isAdult -> "Adult"
        candidate.birthdate != null ->
            model.season.divisionForBirthdate(candidate.birthdate!!)?.displayName ?: "—"
        // Workbook-seeded youth: division from the seeded grade until a birthdate exists.
        else -> candidate.graduationYear
            ?.let { Division.forGrade(model.season.gradeForGraduationYear(it))?.displayName }
            ?.plus(" (seeded)") ?: "—"
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "${candidate.name} — $divisionLabel" +
                (candidate.lastSeasonYear?.let { " · last $it" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (candidate.isSeededYouth) {
                OutlinedTextField(birthdate, { birthdate = it }, label = { Text("Birthdate") },
                    placeholder = { Text("YYYY-MM-DD") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            ShirtPicker(shirt, enabled = true) { shirt = it }
            // Youth pick a team (or the unassigned pool); adults enroll as individuals (no team).
            if (!isAdult && teams.isNotEmpty()) {
                val options: List<Pair<String?, String>> =
                    listOf<Pair<String?, String>>(null to "— Unassigned —") + teams.map { it.id to it.name }
                DropdownPicker(
                    options, options.firstOrNull { it.first == teamId }, { it.second },
                    enabled = true, placeholder = "Team…",
                ) { teamId = it.first }
            }
            Button(onClick = {
                if (candidate.isSeededYouth && !isValidBirthdate(birthdate)) {
                    model.message =
                        "${candidate.name} needs a birthdate — the workbook only had a school grade"
                    return@Button
                }
                model.enroll(congregationId) {
                    enrollContestant(
                        congregationId, candidate.contestantId, shirt,
                        teamId.takeUnless { isAdult },
                        birthdate.takeIf { candidate.isSeededYouth },
                    )
                }
            }) { Text("Add") }
        }
    }
}

/**
 * Registrar-only cross-congregation attach: find an existing person (any congregation) and add them
 * to this congregation's current-season roster — for someone who moved congregations. Coaches never
 * reach the desk. Hidden while reviewing a past year; people already registered this season anywhere
 * are filtered out (they can't be attached).
 */
@Composable
private fun AttachPersonDetail(model: DeskModel, row: RegistrationDeskRowDto) {
    if (model.viewingPast) return
    val congregationId = row.congregation.id
    val currentYear = model.season.eventYear.toString()
    var query by remember(congregationId) { mutableStateOf("") }
    var results by remember(congregationId) { mutableStateOf<List<PersonWithParticipationsDto>>(emptyList()) }
    var searched by remember(congregationId) { mutableStateOf(false) }

    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) { results = emptyList(); searched = false; return@LaunchedEffect }
        delay(300)
        runCatching { model.api.searchPeople(q) }.onSuccess {
            results = it.filter { pwp -> pwp.participations.none { p -> p.seasonYear == currentYear } }
            searched = true
        }
    }

    DetailHeader("Attach an existing person")
    Text(
        "Search anyone already in the system (e.g. moved from another congregation) and add them here.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(query, { query = it }, placeholder = { Text("Search people by name…") },
        singleLine = true, modifier = Modifier.fillMaxWidth())
    if (searched && results.isEmpty()) {
        Text("No attachable matches — anyone already registered this season is hidden.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    results.forEach { pwp ->
        AttachPersonRow(model, congregationId, row.registration?.teams.orEmpty(), pwp)
    }
}

@Composable
private fun AttachPersonRow(
    model: DeskModel,
    congregationId: String,
    teams: List<TeamDto>,
    pwp: PersonWithParticipationsDto,
) {
    val person = pwp.person
    val needsBirthdate = person.birthdate == null && person.graduationYear != null
    var birthdate by remember(person.id) { mutableStateOf("") }
    var shirt by remember(person.id) {
        mutableStateOf(pwp.participations.firstOrNull()?.shirtSize ?: if (person.isAdult) ShirtSize.AM else ShirtSize.YM)
    }
    var teamId by remember(person.id) { mutableStateOf<String?>(null) }
    val divisionLabel = person.division(model.season)?.displayName ?: "—"
    val last = pwp.participations.firstOrNull()?.let { " · last ${it.seasonYear} ${it.congregationName}" } ?: ""
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("${person.name} — $divisionLabel$last", style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (needsBirthdate) {
                OutlinedTextField(birthdate, { birthdate = it }, label = { Text("Birthdate") },
                    placeholder = { Text("YYYY-MM-DD") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            ShirtPicker(shirt, enabled = true) { shirt = it }
            if (!person.isAdult && teams.isNotEmpty()) {
                val options: List<Pair<String?, String>> =
                    listOf<Pair<String?, String>>(null to "— Unassigned —") + teams.map { it.id to it.name }
                DropdownPicker(
                    options, options.firstOrNull { it.first == teamId }, { it.second },
                    enabled = true, placeholder = "Team…",
                ) { teamId = it.first }
            }
            Button(onClick = {
                if (needsBirthdate && !isValidBirthdate(birthdate)) {
                    model.message = "${person.name} needs a birthdate — the workbook only had a school grade"
                    return@Button
                }
                model.mutateRow(congregationId) {
                    attachPerson(congregationId, person.id, shirt, teamId.takeUnless { person.isAdult },
                        birthdate.takeIf { needsBirthdate })
                }
            }) { Text("Attach") }
        }
    }
}

// --- volunteers + shirt order -----------------------------------------------------------

/** A volunteer or willing tribe leader, labeled with their congregation. */
private data class Volunteer(val name: String, val congregation: String)

/**
 * The volunteers roll-up (replaces the workbook's Volunteers tab): every adult guest's positions
 * across the listed congregations grouped by position, plus the willing tribe leaders — adult
 * guests and individual (adult) contestants alike. [rows] is already site-filtered.
 */
@Composable
private fun VolunteersSection(model: DeskModel, rows: List<RegistrationDeskRowDto>) {
    val byPosition = linkedMapOf<String, MutableList<Volunteer>>()
    model.season.volunteerPositions.forEach { byPosition[it] = mutableListOf() }
    val tribeLeaders = mutableListOf<Volunteer>()
    rows.forEach { row ->
        val reg = row.registration ?: return@forEach
        reg.guests.forEach { guest ->
            guest.positions.forEach { position ->
                // getOrPut keeps volunteers whose position was later removed from the season list.
                byPosition.getOrPut(position) { mutableListOf() } += Volunteer(guest.name, row.congregation.name)
            }
            if (guest.tribeLeaderWilling) tribeLeaders += Volunteer(guest.name, row.congregation.name)
        }
        reg.individuals.filter { it.tribeLeaderWilling }
            .forEach { tribeLeaders += Volunteer(it.name, row.congregation.name) }
    }
    if (byPosition.values.all { it.isEmpty() } && tribeLeaders.isEmpty()) return

    SectionHeader("Volunteers")
    byPosition.filterValues { it.isNotEmpty() }.forEach { (position, volunteers) ->
        Text("$position (${volunteers.size})", style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold)
        volunteers.sortedBy { it.name.lowercase() }.forEach {
            Text("${it.name} — ${it.congregation}", style = MaterialTheme.typography.bodySmall)
        }
    }
    if (tribeLeaders.isNotEmpty()) {
        Text("Willing tribe leaders (${tribeLeaders.size})", style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold)
        tribeLeaders.sortedBy { it.name.lowercase() }.forEach {
            Text("${it.name} — ${it.congregation}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * The shirt-order matrix (replaces the workbook's `Congregations` shirt-order tab): size counts
 * per congregation with grand totals by size, over every attendee whose fee includes a shirt
 * (see [shirtSizes] — under-3 guests get none, and a combo-team member counts under their own
 * congregation). [rows] is already site-filtered, so picking a site yields the per-site order
 * that actually goes to the vendor; the CSV downloads the same matrix.
 */
@Composable
private fun ShirtOrderSection(model: DeskModel, rows: List<RegistrationDeskRowDto>, seasonYear: String) {
    val counts: List<Pair<String, Map<ShirtSize, Int>>> = rows.mapNotNull { row ->
        val sizes = row.registration?.shirtSizes.orEmpty()
        if (sizes.isEmpty()) null else row.congregation.name to sizes.groupingBy { it }.eachCount()
    }
    if (counts.isEmpty()) return
    val totals: Map<ShirtSize, Int> =
        ShirtSize.entries.associateWith { size -> counts.sumOf { (_, bySize) -> bySize[size] ?: 0 } }
    val noShirt = rows.sumOf { row -> row.registration?.guests?.count { it.shirtSize == null } ?: 0 }

    SectionHeader("Shirt order (${totals.values.sum()})")
    SaveCsvButton(
        "Shirt order CSV",
        {
            val site = model.siteFilter?.let { model.season.siteFor(it) }
            val suffix = site?.let { "-${siteSlug(it.name)}" } ?: ""
            "tbb-shirt-order-$seasonYear$suffix.csv"
        },
    ) { shirtCsv(counts, totals) }
    ScrollTable(
        headers = listOf("Congregation") + ShirtSize.entries.map { it.displayName } + "Total",
        rows = counts.map { (congregation, bySize) ->
            congregation to (ShirtSize.entries.map { bySize[it]?.toString() ?: "" } +
                bySize.values.sum().toString())
        } + ("Total" to (ShirtSize.entries.map { totals.getValue(it).toString() } +
            totals.values.sum().toString())),
        boldLastRow = true,
    )
    if (noShirt > 0) {
        Text("Plus $noShirt under-3 guest(s) with no included shirt.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun shirtCsv(counts: List<Pair<String, Map<ShirtSize, Int>>>, totals: Map<ShirtSize, Int>): String {
    val header = listOf("Congregation") + ShirtSize.entries.map { it.displayName } + "Total"
    val rows = counts.map { (congregation, bySize) ->
        listOf(congregation) + ShirtSize.entries.map { (bySize[it] ?: 0).toString() } +
            bySize.values.sum().toString()
    }
    val totalRow = listOf("Total") + ShirtSize.entries.map { totals.getValue(it).toString() } +
        totals.values.sum().toString()
    return csvText(listOf(header) + rows + listOf(totalRow))
}

// --- exports ----------------------------------------------------------------------------

/**
 * Fetches the nametags PDF (item 14, F8) honoring the site filter — an authenticated fetch, not a
 * public /generate link, because attendee names include minors'. Generating assigns any missing
 * tester IDs via the same append-only scheme as the tester-IDs screen.
 */
@Composable
private fun NametagsButton(model: DeskModel, seasonYear: String) {
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    Column {
        OutlinedButton(enabled = !busy, onClick = {
            busy = true
            status = null
            model.scope.launch {
                status = try {
                    val bytes = model.api.nametagsPdf(model.siteFilter)
                    val site = model.siteFilter?.let { model.season.siteFor(it) }
                    val suffix = site?.let { "-${siteSlug(it.name)}" } ?: ""
                    saveFile("tbb-nametags-$seasonYear$suffix.pdf", bytes, Mime.PDF)
                } catch (e: Throwable) {
                    "Could not generate nametags: ${e.message}"
                }
                busy = false
            }
        }) { Text("Nametags PDF") }
        status?.let {
            Text(it, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun deskCsv(model: DeskModel, desk: RegistrationDeskResponse): String {
    val multiSite = model.multiSite
    val header = listOfNotNull(
        "Congregation", "Code", "City", "State", "Site".takeIf { multiSite }, "Status", "Teams",
        "Contestants", "Individuals", "Guests", "Total Due", "Submitted", "Paid", "Coach Names",
        "Coach Emails",
    )
    val rows = desk.rows.map { row ->
        val reg = row.registration
        listOfNotNull(
            row.congregation.name,
            row.congregation.code,
            row.congregation.city,
            row.congregation.state,
            (model.season.siteFor(reg?.siteId)?.name ?: "").takeIf { multiSite },
            reg?.status?.name ?: "NONE",
            reg?.teams?.size?.toString() ?: "",
            reg?.contestantCount?.toString() ?: "",
            reg?.individuals?.size?.toString() ?: "",
            reg?.guests?.size?.toString() ?: "",
            reg?.totalCents?.let { formatCents(it) } ?: "",
            reg?.submittedAt?.take(10) ?: "",
            reg?.paidAt?.take(10) ?: "",
            row.coaches.joinToString("; ") { it.displayName },
            row.coaches.joinToString("; ") { it.email },
        )
    }
    return csvText(listOf(header) + rows)
}

/** "1 Main St, Waco, TX 76701 · 555-1234 · a@b.org · prefers text" — only the parts provided. */
internal fun contactSummary(contact: ContactInfoDto): String {
    val stateZip = listOf(contact.state, contact.zip).filter { it.isNotBlank() }.joinToString(" ")
    val postal = listOf(contact.address, contact.city, stateZip).filter { it.isNotBlank() }.joinToString(", ")
    return listOfNotNull(
        postal.takeIf { it.isNotBlank() },
        contact.phone.takeIf { it.isNotBlank() },
        contact.email.takeIf { it.isNotBlank() },
        contact.preference?.let { "prefers ${it.displayName.lowercase()}" },
    ).joinToString(" · ")
}
