package net.markdrew.biblebowl.web

import kotlinx.browser.localStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.markdrew.biblebowl.api.AuthResponse
import net.markdrew.biblebowl.api.FALLBACK_SEASON
import net.markdrew.biblebowl.api.SeasonDto
import net.markdrew.biblebowl.api.UserDto
import net.markdrew.biblebowl.client.TbbApi

/** App-wide client state: the shared [TbbApi], the current season, and the signed-in user. */
object Session {
    private const val TOKEN_KEY = "tbb.token"

    val api = TbbApi()

    var season: SeasonDto = FALLBACK_SEASON
        private set

    val user: UserDto? get() = api.user

    /** Re-render hook, set once by the shell; fired whenever session state changes. */
    var onChange: () -> Unit = {}

    /** Restores a saved sign-in (if any) and fetches the current season, re-rendering as each lands. */
    fun boot(scope: CoroutineScope) {
        localStorage.getItem(TOKEN_KEY)?.let { saved ->
            scope.launch {
                // An invalid/expired token is dropped; a transient failure keeps it for the next load.
                runCatching { api.restoreSession(saved) }
                    .onSuccess { if (it == null) localStorage.removeItem(TOKEN_KEY) }
                if (user != null) onChange()
            }
        }
        scope.launch {
            runCatching { api.currentSeason() }.onSuccess { season = it; onChange() }
        }
    }

    /** Records a fresh sign-in (TbbApi already holds the token/user after login/register). */
    fun signedIn(auth: AuthResponse) {
        localStorage.setItem(TOKEN_KEY, auth.token)
        onChange()
    }

    fun signOut() {
        api.signOut()
        localStorage.removeItem(TOKEN_KEY)
        onChange()
    }

    /** Adopts an admin's just-saved season so every screen sees the new values immediately. */
    fun seasonSaved(updated: SeasonDto) {
        season = updated
        onChange()
    }

    /** Re-renders after a profile edit ([api] already holds the updated user). */
    fun profileSaved() {
        onChange()
    }
}
