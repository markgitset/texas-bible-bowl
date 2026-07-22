package net.markdrew.biblebowl.app.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.markdrew.biblebowl.api.AgeTier
import net.markdrew.biblebowl.api.CongregationDto
import net.markdrew.biblebowl.api.CreateCongregationRequest
import net.markdrew.biblebowl.api.MyRegistrationResponse
import net.markdrew.biblebowl.api.RegistrationDto
import net.markdrew.biblebowl.api.RegistrationStatus
import net.markdrew.biblebowl.api.RegistrationUpdateResponse
import net.markdrew.biblebowl.api.Role
import net.markdrew.biblebowl.api.ScopeType
import net.markdrew.biblebowl.api.SeasonDto
import net.markdrew.biblebowl.api.UpdateCongregationRequest
import net.markdrew.biblebowl.api.UserDto
import net.markdrew.biblebowl.api.ageTierFor
import net.markdrew.biblebowl.api.contestantCount
import net.markdrew.biblebowl.api.division
import net.markdrew.biblebowl.api.divisionLabel
import net.markdrew.biblebowl.api.feeCentsFor
import net.markdrew.biblebowl.api.feesNote
import net.markdrew.biblebowl.api.formatCents
import net.markdrew.biblebowl.api.formatIsoDate
import net.markdrew.biblebowl.api.isInexperienced
import net.markdrew.biblebowl.api.multiSite
import net.markdrew.biblebowl.api.registrationFeeLines
import net.markdrew.biblebowl.api.siteFor
import net.markdrew.biblebowl.client.ApiException
import net.markdrew.biblebowl.client.TbbApi

/**
 * Coach registration flow (docs/gui-redesign.md §5E): a 4-step stepper — congregation → teams →
 * roster → review & submit — on a single route. Resumability comes from the server
 * (`GET /registration/mine`), not the route: opening the screen lands on the first incomplete
 * step. The route itself only requires sign-in; step 1 is exactly where a signed-in non-coach
 * becomes a coach (creating a new congregation self-serve grants the scoped COACH role), and
 * every mutation is scope-checked server-side regardless of what's rendered. Compose port of the
 * web app's RegisterScreen.
 */

