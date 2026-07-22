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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.markdrew.biblebowl.api.CongregationDto
import net.markdrew.biblebowl.api.Role
import net.markdrew.biblebowl.api.RoleGrant
import net.markdrew.biblebowl.api.ScopeType
import net.markdrew.biblebowl.api.UserDto
import net.markdrew.biblebowl.api.division
import net.markdrew.biblebowl.app.ui.LocalSeason
import net.markdrew.biblebowl.client.TbbApi

/**
 * User management (docs/gui-redesign.md §5G): search users, view their role grants, grant/revoke
 * with a congregation picker for COACH. This closes the coach flow's "contact us" loop — an admin
 * finds the user and grants COACH scoped to the existing congregation. Gated on USER_MANAGE;
 * every mutation is re-checked server-side (ROLE_GRANT), including the you-can't-revoke-your-own-
 * admin guard, which the UI mirrors by hiding that Revoke button. Compose port of the web
 * AdminUsersScreen.
 */
@Composable
fun AdminUsersScreen(api: TbbApi, currentUser: UserDto?, onUserChange: (UserDto) -> Unit) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<UserDto>>(emptyList()) }
    var searched by remember { mutableStateOf(false) }
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
        runCatching { api.searchUsers(q) }.onSuccess {
            results = it
            searched = true
        }
    }

    /** Replaces one user's card data after a grant/revoke. */
    fun updateUser(updated: UserDto) {
        results = results.map { if (it.id == updated.id) updated else it }
        // Editing yourself (e.g. granting yourself REGISTRAR) changes what the app should show.
        if (updated.id == currentUser?.id) {
            scope.launch { runCatching { api.refreshUser() }.onSuccess { onUserChange(it) } }
        }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Manage users", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "Search by name or email, then grant or revoke roles.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(query, { query = it }, placeholder = { Text("Search users by name or email…") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        if (results.isEmpty() && searched) {
            Text("No matching users.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(results, key = { it.id }) { user ->
                UserCard(api, user, currentUser, onUpdated = ::updateUser)
            }
        }
    }
}

@Composable
private fun UserCard(api: TbbApi, user: UserDto, currentUser: UserDto?, onUpdated: (UserDto) -> Unit) {
    val season = LocalSeason.current
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(user.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                user.email + (user.division(season)?.let { " · ${it.displayName}" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (user.roles.isEmpty()) {
                Text("Contestant (default) — no explicit grants",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            user.roles.forEach { grant -> GrantLine(api, user, currentUser, grant, onUpdated) }
            GrantForm(api, user, onUpdated)
        }
    }
}

@Composable
private fun GrantLine(
    api: TbbApi,
    user: UserDto,
    currentUser: UserDto?,
    grant: RoleGrant,
    onUpdated: (UserDto) -> Unit,
) {
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(grant.role.displayName, style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        Text(grantScopeLabel(user, grant), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        // Mirror of the server's lockout guard: no revoking your own GLOBAL ADMIN.
        val isOwnAdmin = user.id == currentUser?.id &&
            grant.role == Role.ADMIN && grant.scopeType == ScopeType.GLOBAL
        if (!isOwnAdmin) {
            OutlinedButton(enabled = !busy, onClick = {
                busy = true
                error = null
                scope.launch {
                    try {
                        onUpdated(api.revokeRole(user.id, grant))
                    } catch (e: Throwable) {
                        error = e.message ?: "Revoke failed"
                    }
                    busy = false
                }
            }) { Text("Revoke") }
        }
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
}

private fun grantScopeLabel(user: UserDto, grant: RoleGrant): String = when (grant.scopeType) {
    ScopeType.GLOBAL -> "everywhere"
    ScopeType.SELF -> "self"
    // The server resolves congregation names into UserDto.congregationNames; the short-id
    // fallback covers a grant whose congregation no longer exists.
    else -> grant.scopeType.name.lowercase() +
        (grant.scopeId?.let { " ${user.congregationNames[it] ?: it.take(8)}" } ?: "")
}

/** Role picker + (for COACH) a congregation typeahead + Grant button. */
@Composable
private fun GrantForm(api: TbbApi, user: UserDto, onUpdated: (UserDto) -> Unit) {
    // CONTESTANT is everyone's default — granting it explicitly does nothing useful.
    val roles = listOf(Role.COACH, Role.REGISTRAR, Role.GRADER, Role.ADMIN)
    var role by remember { mutableStateOf(Role.COACH) }
    var congregation by remember { mutableStateOf<CongregationDto?>(null) }
    var congQuery by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<CongregationDto>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(congQuery, role) {
        suggestions = emptyList()
        if (role != Role.COACH || congQuery.trim().length < 2 || congregation != null) return@LaunchedEffect
        delay(300)
        runCatching { api.searchCongregations(congQuery.trim()) }
            .onSuccess { suggestions = it.take(5) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DropdownPicker(roles, role, { it.displayName }, enabled = true) {
                role = it
                congregation = null
                congQuery = ""
            }
            if (role == Role.COACH) {
                OutlinedTextField(
                    congQuery,
                    {
                        congQuery = it
                        congregation = null
                    },
                    placeholder = { Text("Congregation…") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Button(
                enabled = !busy && (role != Role.COACH || congregation != null),
                onClick = {
                    val grant =
                        if (role == Role.COACH) RoleGrant(role, ScopeType.CONGREGATION, congregation?.id)
                        else RoleGrant(role)
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            onUpdated(api.grantRole(user.id, grant))
                        } catch (e: Throwable) {
                            error = e.message ?: "Grant failed"
                        }
                        busy = false
                    }
                },
            ) { Text("Grant") }
        }
        suggestions.forEach { cong ->
            OutlinedButton(onClick = {
                congregation = cong
                congQuery = "${cong.name} — ${cong.city}"
                suggestions = emptyList()
            }) { Text("${cong.name} — ${cong.city}") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}
