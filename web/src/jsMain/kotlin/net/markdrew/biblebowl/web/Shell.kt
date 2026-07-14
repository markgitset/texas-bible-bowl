package net.markdrew.biblebowl.web

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.MainScope
import net.markdrew.biblebowl.api.Permission
import net.markdrew.biblebowl.web.screens.DownloadsScreen
import net.markdrew.biblebowl.web.screens.EventScreen
import net.markdrew.biblebowl.web.screens.HeadingsScreen
import net.markdrew.biblebowl.web.screens.IndexScreen
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
            Routes.QUIZ -> placeholder(container, "Quiz")
            Routes.QUESTIONS -> placeholder(container, "Questions")
            Routes.DOWNLOADS -> DownloadsScreen.render(container)
            Routes.EVENT -> EventScreen.render(container)
            Routes.SIGN_IN -> placeholder(container, "Sign in")
            Routes.ACCOUNT -> placeholder(container, "Account")
            Routes.QUESTIONS_NEW -> gated(container, Permission.QUESTION_SUBMIT) {
                placeholder(container, "Contribute a question")
            }
            Routes.QUESTIONS_MODERATE -> gated(container, Permission.QUESTION_MODERATE) {
                placeholder(container, "Moderate questions")
            }
            Routes.ADMIN_SEASON -> gated(container, Permission.SEASON_MANAGE) {
                placeholder(container, "Season settings")
            }
            else -> StudyHubScreen.render(container) // unknown deep link → hub, same as the wasm app
        }
    }

    /**
     * Renders [render] only when the signed-in user holds [permission]; otherwise the sign-in
     * screen takes the whole route (no disabled-but-visible affordances, per the redesign).
     */
    private fun gated(container: HTMLElement, permission: Permission, render: () -> Unit) {
        val user = Session.user
        if (user != null && permission in user.permissions) render()
        else placeholder(container, "Sign in")
    }

    // Phase-2 stand-in until the real screens land.
    private fun placeholder(container: HTMLElement, title: String) {
        container.child("h1", "page-title", title)
        container.child("p", "text-muted", "Coming right up — this screen is being rebuilt in plain HTML.")
        container.child("p", "", "Season: ${Session.season.eventScripture} (${Session.season.chapterCount} chapters)")
    }
}
