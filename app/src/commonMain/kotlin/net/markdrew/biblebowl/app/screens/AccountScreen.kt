package net.markdrew.biblebowl.app.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.markdrew.biblebowl.api.Permission
import net.markdrew.biblebowl.api.UserDto
import net.markdrew.biblebowl.client.TbbApi

/**
 * Account screen (docs/gui-redesign.md §5H): profile, roles held, sign out. Claiming a roster
 * entry by coach-shared code arrives with registration (Phase 4).
 */
@Composable
fun AccountScreen(api: TbbApi, user: UserDto?, onSignOut: () -> Unit, onOpenSeasonAdmin: () -> Unit) {
    val current = user ?: return // routed here only when signed in; guard for safety

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
        current.division?.let {
            Text("Division: ${it.displayName}", style = MaterialTheme.typography.bodyMedium)
        }

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
