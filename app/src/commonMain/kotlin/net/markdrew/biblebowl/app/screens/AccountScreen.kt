package net.markdrew.biblebowl.app.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.markdrew.biblebowl.api.ContactInfoDto
import net.markdrew.biblebowl.api.ContactPreference
import net.markdrew.biblebowl.api.Division
import net.markdrew.biblebowl.api.PersonRelation
import net.markdrew.biblebowl.api.Permission
import net.markdrew.biblebowl.api.UpdateProfileRequest
import net.markdrew.biblebowl.api.UserDto
import net.markdrew.biblebowl.api.division
import net.markdrew.biblebowl.api.divisionForBirthdate
import net.markdrew.biblebowl.api.hasEventWidePermission
import net.markdrew.biblebowl.api.isGlobalAdmin
import net.markdrew.biblebowl.api.isValidBirthdate
import net.markdrew.biblebowl.app.navigation.Routes
import net.markdrew.biblebowl.app.ui.LocalSeason
import net.markdrew.biblebowl.client.TbbApi

/**
 * Account screen (docs/gui-redesign.md §5H): editable profile (display name + the birthdate/adult
 * fields that drive division eligibility, plus adults' event contact info), claiming a roster
 * entry by coach-shared code, roles held, sign out.
 */
@Composable
fun AccountScreen(
    api: TbbApi,
    user: UserDto?,
    onUserChange: (UserDto) -> Unit,
    onSignOut: () -> Unit,
    onOpenSeasonAdmin: () -> Unit,
    onNavigate: (String) -> Unit,
) {
    val current = user ?: return // routed here only when signed in; guard for safety
    val season = LocalSeason.current

    Column(
        Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            current.displayName,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(current.email, style = MaterialTheme.typography.bodyLarge)
        val division = current.division(season)
        Text(
            division?.let { "Division: ${it.displayName}" }
                ?: "No division yet — add your birthdate below (or mark yourself an adult).",
            style = MaterialTheme.typography.bodyMedium,
            color = if (division == null) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface,
        )

        ProfileCard(api, current, onUserChange)
        if (season.registrationEnabled || isGlobalAdmin(current.roles)) ClaimCard(api)

        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Roles", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (current.roles.isEmpty()) {
                    Text(
                        "Contestant (default)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    current.roles.forEach { grant ->
                        Text(
                            grant.role.name.lowercase().replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }

        StaffLinks(current, onNavigate)

        if (Permission.SEASON_MANAGE in current.permissions) {
            OutlinedButton(onClick = onOpenSeasonAdmin, modifier = Modifier.fillMaxWidth()) {
                Text("Season settings")
            }
            ClearPdfCacheButton(api)
        }
        OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) { Text("Sign out") }
    }
}

/**
 * Event-staff destinations, mirroring the web navbar's user menu (NavMenu.kt): the desk-family
 * screens for event-wide REGISTRATION_MANAGE holders, tester IDs also for SCORE_ENTER, user
 * management for USER_MANAGE — each behind the registration launch toggle where the web is
 * (admins preview dark features with a note).
 */
@Composable
private fun StaffLinks(user: UserDto, onNavigate: (String) -> Unit) {
    val season = LocalSeason.current
    val registrationVisible = season.registrationEnabled || isGlobalAdmin(user.roles)
    val registrar = hasEventWidePermission(user.roles, Permission.REGISTRATION_MANAGE)
    val testerAccess = registrar || hasEventWidePermission(user.roles, Permission.SCORE_ENTER)
    val links = buildList {
        if (registrationVisible && registrar) {
            add("Registration desk" to Routes.ADMIN_REGISTRATIONS)
            add("Registration counts" to Routes.ADMIN_COUNTS)
            add("Housing" to Routes.ADMIN_HOUSING)
            add("Tribes" to Routes.ADMIN_TRIBES)
        }
        if (registrationVisible && testerAccess) add("Tester IDs" to Routes.ADMIN_TESTERS)
        if (Permission.USER_MANAGE in user.permissions) add("User management" to Routes.ADMIN_USERS)
    }
    if (links.isEmpty()) return
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Event staff", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (!season.registrationEnabled && registrationVisible && (registrar || testerAccess)) {
                Text(
                    "Registration is hidden until launch — visible to you as a global admin.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            links.forEach { (label, route) ->
                OutlinedButton(onClick = { onNavigate(route) }, modifier = Modifier.fillMaxWidth()) {
                    Text(label)
                }
            }
        }
    }
}

