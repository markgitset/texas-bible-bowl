package net.markdrew.biblebowl.app.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.markdrew.biblebowl.api.AgeTier
import net.markdrew.biblebowl.api.AwayMemberDto
import net.markdrew.biblebowl.api.ContactInfoDto
import net.markdrew.biblebowl.api.ContactPreference
import net.markdrew.biblebowl.api.Division
import net.markdrew.biblebowl.api.Gender
import net.markdrew.biblebowl.api.ParticipantDto
import net.markdrew.biblebowl.api.ReturningContestantDto
import net.markdrew.biblebowl.api.ShirtSize
import net.markdrew.biblebowl.api.TeamDto
import net.markdrew.biblebowl.api.UpsertGuestRequest
import net.markdrew.biblebowl.api.UpsertIndividualRequest
import net.markdrew.biblebowl.api.UpsertRosterEntryRequest
import net.markdrew.biblebowl.api.ageTierFor
import net.markdrew.biblebowl.api.division
import net.markdrew.biblebowl.api.divisionForBirthdate
import net.markdrew.biblebowl.api.feeCentsFor
import net.markdrew.biblebowl.api.feesNote
import net.markdrew.biblebowl.api.formatCents
import net.markdrew.biblebowl.api.gradeForGraduationYear
import net.markdrew.biblebowl.api.isInexperienced
import net.markdrew.biblebowl.api.isSeededYouth
import net.markdrew.biblebowl.api.isValidBirthdate

/**
 * Step 3 of the register flow (see RegisterScreen): the roster — team member rows, the unassigned
 * pool, members away on combo teams, returning candidates, individual (adult) contestants, and
 * guests/volunteers. Ported from the web RegisterScreen's roster step; rows autosave like the web
 * (text on focus loss, pickers/checkboxes immediately).
 */
@Composable
internal fun RosterStep(model: RegisterModel) {
    model.registration?.teams?.forEach { team -> TeamRosterCard(model, team) }
    UnassignedCard(model)
    AwayMembersCard(model)
    ReturningCard(model)
    IndividualsCard(model)
    GuestsCard(model)

    model.registration?.let { reg ->
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Total: ${formatCents(reg.totalCents)} " +
                        "(${totalBreakdown(model.season, reg)}, t-shirts included)",
                    fontWeight = FontWeight.SemiBold,
                )
                model.season.feesNote.takeIf { it.isNotEmpty() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (model.contestants > 0 || model.guests.isNotEmpty()) {
        Button(onClick = { model.goTo(4) }) { Text("Continue to review") }
    }
}

// --- team rosters -----------------------------------------------------------------------

@Composable
private fun TeamRosterCard(model: RegisterModel, team: TeamDto) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(team.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                TeamDivisionBadge(model, team)
            }
            team.members.forEach { member ->
                // Visiting (combo-team) members are edited by their own congregation's coach.
                if (member.participation.congregationId != model.registration?.congregation?.id) {
                    VisitingMemberRow(model, member)
                } else {
                    ContestantRow(model, member, currentTeamId = team.id)
                }
            }
            when {
                !model.canEdit -> {}
                team.members.size >= 4 -> Text(
                    "This team is full (4 contestants max).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> AddMemberForm(model, team)
            }
        }
    }
}

/**
 * A visiting member on one of this congregation's (combo) teams: read-only here — their own
 * congregation registers, edits, and pays for them; a registrar moves them.
 */
