package net.markdrew.biblebowl.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import net.markdrew.biblebowl.api.UserDto
import net.markdrew.biblebowl.app.net.TbbApi
import net.markdrew.biblebowl.app.screens.AuthScreen
import net.markdrew.biblebowl.app.screens.MainScreen
import net.markdrew.biblebowl.app.ui.TbbTheme

@Composable
fun App(api: TbbApi = remember { TbbApi() }) {
    var user by remember { mutableStateOf<UserDto?>(null) }

    TbbTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val current = user
            if (current == null) {
                AuthScreen(api, onSignedIn = { user = it })
            } else {
                MainScreen(api, current, onSignOut = { api.signOut(); user = null })
            }
        }
    }
}