/** Shared state + actions for the register steps (the web object's fields, Compose-observable). */
internal class RegisterModel(
    val api: TbbApi,
    user: UserDto?,
    val scope: CoroutineScope,
) {
    var season: SeasonDto by mutableStateOf(net.markdrew.biblebowl.api.FALLBACK_SEASON)
    var loaded: MyRegistrationResponse? by mutableStateOf(null)
    var step: Int by mutableStateOf(1)
    var error: String? by mutableStateOf(null)
    var loadError: String? by mutableStateOf(null)
    var confirm: ConfirmRequest? by mutableStateOf(null)

    val congregation: CongregationDto? get() = loaded?.congregations?.firstOrNull()
    val registration: RegistrationDto? get() = loaded?.registration

    /** A globally-scoped admin (may edit outside the window, and may change a set congregation code). */
    val isAdmin: Boolean =
        user?.roles?.any { it.role == Role.ADMIN && it.scopeType == ScopeType.GLOBAL } == true

    /** Admins may keep editing outside the window (the server exempts them too). */
    val canEdit: Boolean get() = loaded?.windowOpen == true || isAdmin

    /** Team members plus individual (adult) contestants. */
    val contestants: Int get() = registration?.contestantCount ?: 0

    val guests get() = registration?.guests ?: emptyList()

    /** The registration's season (falls back to the current season before the draft exists). */
    val seasonYear: String get() = registration?.seasonYear ?: season.eventYear.toString()

    fun firstIncompleteStep(): Int = when {
        congregation == null -> 1
        // A multi-site season needs the event site chosen (part of the congregation step).
        season.multiSite && season.siteFor(registration?.siteId) == null -> 1
        registration?.teams.isNullOrEmpty() && registration?.individuals.isNullOrEmpty() &&
            registration?.unassigned.isNullOrEmpty() && loaded?.returningCandidates.isNullOrEmpty() -> 2
        contestants == 0 && guests.isEmpty() -> 3
        registration?.unassigned.isNullOrEmpty() == false -> 3 // land on the roster to place them
        else -> 4
    }

    /**
     * The furthest step the current data supports (completed steps stay clickable). The roster step
     * is reachable without any teams: adults are added there as individuals, so an adults-only
     * congregation never touches the teams step. Guests count as registered people here too — a
     * guest-only registration (e.g. all volunteers) can still be reviewed and submitted.
     */
    fun maxReachableStep(): Int = when {
        congregation == null -> 1
        contestants == 0 && guests.isEmpty() -> 3
        else -> STEPS
    }

    fun goTo(target: Int) {
        step = target
        error = null
    }

    suspend fun load() {
        try {
            loaded = api.myRegistration()
            step = firstIncompleteStep()
        } catch (e: Throwable) {
            loadError = e.message ?: "Something went wrong"
        }
    }

    /**
     * The key of the control whose mutation is in flight — its button renders a spinner and blocks
     * re-clicks (see [BusyButton]). Null when idle or for keyless autosaves (field edits).
     */
    var busyKey: String? by mutableStateOf(null)

    /**
     * Runs a mutation and adopts the returned registration + returning candidates (API errors show
     * inline). Every mutation response carries the recomputed candidate list, so one round trip
     * keeps the roster step in sync — enrolling consumes a candidate, and removing a prior-season
     * contestant re-offers them. [busy] marks the triggering control busy until the call lands.
     */
    fun mutate(busy: String? = null, call: suspend TbbApi.() -> RegistrationUpdateResponse) {
        busy?.let { busyKey = it }
        scope.launch {
            try {
                val updated = api.call()
                loaded = loaded?.copy(
                    registration = updated.registration,
                    returningCandidates = updated.returningCandidates,
                )
                error = null
            } catch (e: Throwable) {
                error = e.message ?: "Something went wrong"
            } finally {
                if (busy != null && busyKey == busy) busyKey = null
            }
        }
    }

    companion object {
        const val STEPS = 4
    }
}

private const val STEPS = RegisterModel.STEPS

@Composable
fun RegisterScreen(api: TbbApi, user: UserDto?) {
    val scope = rememberCoroutineScope()
    val model = remember(api, user?.id) { RegisterModel(api, user, scope) }
    model.season = LocalSeasonValue()

    LaunchedEffect(model) { model.load() }

    Column(
        Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Register my teams",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )

        model.loadError?.let {
            Text("Could not load your registration: $it", color = MaterialTheme.colorScheme.error)
            return@Column
        }
        if (model.loaded == null) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Column
        }

        WindowBanner(model)
        Stepper(model)
        model.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        when (model.step.coerceAtMost(model.maxReachableStep())) {
            1 -> CongregationStep(model)
            2 -> TeamsStep(model)
            3 -> RosterStep(model)
            else -> ReviewStep(model)
        }
    }
    ConfirmDialogHost(model.confirm) { model.confirm = null }
}

/** Reads the season composition local (kept out of RegisterModel's constructor so it can refresh). */
@Composable
private fun LocalSeasonValue(): SeasonDto = net.markdrew.biblebowl.app.ui.LocalSeason.current

