package net.markdrew.biblebowl.app.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.markdrew.biblebowl.api.UserDto

/**
 * Account screen (docs/gui-redesign.md §5H): profile, roles held, sign out. Claiming a roster
 * entry by coach-shared code arrives with registration (Phase 4).
 */
@Composable
fun AccountScreen(user: UserDto?, onSignOut: () -> Unit) {
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

        OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) { Text("Sign out") }
    }
}