/** Display name plus the adult/birthdate eligibility fields, saved via `PUT /auth/me`. */
@Composable
private fun ProfileCard(api: TbbApi, user: UserDto, onUserChange: (UserDto) -> Unit) {
    var name by remember(user) { mutableStateOf(user.displayName) }
    var adult by remember(user) { mutableStateOf(user.adult) }
    var birthdate by remember(user) { mutableStateOf(user.birthdate ?: "") }
    var contact by remember(user) { mutableStateOf(user.contact ?: ContactInfoDto()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val season = LocalSeason.current

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Profile", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("Display name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = adult, onCheckedChange = { adult = it })
                Text("I'm an adult (18+ or finished high school)", style = MaterialTheme.typography.bodyMedium)
            }
            if (!adult) {
                OutlinedTextField(
                    value = birthdate, onValueChange = { birthdate = it.trim() },
                    label = { Text("Birthdate (yyyy-MM-dd)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = {
                        Text("Used to place contestants in the right division each season.")
                    },
                )
            }
            val division = if (adult) Division.ADULT
                else birthdate.takeIf { it.isNotBlank() }?.let { season.divisionForBirthdate(it) }
            division?.let {
                Text("Division: ${it.displayName}", style = MaterialTheme.typography.bodySmall)
            }

            // Contact info (adults): optional event-communication details for registrars.
            // Hidden for non-adults, like the web — the values still submit either way.
            if (adult) {
                Text("Contact info", style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold)
                Text(
                    "Optional — how event organizers can reach you (only registrars see it).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(contact.address, { contact = contact.copy(address = it) },
                    label = { Text("Street address") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(contact.city, { contact = contact.copy(city = it) },
                        label = { Text("City") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(contact.state, { contact = contact.copy(state = it.take(2)) },
                        label = { Text("State") }, singleLine = true, modifier = Modifier.weight(0.5f))
                    OutlinedTextField(contact.zip, { contact = contact.copy(zip = it.take(10)) },
                        label = { Text("Zip") }, singleLine = true, modifier = Modifier.weight(0.6f))
                }
                OutlinedTextField(contact.phone, { contact = contact.copy(phone = it) },
                    label = { Text("Phone") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                val options: List<Pair<ContactPreference?, String>> =
                    listOf<Pair<ContactPreference?, String>>(null to "No preference") +
                        ContactPreference.entries.map { it to it.displayName }
                DropdownPicker(
                    options, options.firstOrNull { it.first == contact.preference }, { it.second },
                    enabled = true, placeholder = "No preference",
                ) { contact = contact.copy(preference = it.first) }
            }

            Button(
                onClick = {
                    busy = true; message = null
                    scope.launch {
                        try {
                            val updated = api.updateProfile(
                                UpdateProfileRequest(
                                    displayName = name.trim(),
                                    birthdate = birthdate.takeIf { it.isNotBlank() }?.takeUnless { adult },
                                    adult = adult,
                                    // Always sent (empty clears); the server keeps stored contact
                                    // only when the field is omitted entirely (older clients).
                                    contact = contact.copy(
                                        address = contact.address.trim(),
                                        city = contact.city.trim(),
                                        state = contact.state.trim(),
                                        zip = contact.zip.trim(),
                                        phone = contact.phone.trim(),
                                    ),
                                )
                            )
                            onUserChange(updated)
                            message = "Saved."
                        } catch (e: Throwable) {
                            message = "Save failed: ${e.message}"
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = !busy && name.isNotBlank() && (adult || isValidBirthdate(birthdate)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (busy) CircularProgressIndicator(Modifier.height(18.dp)) else Text("Save profile")
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

/**
 * Claim a roster entry by the coach-shared code, linking it to this account — that link is
 * what My Scores' owner scoping keys off (dashes/case in the code are tolerated server-side).
 */
@Composable
private fun ClaimCard(api: TbbApi) {
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Claim a contestant", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold)
            Text(
                "Enter the claim code your coach shared (like ABCD-2345) to link a contestant to this " +
                    "account and see their scores once they're released. If it's your own code and your " +
                    "email matches, it becomes you; otherwise you'll manage them (e.g. a parent claiming a child).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(code, { code = it }, placeholder = { Text("ABCD-2345") },
                    singleLine = true, modifier = Modifier.weight(1f))
                Button(
                    enabled = !busy && code.isNotBlank(),
                    onClick = {
                        busy = true
                        message = null
                        scope.launch {
                            try {
                                val result = api.claimPerson(code)
                                code = ""
                                isError = false
                                val who = if (result.relation == PersonRelation.SELF) "you" else result.person.name
                                message = "Claimed ${result.person.name} (as $who) — see My scores on the " +
                                    "Event tab once scores are released."
                            } catch (e: Throwable) {
                                isError = true
                                message = "Claim failed: ${e.message}"
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) { Text("Claim") }
            }
            message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isError) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.secondary,
                    fontWeight = if (isError) FontWeight.Normal else FontWeight.SemiBold,
                )
            }
        }
    }
}

/**
 * Admin: drops the server's compiled-PDF cache so every study document regenerates on its next
 * download — for after a generation-code change (season/word-list changes invalidate on their own).
 */
@Composable
private fun ClearPdfCacheButton(api: TbbApi) {
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    OutlinedButton(
        onClick = {
            busy = true; message = null
            scope.launch {
                try {
                    val cleared = api.clearPdfCache().cleared
                    isError = false
                    message = "Cleared $cleared cached PDF(s) — next downloads regenerate."
                } catch (e: Throwable) {
                    isError = true
                    message = "Clear failed: ${e.message}"
                } finally {
                    busy = false
                }
            }
        },
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (busy) CircularProgressIndicator(Modifier.height(18.dp)) else Text("Clear PDF cache")
    }
    message?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
    }
}