@Composable
private fun WindowBanner(model: RegisterModel) {
    if (model.canEdit) return
    val season = model.season
    val message = when {
        season.registrationOpensOn == null ->
            "Registration for ${season.eventYear} hasn't opened yet — check back soon."
        else ->
            "Registration is closed (it ran ${formatIsoDate(season.registrationOpensOn)} through " +
                "${formatIsoDate(season.registrationClosesOn)}). Your registration is shown read-only."
    }
    Text(message, color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun Stepper(model: RegisterModel) {
    val labels = listOf("Congregation", "Teams", "Roster", "Review & submit")
    val reachable = model.maxReachableStep()
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        labels.forEachIndexed { i, label ->
            val n = i + 1
            FilterChip(
                selected = n == model.step,
                enabled = n <= reachable,
                onClick = { model.goTo(n) },
                label = { Text("$n. $label") },
            )
        }
    }
}

@Composable
private fun NextButton(model: RegisterModel, label: String, nextStep: Int) {
    Button(onClick = { model.goTo(nextStep) }) { Text(label) }
}

/**
 * A mutation button with instant feedback: while the mutation keyed [key] is in flight
 * (see [RegisterModel.mutate]'s `busy` parameter) it shows a small spinner and blocks re-clicks.
 */
@Composable
internal fun BusyButton(
    model: RegisterModel,
    key: String,
    label: String,
    outlined: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val busy = model.busyKey == key
    val content: @Composable RowScope.() -> Unit = {
        if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        else Text(label)
    }
    if (outlined) OutlinedButton(onClick = onClick, enabled = enabled && !busy, content = content)
    else Button(onClick = onClick, enabled = enabled && !busy, content = content)
}

// --- Step 1: congregation ---------------------------------------------------------------

@Composable
private fun CongregationStep(model: RegisterModel) {
    val existing = model.congregation
    if (existing != null) {
        if (model.canEdit) CongregationEditForm(model, existing) else CongregationSummary(existing)
        SiteCard(model, existing)
        NextButton(model, "Continue to teams", 2)
        return
    }

    Text(
        "Registration is done by congregation. Find yours, or start it if it's new.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    NewCongregationCard(model)
    FindCongregationCard(model)
}

/** "Austin, TX" — or just "Austin" for congregations created before state was collected. */
private fun cityStateLine(cong: CongregationDto): String =
    if (cong.state.isBlank()) cong.city else "${cong.city}, ${cong.state}"

private fun contactUsMessage(serverMessage: String?): String =
    (serverMessage?.let { "$it. " } ?: "") +
        "That congregation is already in the system — contact us " +
        "(texasbiblebowl.org/contact) and an admin will add you as its coach."

/** Start a new congregation (self-serve: creating one makes you its coach). */
@Composable
private fun NewCongregationCard(model: RegisterModel) {
    var name by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var codeEdited by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf("") }
    var city by remember { mutableStateOf("") }
    var state by remember { mutableStateOf("TX") }
    var zip by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var formError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // Suggest a code from the name until the coach types their own (relaunch-per-keystroke
    // is the debounce: the delay restarts on every name change).
    LaunchedEffect(name) {
        if (codeEdited || name.isBlank()) return@LaunchedEffect
        delay(400)
        runCatching { model.api.suggestCongregationCode(name.trim()) }
            .onSuccess { if (!codeEdited) code = it }
    }

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Start a new congregation", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold)
            OutlinedTextField(name, { name = it }, label = { Text("Congregation name") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                code, { code = it.uppercase().take(2); codeEdited = true },
                label = { Text("Code") }, singleLine = true, modifier = Modifier.width(120.dp),
            )
            Text(
                "A two-letter shorthand for your congregation (suggested from the name — " +
                    "\"West Bexar County Church of Christ\" → WB). You can change it now; once " +
                    "saved, only an admin can.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(address, { address = it }, label = { Text("Mailing address") },
                placeholder = { Text("Street address or PO Box") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(city, { city = it }, label = { Text("City") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(state, { state = it.take(2) }, label = { Text("State") },
                    singleLine = true, modifier = Modifier.width(90.dp))
                OutlinedTextField(zip, { zip = it.take(10) }, label = { Text("ZIP") },
                    singleLine = true, modifier = Modifier.width(120.dp))
                OutlinedTextField(phone, { phone = it.take(30) }, label = { Text("Phone (optional)") },
                    singleLine = true, modifier = Modifier.weight(1f))
            }
            formError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            // Deliberately enabled even outside the window: creating a congregation is
            // onboarding, not registration (the server draws the same line).
            Button(
                enabled = !creating && listOf(name, address, city, state, zip).none { it.isBlank() },
                onClick = {
                    creating = true
                    formError = null
                    scope.launch {
                        try {
                            model.api.createCongregation(
                                CreateCongregationRequest(
                                    name = name, city = city, state = state,
                                    mailingAddress = address, zip = zip, phone = phone, code = code,
                                )
                            )
                            model.api.refreshUser() // pick up the new scoped COACH grant
                            model.loaded = model.api.myRegistration()
                            // On a multi-site season, stay on step 1: the site picker is here.
                            model.step = if (model.season.multiSite) 1 else 2
                        } catch (e: Throwable) {
                            // Only the name+city dupe gets the "contact us to claim it" flow; a
                            // taken code or any other error shows the server's message as-is.
                            val ex = e as? ApiException
                            formError = if (ex?.status == 409 && ex.errorCode == "congregation_exists")
                                contactUsMessage(e.message)
                            else e.message ?: "Something went wrong"
                        }
                        creating = false
                    }
                },
            ) { Text("Create & continue") }
        }
    }
}

/** Find an existing congregation (claiming one goes through an admin). */
@Composable
private fun FindCongregationCard(model: RegisterModel) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<CongregationDto>>(emptyList()) }
    var claimMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(query) {
        claimMessage = null
        val q = query.trim()
        if (q.length < 2) {
            results = emptyList()
            return@LaunchedEffect
        }
        delay(300)
        runCatching { model.api.searchCongregations(q) }.onSuccess { results = it }
    }

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Find your congregation", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold)
            OutlinedTextField(query, { query = it }, placeholder = { Text("Search by name or city…") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            val message = claimMessage
            when {
                message != null -> Text(message, color = MaterialTheme.colorScheme.tertiary)
                query.trim().length >= 2 && results.isEmpty() ->
                    Text("No match — start it above.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> results.forEach { c ->
                    OutlinedButton(onClick = { claimMessage = contactUsMessage(null) }) {
                        Text("${c.name} — ${c.city}")
                    }
                }
            }
        }
    }
}

/** Read-only congregation card, shown once registration has closed (or before it opens). */
@Composable
private fun CongregationSummary(existing: CongregationDto) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Your congregation", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${existing.name} — ${cityStateLine(existing)}")
                if (existing.code.isNotBlank()) {
                    Text("  ${existing.code}", fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.secondary)
                }
            }
            if (existing.mailingAddress.isNotBlank()) {
                Text(
                    "${existing.mailingAddress}, ${cityStateLine(existing)} ${existing.zip}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (existing.phone.isNotBlank()) {
                Text(existing.phone, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * Editable congregation card, shown while registration is open. Name, address, city, state, and
 * ZIP are freely editable. The two-letter congregation code is set-once for a coach: editable
 * while it's still blank, then locked (only an admin can change it) — the server enforces this.
 */
@Composable
private fun CongregationEditForm(model: RegisterModel, existing: CongregationDto) {
    var name by remember(existing) { mutableStateOf(existing.name) }
    var code by remember(existing) { mutableStateOf(existing.code) }
    var address by remember(existing) { mutableStateOf(existing.mailingAddress) }
    var city by remember(existing) { mutableStateOf(existing.city) }
    var state by remember(existing) { mutableStateOf(existing.state) }
    var zip by remember(existing) { mutableStateOf(existing.zip) }
    var phone by remember(existing) { mutableStateOf(existing.phone) }
    var saved by remember { mutableStateOf(false) }
    var formError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val codeLocked = existing.code.isNotBlank() && !model.isAdmin
    // A congregation that predates codes has none — suggest one from its name.
    LaunchedEffect(existing.id) {
        if (!codeLocked && existing.code.isBlank()) {
            runCatching { model.api.suggestCongregationCode(existing.name) }
                .onSuccess { if (code.isBlank()) code = it }
        }
    }

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Your congregation", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold)
            Text("Fix a typo in your congregation's details below.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(name, { name = it }, label = { Text("Congregation name") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                code, { code = it.uppercase().take(2) }, label = { Text("Code") },
                singleLine = true, enabled = !codeLocked, modifier = Modifier.width(120.dp),
            )
            Text(
                if (codeLocked) "Your two-letter code is set — contact us and an admin can change it."
                else "Pick a unique two-letter code for your congregation. Once you save it, only " +
                    "an admin can change it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(address, { address = it }, label = { Text("Mailing address") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(city, { city = it }, label = { Text("City") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(state, { state = it.take(2) }, label = { Text("State") },
                    singleLine = true, modifier = Modifier.width(90.dp))
                OutlinedTextField(zip, { zip = it.take(10) }, label = { Text("ZIP") },
                    singleLine = true, modifier = Modifier.width(120.dp))
                OutlinedTextField(phone, { phone = it.take(30) }, label = { Text("Phone (optional)") },
                    singleLine = true, modifier = Modifier.weight(1f))
            }
            formError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (saved) Text("Saved.", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            Button(
                enabled = listOf(name, address, city, state, zip).none { it.isBlank() },
                onClick = {
                    saved = false
                    formError = null
                    scope.launch {
                        try {
                            model.api.updateCongregation(
                                existing.id,
                                UpdateCongregationRequest(
                                    name = name, city = city, state = state,
                                    mailingAddress = address, zip = zip, phone = phone, code = code,
                                ),
                            )
                            // Refresh so the embedded registration.congregation (review step) matches.
                            model.loaded = model.api.myRegistration()
                            saved = true
                        } catch (e: Throwable) {
                            formError = e.message ?: "Something went wrong"
                        }
                    }
                },
            ) { Text("Save changes") }
        }
    }
}

/**
 * Event-site picker (item F6), shown only when the season runs at two or more sites. Picking
 * saves immediately (creating the draft registration if this is the first thing the coach
 * does); a multi-site registration can't be submitted until a site is chosen.
 */
@Composable
private fun SiteCard(model: RegisterModel, cong: CongregationDto) {
    val season = model.season
    if (!season.multiSite) return
    val chosen = season.siteFor(model.registration?.siteId)
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Event site", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "This season runs at ${season.sites.size} locations — choose the one your " +
                    "congregation attends.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            season.sites.forEach { site ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().selectable(
                        selected = site.id == chosen?.id,
                        enabled = model.canEdit,
                        onClick = { model.mutate { setRegistrationSite(cong.id, site.id) } },
                    ),
                ) {
                    RadioButton(
                        selected = site.id == chosen?.id,
                        enabled = model.canEdit,
                        onClick = { model.mutate { setRegistrationSite(cong.id, site.id) } },
                    )
                    Column {
                        Text(site.name)
                        if (site.address.isNotBlank()) {
                            Text(site.address, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (chosen == null) {
                Text("Required before you can submit.", color = MaterialTheme.colorScheme.tertiary,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

// --- Step 2: teams ----------------------------------------------------------------------

@Composable
private fun TeamsStep(model: RegisterModel) {
    val cong = model.congregation ?: return
    Text(
        "A team has up to 4 contestants (grades 3–12, from birthdates) and competes in the " +
            "division of its highest member. Adults aren't placed on a team — add them as " +
            "individual contestants on the roster step.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    model.registration?.teams?.forEach { team ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            BlurSaveField(
                initial = team.name,
                label = "Team name",
                enabled = model.canEdit,
                modifier = Modifier.weight(1f),
            ) { newName -> model.mutate { renameTeam(team.id, newName) } }
            TeamDivisionBadge(model, team)
            Text("${team.members.size}/4", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (model.canEdit) {
                BusyButton(model, "delete-team-${team.id}", "Delete", outlined = true, onClick = {
                    if (team.members.isEmpty()) {
                        model.mutate("delete-team-${team.id}") { deleteTeam(team.id) }
                    } else {
                        model.confirm = ConfirmRequest(
                            "Delete ${team.name}? Its ${team.members.size} contestant" +
                                "${if (team.members.size == 1) "" else "s"} won't be deleted — " +
                                "they'll move to Unassigned so you can place them on another team.",
                            confirmLabel = "Delete",
                        ) { model.mutate("delete-team-${team.id}") { deleteTeam(team.id) } }
                    }
                })
            }
        }
    }

    if (model.canEdit) {
        var newName by remember { mutableStateOf("") }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedTextField(newName, { newName = it }, placeholder = { Text("New team name") },
                singleLine = true, modifier = Modifier.weight(1f))
            BusyButton(
                model, "add-team", "Add team",
                enabled = newName.isNotBlank(),
                onClick = {
                    model.mutate("add-team") { addTeam(cong.id, newName) }
                    newName = ""
                },
            )
        }
    }

    NextButton(model, "Continue to roster", 3)
}

@Composable
internal fun TeamDivisionBadge(model: RegisterModel, team: net.markdrew.biblebowl.api.TeamDto) {
    val division = team.division(model.season)
    Text(
        division?.let { divisionLabel(it, team.isInexperienced(model.seasonYear)) } ?: "Empty",
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = if (division == null) MaterialTheme.colorScheme.onSurfaceVariant
        else MaterialTheme.colorScheme.primary,
    )
}

// --- Step 4: review & submit ------------------------------------------------------------

/** "3 × $85 + 1 × $65 = $320" — one review row's fee line, tiered by age (under-3s are free). */
internal fun tierFeeMath(season: SeasonDto, birthdates: List<String?>, contestant: Boolean): String {
    val tiers = birthdates.groupingBy { season.ageTierFor(it) }.eachCount()
    val parts = AgeTier.entries.mapNotNull { tier ->
        val n = tiers[tier] ?: return@mapNotNull null
        if (tier == AgeTier.UNDER_3) "$n free" else "$n × ${formatCents(season.feeCentsFor(tier, contestant))}"
    }
    val total = tiers.entries.fold(0 as Int?) { sum, (tier, n) ->
        sum?.let { s -> season.feeCentsFor(tier, contestant)?.times(n)?.plus(s) }
    }
    return "${parts.joinToString(" + ")} = ${formatCents(total)}"
}

/** "2 contestants (9+) × $85 + 1 guest (3–8) × $65 + 1 guest (under 3) free" — tiers in use. */
internal fun totalBreakdown(season: SeasonDto, reg: RegistrationDto): String =
    registrationFeeLines(season, reg).joinToString(" + ") { line ->
        val noun = (if (line.contestant) "contestant" else "guest") + (if (line.count == 1) "" else "s")
        val tierNote = when (line.tier) {
            AgeTier.AGE_9_PLUS -> "(9+)"
            AgeTier.AGE_3_TO_8 -> "(3–8)"
            AgeTier.UNDER_3 -> "(under 3)"
        }
        if (line.eachCents == 0) "${line.count} $noun $tierNote free"
        else "${line.count} $noun $tierNote × ${formatCents(line.eachCents)}"
    }

@Composable
private fun ReviewStep(model: RegisterModel) {
    val reg = model.registration ?: return
    val season = model.season
    val cong = reg.congregation

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "${cong.name} — ${cityStateLine(cong)} · ${reg.seasonYear} season",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            season.siteFor(reg.siteId)?.let { site ->
                Text("Event site: ${site.name}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            reg.teams.forEach { team ->
                // Visiting (combo-team) members are named but their own congregation pays for
                // them, so only home members enter this row's fee math.
                ReviewRow(
                    title = team.name,
                    division = team.division(season)
                        ?.let { divisionLabel(it, team.isInexperienced(model.seasonYear)) } ?: "—",
                    people = team.members.joinToString {
                        it.name + (if (it.isInexperienced(model.seasonYear)) " (1st year)" else "") +
                            (it.congregationName?.let { c -> " (from $c)" } ?: "")
                    },
                    fees = tierFeeMath(
                        season,
                        team.members.filter { it.congregationId == null }.map { it.birthdate },
                        contestant = true,
                    ),
                )
            }
            if (reg.unassigned.isNotEmpty()) {
                ReviewRow(
                    title = "Unassigned", division = "—",
                    people = reg.unassigned.joinToString { it.name },
                    fees = tierFeeMath(season, reg.unassigned.map { it.birthdate }, contestant = true),
                )
            }
            if (reg.awayMembers.isNotEmpty()) {
                ReviewRow(
                    title = "On combo teams", division = "—",
                    people = reg.awayMembers.joinToString {
                        "${it.entry.name} (${it.teamName}, ${it.congregationName})"
                    },
                    fees = tierFeeMath(season, reg.awayMembers.map { it.entry.birthdate }, contestant = true),
                )
            }
            if (reg.individuals.isNotEmpty()) {
                ReviewRow(
                    title = "Individuals", division = "Adult",
                    people = reg.individuals.joinToString { it.name },
                    fees = tierFeeMath(season, reg.individuals.map { it.birthdate }, contestant = true),
                )
            }
            if (reg.guests.isNotEmpty()) {
                ReviewRow(
                    title = "Guests", division = "—",
                    people = reg.guests.joinToString {
                        it.name + when (season.ageTierFor(it.birthdate)) {
                            AgeTier.AGE_9_PLUS -> ""
                            AgeTier.AGE_3_TO_8 -> " (3–8)"
                            AgeTier.UNDER_3 -> " (under 3)"
                        }
                    },
                    fees = tierFeeMath(season, reg.guests.map { it.birthdate }, contestant = false),
                )
            }

            val guestPart = reg.guests.size
                .takeIf { it > 0 }?.let { " + $it guest${if (it == 1) "" else "s"}" } ?: ""
            Text(
                "Total due (${model.contestants} contestant" +
                    "${if (model.contestants == 1) "" else "s"}$guestPart): ${formatCents(reg.totalCents)}",
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Everyone pays by age: 9+ ${formatCents(season.priceContestantCents)} " +
                    "(volunteers ${formatCents(season.priceVolunteerCents)}), ages 3–8 " +
                    "${formatCents(season.priceChildCents)}, under 3 free. One t-shirt included " +
                    "for everyone but under-3s; extra shirts are paid at the door.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            season.feesNote.takeIf { it.isNotEmpty() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Payment", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Mail a check payable to Texas Bible Bowl:")
            Text("Texas Bible Bowl\n10431 Remuda View Drive\nSan Antonio, TX 78254")
        }
    }

    if (reg.status == RegistrationStatus.SUBMITTED) {
        Text(
            "Submitted ${reg.submittedAt?.take(10) ?: ""} — you can keep editing and re-submit " +
                "until ${formatIsoDate(season.registrationClosesOn)}.",
            color = MaterialTheme.colorScheme.secondary,
            fontWeight = FontWeight.SemiBold,
        )
    }
    val unassignedCount = reg.unassigned.size
    if (model.canEdit && unassignedCount > 0) {
        Text(
            "$unassignedCount contestant${if (unassignedCount == 1) " isn't" else "s aren't"} on a " +
                "team yet. You can go back to the roster step to place them, or submit now and a " +
                "registrar will assign them to a team for you.",
            color = MaterialTheme.colorScheme.tertiary,
        )
    }
    val siteMissing = season.multiSite && season.siteFor(reg.siteId) == null
    if (model.canEdit && siteMissing) {
        Text(
            "Choose your event site on the congregation step before submitting.",
            color = MaterialTheme.colorScheme.tertiary,
        )
    }
    if (model.canEdit) {
        val label = if (reg.status == RegistrationStatus.SUBMITTED) "Update registration" else "Submit registration"
        BusyButton(model, "submit", label, onClick = {
            if (siteMissing) {
                model.goTo(1)
                return@BusyButton
            }
            if (unassignedCount > 0) {
                model.confirm = ConfirmRequest(
                    "$unassignedCount contestant${if (unassignedCount == 1) " isn't" else "s aren't"} " +
                        "on a team yet. Submit anyway and let a registrar place them, or cancel to " +
                        "go back and assign them to a team yourself?",
                    confirmLabel = "Submit anyway",
                ) { model.mutate("submit") { submitRegistration(cong.id) } }
            } else {
                model.mutate("submit") { submitRegistration(cong.id) }
            }
        })
    }
}

@Composable
private fun ReviewRow(title: String, division: String, people: String, fees: String) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(division, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary)
        }
        if (people.isNotBlank()) {
            Text(people, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(fees, style = MaterialTheme.typography.bodySmall)
    }
}
