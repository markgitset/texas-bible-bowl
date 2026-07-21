package net.markdrew.biblebowl.web

import kotlinx.browser.localStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import net.markdrew.biblebowl.api.AuthResponse
import net.markdrew.biblebowl.api.FALLBACK_SEASON
import net.markdrew.biblebowl.api.SeasonDto
import net.markdrew.biblebowl.api.UserDto
import net.markdrew.biblebowl.api.isGlobalAdmin
import net.markdrew.biblebowl.client.TbbApi

/** App-wide client state: the shared [TbbApi], the current season, and the signed-in user. */
object Session {
    private const val TOKEN_KEY = "tbb.token"

    /**
     * The signed-in user menu as JSON ([NavMenu]); the site's params.js renders it on static
     * pages' navbars. Refreshed on every auth/season change here, so it can lag a server-side
     * role/toggle change until the next app visit — cosmetic only, all access is enforced by
     * the Shell's route gates and the server.
     */
    private const val NAV_KEY = "tbb.nav"

    /** Pre-user-menu cache of the display name; purge so old params.js data can't linger. */
    private const val LEGACY_NAME_KEY = "tbb.user-name"

    val api = TbbApi()

    var season: SeasonDto = FALLBACK_SEASON
        private set

    val user: UserDto? get() = api.user

    /**
     * Whether registration features (coach registration, claim codes, registration desk) should
     * render for this user: the season's feature toggle is on, or the user is a global admin
     * previewing the dark-deployed feature. Mirrors the server's `feature_disabled` gate.
     */
    val registrationVisible: Boolean get() = season.registrationEnabled || isAdminPreview

    /** Like [registrationVisible] for scoring: grading desk, standings, My Scores. */
    val gradingVisible: Boolean get() = season.gradingEnabled || isAdminPreview

    private val isAdminPreview: Boolean get() = user?.let { isGlobalAdmin(it.roles) } == true

    /** Re-render hook, set once by the shell; fired whenever session state changes. */
    var onChange: () -> Unit = {}

    /** Restores a saved sign-in (if any) and fetches the current season, re-rendering as each lands. */
    fun boot(scope: CoroutineScope) {
        localStorage.getItem(TOKEN_KEY)?.let { saved ->
            scope.launch {
                // An invalid/expired token is dropped; a transient failure keeps it for the next load.
                runCatching { api.restoreSession(saved) }
                    .onSuccess { if (it == null) localStorage.removeItem(TOKEN_KEY) }
                cacheNavState()
                if (user != null) onChange()
            }
        }
        scope.launch {
            runCatching { api.currentSeason() }.onSuccess {
                season = it
                cacheNavState() // feature toggles change which menu items exist
                onChange()
            }
        }
    }

    /** Records a fresh sign-in (TbbApi already holds the token/user after login/register). */
    fun signedIn(auth: AuthResponse) {
        localStorage.setItem(TOKEN_KEY, auth.token)
        cacheNavState()
        onChange()
    }

    fun signOut() {
        api.signOut()
        localStorage.removeItem(TOKEN_KEY)
        cacheNavState()
        onChange()
    }

    /** Mirrors the signed-in user menu into localStorage for the static pages' navbar. */
    private fun cacheNavState() {
        val u = user
        if (u == null) localStorage.removeItem(NAV_KEY)
        else localStorage.setItem(NAV_KEY, Json.encodeToString(buildNavMenu(u, season)))
        localStorage.removeItem(LEGACY_NAME_KEY)
    }

    /** Adopts an admin's just-saved season so every screen sees the new values immediately. */
    fun seasonSaved(updated: SeasonDto) {
        season = updated
        cacheNavState()
        onChange()
    }

    /** Re-renders after a profile edit ([api] already holds the updated user). */
    fun profileSaved() {
        cacheNavState()
        onChange()
    }
}
