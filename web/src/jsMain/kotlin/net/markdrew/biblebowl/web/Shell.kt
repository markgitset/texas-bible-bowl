package net.markdrew.biblebowl.web

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.MainScope
import net.markdrew.biblebowl.api.Permission
import net.markdrew.biblebowl.web.screens.AccountScreen
import net.markdrew.biblebowl.web.screens.AdminSeasonScreen
import net.markdrew.biblebowl.web.screens.AuthScreen
import net.markdrew.biblebowl.web.screens.ContributeScreen
import net.markdrew.biblebowl.web.screens.DownloadsScreen
import net.markdrew.biblebowl.web.screens.ModerateScreen
import net.markdrew.biblebowl.web.screens.HeadingsScreen
import net.markdrew.biblebowl.web.screens.IndexScreen
import net.markdrew.biblebowl.web.screens.QuestionsScreen
import net.markdrew.biblebowl.web.screens.QuizScreen
import net.markdrew.biblebowl.web.screens.RegisterScreen
import net.markdrew.biblebowl.web.screens.StudyHubScreen
import org.w3c.dom.HTMLElement

/**
 * Owns the render loop: listens for hash changes and session changes, keeps the static navbar's
 * active state in sync, and renders the current route's screen into `#app`.
 */
object Shell {
    val scope = MainScope()
    private lateinit var app: HTMLElement

    fun start() {
        app = document.getElementById("app") as HTMLElement
        Session.onChange = { render() }
        Session.boot(scope)
        window.addEventListener("hashchange", { render() })
        render()
    }

    /** The route encoded in the URL hash; a blank/unknown hash falls back to the study hub. */
    private fun currentRoute(): String =
        window.location.hash.substringAfter('#', "").ifBlank { Routes.STUDY }

    fun navigate(route: String) {
        window.location.hash = route // triggers hashchange → render
    }

    private fun render() {
        val route = currentRoute()
        updateNav(route)
        app.clear()
        // content-body opts into the site's content styles (navy headings/tables, gold link hovers).
        val screen = app.child("div", "content-body")
        renderScreen(route, screen)
        window.scrollTo(0.0, 0.0)
    }

    private fun updateNav(route: String) {
        val active = topDestinationOf(route)
        TopDestination.entries.forEach { dest ->
            (document.querySelector("[data-route='${dest.route}']") as? HTMLElement)
                ?.classList?.toggle("active", dest == active)
        }
        // Collapse the mobile menu after navigating — a hash change doesn't reload the page,
        // so Bootstrap would otherwise leave it open over the new screen.
        document.getElementById("appNav")?.classList?.remove("show")

        val slot = document.getElementById("accountSlot") as HTMLElement
        slot.clear()
        val user = Session.user
        if (user == null) {
            slot.child("a", "btn btn-warning btn-sm fw-bold px-3", "Sign in") {
                setAttribute("href", "#${Routes.SIGN_IN}")
            }
        } else {
            slot.child("a", "btn btn-outline-light btn-sm px-3") {
                setAttribute("href", "#${Routes.ACCOUNT}")
                child("i", "bi bi-person-circle me-1")
                append(user.displayName.ifBlank { "Account" })
            }
        }
    }

    private fun renderScreen(route: String, container: HTMLElement) {
        when (route) {
            Routes.STUDY -> StudyHubScreen.render(container)
            Routes.STUDY_INDICES -> IndexScreen.render(container)
            Routes.STUDY_HEADINGS -> HeadingsScreen.render(container)
            Routes.QUIZ -> QuizScreen.render(container)
            Routes.QUESTIONS -> QuestionsScreen.render(container)
            Routes.DOWNLOADS -> DownloadsScreen.render(container)
            Routes.SIGN_IN -> AuthScreen.render(container)
            Routes.ACCOUNT -> AccountScreen.render(container)
            // Sign-in only, no permission: step 1 is where a signed-in user *becomes* a coach
            // (self-serve congregation creation); a TEAM_MANAGE gate would lock them out of it.
            // The server scope-checks every mutation regardless.
            Routes.REGISTER -> signedIn(container) { RegisterScreen.render(container) }
            Routes.QUESTIONS_NEW -> gated(container, Permission.QUESTION_SUBMIT) {
                ContributeScreen.render(container)
            }
            Routes.QUESTIONS_MODERATE -> gated(container, Permission.QUESTION_MODERATE) {
                ModerateScreen.render(container)
            }
            Routes.ADMIN_SEASON -> gated(container, Permission.SEASON_MANAGE) {
                AdminSeasonScreen.render(container)
            }
            else -> StudyHubScreen.render(container) // unknown deep link → hub, same as the wasm app
        }
    }

    /**
     * Renders [render] only when the signed-in user holds [permission]; otherwise the sign-in
     * screen takes the whole route (no disabled-but-visible affordances, per the redesign).
     * The hash stays on the gated route, so signing in re-renders straight into the destination.
     */
    private fun gated(container: HTMLElement, permission: Permission, render: () -> Unit) {
        val user = Session.user
        if (user != null && permission in user.permissions) render()
        else AuthScreen.render(container)
    }

    /** Like [gated] but requires only a signed-in user, any permissions. */
    private fun signedIn(container: HTMLElement, render: () -> Unit) {
        if (Session.user != null) render() else AuthScreen.render(container)
    }

}