@Composable
private fun VisitingMemberRow(model: RegisterModel, member: ParticipantDto) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(member.person.name)
            Text(
                "from ${member.participation.congregationName}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        val division = member.division(model.season)?.displayName ?: "division unknown"
        Text(
            "$division — registered by their own congregation",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One editable youth contestant row (name/birthdate/shirt/gender/1st-year), used under a team, in
 * the Unassigned card, and in the On-combo-teams card. [currentTeamId] is the team the row belongs
 * to (null when unassigned); the team dropdown moves the contestant between teams or off to the
 * pool. [away] labels the current (other-congregation) team when the member is on a combo team.
 *
 * Each field saves independently with the server's values for the others (text fields on focus
 * loss, pickers immediately), like the web row's per-field change events.
 */
@Composable
private fun ContestantRow(
    model: RegisterModel,
    member: ParticipantDto,
    currentTeamId: String?,
    away: AwayMemberDto? = null,
) {
    fun save(
        name: String = member.person.name,
        birthdate: String = member.person.birthdate ?: "",
        shirt: ShirtSize = member.participation.shirtSize ?: ShirtSize.YM,
        gender: Gender? = member.person.gender,
        firstYear: Boolean = member.isInexperienced(model.seasonYear),
    ) {
        if (name.isNotBlank() && isValidBirthdate(birthdate) && gender != null) {
            model.mutate {
                updateRosterEntry(member.participation.id, UpsertRosterEntryRequest(name, birthdate, shirt, gender, firstYear))
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            BlurSaveField(member.person.name, "Name", model.canEdit, Modifier.weight(1.4f)) { save(name = it) }
            BlurSaveField(
                member.person.birthdate ?: "", "Birthdate", model.canEdit, Modifier.weight(1f),
                placeholder = "YYYY-MM-DD", validate = ::isValidBirthdate,
            ) { save(birthdate = it) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ShirtPicker(member.participation.shirtSize ?: ShirtSize.YM, model.canEdit) { save(shirt = it) }
            GenderPicker(member.person.gender, model.canEdit) { save(gender = it) }
            LabeledCheckbox("1st year", member.isInexperienced(model.seasonYear), model.canEdit) {
                save(firstYear = it)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TeamAssignPicker(model, member, currentTeamId, away)
            ClaimCodeChip(member.person.claimCode)
            if (model.canEdit) {
                BusyButton(model, "remove-${member.participation.id}", "Remove", outlined = true) {
                    model.mutate("remove-${member.participation.id}") { deleteRosterEntry(member.participation.id) }
                }
            }
        }
    }
}

/**
 * The per-contestant team picker: each of this congregation's teams plus an "Unassigned" option.
 * Changing it moves the contestant (server enforces the 4-cap). Rendered only when there's
 * somewhere to move to — at least one team, or the contestant is currently on one. For a member
 * on a combo team, [away] adds that (other-congregation) team as the selected option, so the
 * coach can pull the member back but not push anyone across (registrar-only).
 */
@Composable
private fun TeamAssignPicker(
    model: RegisterModel,
    member: ParticipantDto,
    currentTeamId: String?,
    away: AwayMemberDto? = null,
) {
    val teams = model.registration?.teams.orEmpty()
    if (teams.isEmpty() && currentTeamId == null && away == null) return
    val options: List<Pair<String?, String>> = buildList {
        add(null to "— Unassigned —")
        teams.forEach { add(it.id to it.name) }
        away?.let { add(it.teamId to "${it.teamName} (${it.congregationName})") }
    }
    val selectedId = away?.teamId ?: currentTeamId
    DropdownPicker(
        options = options,
        selected = options.firstOrNull { it.first == selectedId },
        display = { it.second },
        enabled = model.canEdit,
        placeholder = "Team…",
    ) { (teamId, _) -> model.mutate { assignMemberTeam(member.participation.id, teamId) } }
}

@Composable
private fun AddMemberForm(model: RegisterModel, team: TeamDto) {
    var name by remember { mutableStateOf("") }
    var birthdate by remember { mutableStateOf("") }
    var shirt by remember { mutableStateOf(ShirtSize.YM) }
    var gender by remember { mutableStateOf<Gender?>(null) }
    var firstYear by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(name, { name = it }, placeholder = { Text("Contestant name") },
                singleLine = true, modifier = Modifier.weight(1.4f))
            OutlinedTextField(birthdate, { birthdate = it }, label = { Text("Birthdate") },
                placeholder = { Text("YYYY-MM-DD") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ShirtPicker(shirt, enabled = true) { shirt = it }
            GenderPicker(gender, enabled = true) { gender = it }
            LabeledCheckbox("1st year", firstYear, enabled = true) { firstYear = it }
            BusyButton(
                model, "add-member-${team.id}", "Add",
                enabled = name.isNotBlank() && isValidBirthdate(birthdate) && gender != null,
                onClick = {
                    val chosenGender = gender ?: return@BusyButton
                    model.mutate("add-member-${team.id}") {
                        addRosterEntry(
                            team.id,
                            UpsertRosterEntryRequest(name, birthdate, shirt, chosenGender, firstYear),
                        )
                    }
                    name = ""
                    birthdate = ""
                    gender = null
                    firstYear = false
                },
            )
        }
        Text(
            "The birthdate places each contestant in the right division (adults go under " +
                "Individual contestants instead). Each contestant gets a claim code — share it so " +
                "they (or a parent) can link their own account later.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// --- pools ------------------------------------------------------------------------------

/** Eligible youth not on a team (e.g. their team was deleted) — assign each, or a registrar will. */
@Composable
private fun UnassignedCard(model: RegisterModel) {
    val unassigned = model.registration?.unassigned.orEmpty()
    if (unassigned.isEmpty()) return
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Unassigned contestants (${unassigned.size})",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.tertiary)
            Text(
                "These contestants are eligible but not on a team yet (their team was deleted). " +
                    "Pick a team for each below. You can still submit with some here — a registrar " +
                    "will place them on a team for you.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (model.registration?.teams.isNullOrEmpty()) {
                Text(
                    "Add a team on the Teams step first, then assign these contestants to it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            unassigned.forEach { member -> ContestantRow(model, member, currentTeamId = null) }
        }
    }
}

/** Members a registrar placed on another congregation's (combo) team — still edited and billed here. */
@Composable
private fun AwayMembersCard(model: RegisterModel) {
    val away = model.registration?.awayMembers.orEmpty()
    if (away.isEmpty()) return
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("On combo teams (${away.size})",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.secondary)
            Text(
                "A registrar placed these contestants on another congregation's team — they're " +
                    "still registered and paid for by your congregation. You can pull one back " +
                    "(pick Unassigned or one of your teams); only a registrar can place them on " +
                    "another congregation's team.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            away.forEach { ContestantRow(model, it.entry, currentTeamId = it.teamId, away = it) }
        }
    }
}

// --- returning candidates ---------------------------------------------------------------

/**
 * Contestants who competed here before but aren't on this season's roster yet. Team assignments
 * are per-year, so a new season starts with none — these are offered for one-click enrollment
 * (which is what starts counting/billing them). Only shown when the coach can edit.
 */
@Composable
private fun ReturningCard(model: RegisterModel) {
    // Youth candidates go on a team here; returning adults are offered in the individuals card.
    // Workbook-seeded youth (grade but no birthdate yet) count as youth: enrolling collects it.
    val candidates = model.loaded?.returningCandidates.orEmpty()
        .filter { it.birthdate != null || it.isSeededYouth }
    if (candidates.isEmpty() || !model.canEdit) return
    val cong = model.congregation ?: return
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Returning contestants (${candidates.size})",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.secondary)
            Text(
                "These competed for your congregation before but aren't on this year's roster " +
                    "yet. Add each to a team (or the roster) — they're only counted once you do.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            candidates.forEach { candidate -> ReturningRow(model, candidate, cong.id) }
        }
    }
}

@Composable
private fun ReturningRow(model: RegisterModel, candidate: ReturningContestantDto, congregationId: String) {
    // A workbook-seeded youth has no birthdate on file yet — collect it at first enrollment.
    var birthdate by remember(candidate.contestantId) { mutableStateOf("") }
    var shirt by remember(candidate.contestantId) { mutableStateOf(candidate.lastShirtSize ?: ShirtSize.YM) }
    var teamId by remember(candidate.contestantId) { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(candidate.name, modifier = Modifier.weight(1f))
            val division = candidate.birthdate?.let { model.season.divisionForBirthdate(it) }
                ?: candidate.graduationYear
                    ?.let { Division.forGrade(model.season.gradeForGraduationYear(it)) }
            Text(
                division?.displayName ?: "—",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (division == null) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.primary,
            )
        }
        val notes = listOfNotNull(
            candidate.graduationYear.takeIf { candidate.isSeededYouth }
                ?.let { "~grade ${model.season.gradeForGraduationYear(it)}" },
            candidate.lastSeasonYear?.let { "last competed $it" },
        )
        if (notes.isNotEmpty()) {
            Text(notes.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (candidate.isSeededYouth) {
                OutlinedTextField(birthdate, { birthdate = it }, label = { Text("Birthdate") },
                    placeholder = { Text("YYYY-MM-DD") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            ShirtPicker(shirt, enabled = true) { shirt = it }
            val teams = model.registration?.teams.orEmpty()
            val options: List<Pair<String?, String>> =
                listOf<Pair<String?, String>>(null to "— Unassigned —") + teams.map { it.id to it.name }
            DropdownPicker(
                options, options.firstOrNull { it.first == teamId }, { it.second },
                enabled = true, placeholder = "Team…",
            ) { teamId = it.first }
            BusyButton(model, "enroll-${candidate.contestantId}", "Add") {
                if (candidate.isSeededYouth && !isValidBirthdate(birthdate)) {
                    model.error = "${candidate.name} needs a birthdate — the workbook only had a school grade"
                    return@BusyButton
                }
                model.mutate("enroll-${candidate.contestantId}") {
                    enrollContestant(
                        congregationId, candidate.contestantId, shirt, teamId,
                        birthdate.takeIf { candidate.isSeededYouth },
                    )
                }
            }
        }
    }
}

// --- individuals ------------------------------------------------------------------------

/** Adult contestants aren't on any team — they're registered here, straight on the registration. */
@Composable
private fun IndividualsCard(model: RegisterModel) {
    val cong = model.congregation ?: return
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Individual contestants (Adult)",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Adults aren't placed on a team — each competes individually in the Adult division.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            model.registration?.individuals?.forEach { member -> IndividualRow(model, member) }

            // Returning adults: competed here before but not on this year's roster — one-click add.
            val returningAdults = model.loaded?.returningCandidates.orEmpty()
                .filter { it.birthdate == null && !it.isSeededYouth }
            if (model.canEdit && returningAdults.isNotEmpty()) {
                Text("Returning adults — add each to this year's roster:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary)
                returningAdults.forEach { candidate ->
                    var shirt by remember(candidate.contestantId) {
                        mutableStateOf(candidate.lastShirtSize ?: ShirtSize.AM)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(candidate.name)
                            candidate.lastSeasonYear?.let {
                                Text("last competed $it", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        ShirtPicker(shirt, enabled = true) { shirt = it }
                        BusyButton(model, "enroll-${candidate.contestantId}", "Add") {
                            model.mutate("enroll-${candidate.contestantId}") {
                                enrollContestant(cong.id, candidate.contestantId, shirt, null)
                            }
                        }
                    }
                }
            }

            if (model.canEdit) AddIndividualForm(model, cong.id)
        }
    }
}

@Composable
private fun IndividualRow(model: RegisterModel, member: ParticipantDto) {
    fun save(
        name: String = member.person.name,
        shirt: ShirtSize = member.participation.shirtSize ?: ShirtSize.AM,
        gender: Gender? = member.person.gender,
        tribe: Boolean = member.participation.tribeLeaderWilling,
    ) {
        if (name.isNotBlank() && gender != null) {
            model.mutate {
                updateIndividual(member.participation.id, UpsertIndividualRequest(name, shirt, gender, tribeLeaderWilling = tribe))
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BlurSaveField(member.person.name, "Name", model.canEdit, Modifier.fillMaxWidth()) { save(name = it) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ShirtPicker(member.participation.shirtSize ?: ShirtSize.AM, model.canEdit) { save(shirt = it) }
            GenderPicker(member.person.gender, model.canEdit) { save(gender = it) }
            LabeledCheckbox("Tribe leader?", member.participation.tribeLeaderWilling, model.canEdit) { save(tribe = it) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ClaimCodeChip(member.person.claimCode)
            if (model.canEdit) {
                BusyButton(model, "remove-${member.participation.id}", "Remove", outlined = true) {
                    model.mutate("remove-${member.participation.id}") { deleteIndividual(member.participation.id) }
                }
            }
        }
    }
}

@Composable
private fun AddIndividualForm(model: RegisterModel, congregationId: String) {
    var name by remember { mutableStateOf("") }
    var shirt by remember { mutableStateOf(ShirtSize.AM) }
    var gender by remember { mutableStateOf<Gender?>(null) }
    var tribe by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(name, { name = it }, placeholder = { Text("Adult contestant name") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ShirtPicker(shirt, enabled = true) { shirt = it }
            GenderPicker(gender, enabled = true) { gender = it }
            LabeledCheckbox("Tribe leader?", tribe, enabled = true) { tribe = it }
            BusyButton(
                model, "add-individual", "Add",
                enabled = name.isNotBlank() && gender != null,
                onClick = {
                    val chosenGender = gender ?: return@BusyButton
                    model.mutate("add-individual") {
                        addIndividual(
                            congregationId,
                            UpsertIndividualRequest(name, shirt, chosenGender, tribeLeaderWilling = tribe),
                        )
                    }
                    name = ""
                    gender = null
                    tribe = false
                },
            )
        }
    }
}

// --- guests -----------------------------------------------------------------------------

/** "Age 3–8 — $65" / "Under 3 — free" — the live fee hint beside a guest's birthdate. */
private fun tierHint(model: RegisterModel, tier: AgeTier): String = when (tier) {
    AgeTier.AGE_9_PLUS -> "" // the default; no callout needed
    AgeTier.UNDER_3 -> "${tier.displayName} — free"
    else -> "${tier.displayName} — ${formatCents(model.season.feeCentsFor(tier, contestant = false))}"
}

/** Guests (mostly volunteers) register and pay too, but aren't contestants and join no team. */
@Composable
private fun GuestsCard(model: RegisterModel) {
    val cong = model.congregation ?: return
    val season = model.season
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Guests & volunteers", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold)
            Text(
                "Everyone attending must register and pay, including guests and volunteers — " +
                    "age 9+ ${formatCents(season.priceVolunteerCents)}, ages 3–8 " +
                    "${formatCents(season.priceChildCents)}, under 3 free. T-shirt included " +
                    "(except under-3s). Guests aren't contestants and aren't placed on a team.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            model.guests.forEach { guest -> GuestRow(model, guest) }
            if (model.canEdit) AddGuestForm(model, cong.id)
        }
    }
}

@Composable
private fun GuestRow(model: RegisterModel, guest: ParticipantDto) {
    // The birthdate is row state (not just server state) so the tier hint, shirt visibility, and
    // volunteer/contact sections react as the coach types.
    var birthdate by remember(guest.participation.id, guest.person.birthdate) { mutableStateOf(guest.person.birthdate ?: "") }
    var contactOpen by remember(guest.participation.id) { mutableStateOf(false) }
    var contact by remember(guest.participation.id, guest.person.contact) { mutableStateOf(guest.person.contact ?: ContactInfoDto()) }
    val tier = model.season.ageTierFor(birthdate.ifBlank { null })

    fun save(
        name: String = guest.person.name,
        shirt: ShirtSize? = guest.participation.shirtSize,
        gender: Gender? = guest.person.gender,
        positions: List<String> = guest.participation.positions,
        tribe: Boolean = guest.participation.tribeLeaderWilling,
    ) {
        if (name.isBlank() || gender == null) return
        val birthdateOrNull = birthdate.ifBlank { null }
        if (birthdateOrNull != null && !isValidBirthdate(birthdateOrNull)) return
        // Under-3s' free entry includes no shirt.
        val effectiveShirt = if (tier == AgeTier.UNDER_3) null else shirt ?: ShirtSize.AM
        model.mutate {
            updateGuest(
                guest.participation.id,
                UpsertGuestRequest(
                    name, effectiveShirt, birthdateOrNull, gender,
                    positions = positions, tribeLeaderWilling = tribe, contact = contact,
                ),
            )
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            BlurSaveField(guest.person.name, "Name", model.canEdit, Modifier.weight(1.4f)) { save(name = it) }
            BlurSaveField(
                guest.person.birthdate ?: "", "Birthdate (children)", model.canEdit, Modifier.weight(1f),
                placeholder = "YYYY-MM-DD",
                validate = { it.isBlank() || isValidBirthdate(it) },
            ) {
                birthdate = it
                save()
            }
        }
        tierHint(model, tier).takeIf { it.isNotEmpty() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            GenderPicker(guest.person.gender, model.canEdit) { save(gender = it) }
            if (tier != AgeTier.UNDER_3) {
                ShirtPicker(guest.participation.shirtSize ?: ShirtSize.AM, model.canEdit) { save(shirt = it) }
            }
            if (tier == AgeTier.AGE_9_PLUS) {
                OutlinedButton(onClick = { contactOpen = !contactOpen }) {
                    Text(if (guest.person.contact != null) "Contact ✓" else "Contact")
                }
            }
            if (model.canEdit) {
                BusyButton(model, "remove-${guest.participation.id}", "Remove", outlined = true) {
                    model.mutate("remove-${guest.participation.id}") { deleteGuest(guest.participation.id) }
                }
            }
        }
        // Volunteer positions + tribe leading (item 8, F2): adult (age-9+ tier) guests only.
        if (tier == AgeTier.AGE_9_PLUS) {
            VolunteerFields(
                model,
                positions = guest.participation.positions,
                tribeLeaderWilling = guest.participation.tribeLeaderWilling,
                enabled = model.canEdit,
                onPositionsChange = { save(positions = it) },
                onTribeChange = { save(tribe = it) },
            )
            if (contactOpen) {
                GuestContactPanel(model, contact, onChange = { contact = it }, onSave = { save() })
            }
        }
    }
}

/**
 * The volunteer questions for an adult (age-9+) guest: one checkbox per season-configured
 * position plus the tribe-leader checkbox.
 */
@Composable
private fun VolunteerFields(
    model: RegisterModel,
    positions: List<String>,
    tribeLeaderWilling: Boolean,
    enabled: Boolean,
    onPositionsChange: (List<String>) -> Unit,
    onTribeChange: (Boolean) -> Unit,
) {
    Column {
        Text("Volunteer:", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        model.season.volunteerPositions.forEach { position ->
            LabeledCheckbox(position, position in positions, enabled) { checked ->
                onPositionsChange(if (checked) positions + position else positions - position)
            }
        }
        LabeledCheckbox("Tribe leader?", tribeLeaderWilling, enabled, onTribeChange)
    }
}

/**
 * A guest's contact details (item 9, F3): optional address/phone/email/preference (guests have no
 * account, hence the email) with an explicit save — contact isn't autosaved per keystroke, like
 * the web's panel.
 */
@Composable
private fun GuestContactPanel(
    model: RegisterModel,
    contact: ContactInfoDto,
    onChange: (ContactInfoDto) -> Unit,
    onSave: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(start = 12.dp)) {
        Text("Contact info for event organizers (optional)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(contact.address, { onChange(contact.copy(address = it)) },
            label = { Text("Street address") }, singleLine = true,
            enabled = model.canEdit, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(contact.city, { onChange(contact.copy(city = it)) }, label = { Text("City") },
                singleLine = true, enabled = model.canEdit, modifier = Modifier.weight(1f))
            OutlinedTextField(contact.state, { onChange(contact.copy(state = it.take(2))) },
                label = { Text("State") }, singleLine = true, enabled = model.canEdit,
                modifier = Modifier.weight(0.5f))
            OutlinedTextField(contact.zip, { onChange(contact.copy(zip = it.take(10))) },
                label = { Text("Zip") }, singleLine = true, enabled = model.canEdit,
                modifier = Modifier.weight(0.6f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(contact.phone, { onChange(contact.copy(phone = it)) }, label = { Text("Phone") },
                singleLine = true, enabled = model.canEdit, modifier = Modifier.weight(1f))
            OutlinedTextField(contact.email, { onChange(contact.copy(email = it)) }, label = { Text("Email") },
                singleLine = true, enabled = model.canEdit, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val options: List<Pair<ContactPreference?, String>> =
                listOf<Pair<ContactPreference?, String>>(null to "No preference") +
                    ContactPreference.entries.map { it to it.displayName }
            DropdownPicker(
                options, options.firstOrNull { it.first == contact.preference }, { it.second },
                enabled = model.canEdit, placeholder = "No preference",
            ) { onChange(contact.copy(preference = it.first)) }
            if (model.canEdit) {
                Button(onClick = onSave) { Text("Save contact") }
            }
        }
    }
}

@Composable
private fun AddGuestForm(model: RegisterModel, congregationId: String) {
    var name by remember { mutableStateOf("") }
    var gender by remember { mutableStateOf<Gender?>(null) }
    var birthdate by remember { mutableStateOf("") }
    var shirt by remember { mutableStateOf(ShirtSize.AM) }
    var positions by remember { mutableStateOf(emptyList<String>()) }
    var tribe by remember { mutableStateOf(false) }
    val tier = model.season.ageTierFor(birthdate.ifBlank { null })

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(name, { name = it }, placeholder = { Text("Guest or volunteer name") },
                singleLine = true, modifier = Modifier.weight(1.4f))
            OutlinedTextField(birthdate, { birthdate = it }, label = { Text("Birthdate (children)") },
                placeholder = { Text("YYYY-MM-DD") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        tierHint(model, tier).takeIf { it.isNotEmpty() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            GenderPicker(gender, enabled = true) { gender = it }
            if (tier != AgeTier.UNDER_3) ShirtPicker(shirt, enabled = true) { shirt = it }
            BusyButton(
                model, "add-guest", "Add",
                enabled = name.isNotBlank() && gender != null &&
                    (birthdate.isBlank() || isValidBirthdate(birthdate)),
                onClick = {
                    val chosenGender = gender ?: return@BusyButton
                    model.mutate("add-guest") {
                        addGuest(
                            congregationId,
                            UpsertGuestRequest(
                                name,
                                if (tier == AgeTier.UNDER_3) null else shirt,
                                birthdate.ifBlank { null },
                                chosenGender,
                                positions = positions,
                                tribeLeaderWilling = tribe,
                            ),
                        )
                    }
                    name = ""
                    birthdate = ""
                    gender = null
                    positions = emptyList()
                    tribe = false
                },
            )
        }
        if (tier == AgeTier.AGE_9_PLUS) {
            VolunteerFields(
                model, positions, tribe, enabled = true,
                onPositionsChange = { positions = it },
                onTribeChange = { tribe = it },
            )
        }
    }
}
