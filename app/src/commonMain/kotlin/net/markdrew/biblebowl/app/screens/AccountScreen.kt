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
import net.markdrew.biblebowl.api.Division
import net.markdrew.biblebowl.api.Permission
import net.markdrew.biblebowl.api.UpdateProfileRequest
import net.markdrew.biblebowl.api.UserDto
import net.markdrew.biblebowl.api.division
import net.markdrew.biblebowl.api.divisionForBirthdate
import net.markdrew.biblebowl.api.isValidBirthdate
import net.markdrew.biblebowl.app.ui.LocalSeason
import net.markdrew.biblebowl.client.TbbApi

/**
 * Account screen (docs/gui-redesign.md §5H): editable profile (display name + the birthdate/adult
 * fields that drive division eligibility), roles held, sign out. Claiming a roster entry by
 * coach-shared code arrives with registration (Phase 4).
 */
@Composable
fun AccountScreen(
    api: TbbApi,
    user: UserDto?,
    onUserChange: (UserDto) -> Unit,
    onSignOut: () -> Unit,
    onOpenSeasonAdmin: () -> Unit,
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

        if (Permission.SEASON_MANAGE in current.permissions) {
            OutlinedButton(onClick = onOpenSeasonAdmin, modifier = Modifier.fillMaxWidth()) {
                Text("Season settings")
            }
            ClearPdfCacheButton(api)
        }
        OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) { Text("Sign out") }
    }
}

/** Display name plus the adult/birthdate eligibility fields, saved via `PUT /auth/me`. */
@Composable
private fun ProfileCard(api: TbbApi, user: UserDto, onUserChange: (UserDto) -> Unit) {
    var name by remember(user) { mutableStateOf(user.displayName) }
    var adult by remember(user) { mutableStateOf(user.adult) }
    var birthdate by remember(user) { mutableStateOf(user.birthdate ?: "") }
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
