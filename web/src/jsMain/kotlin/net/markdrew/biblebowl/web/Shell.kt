package net.markdrew.biblebowl.web

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.MainScope
import net.markdrew.biblebowl.api.Permission
import net.markdrew.biblebowl.api.hasEventWidePermission
import net.markdrew.biblebowl.web.screens.AccountScreen
import net.markdrew.biblebowl.web.screens.AdminRegistrationsScreen
import net.markdrew.biblebowl.web.screens.AdminSeasonScreen
import net.markdrew.biblebowl.web.screens.AdminUsersScreen
import net.markdrew.biblebowl.web.screens.AuthScreen
import net.markdrew.biblebowl.web.screens.ContributeScreen
import net.markdrew.biblebowl.web.screens.DownloadsScreen
import net.markdrew.biblebowl.web.screens.GradingScreen
import net.markdrew.biblebowl.web.screens.ModerateScreen
import net.markdrew.biblebowl.web.screens.HeadingsScreen
import net.markdrew.biblebowl.web.screens.IndexScreen
import net.markdrew.biblebowl.web.screens.MyScoresScreen
import net.markdrew.biblebowl.web.screens.QuestionsScreen
import net.markdrew.biblebowl.web.screens.QuizScreen
import net.markdrew.biblebowl.web.screens.RegisterScreen
import net.markdrew.biblebowl.web.screens.StandingsScreen
import net.markdrew.biblebowl.web.screens.StudyHubScreen
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList

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
        document.title = "${routeLabel(route)} | Texas Bible Bowl"
        app.clear()
        breadcrumbs(route)
        // content-body opts into the site's content styles (navy headings/tables, gold link hovers).
        val screen = app.child("div", "content-body")
        renderScreen(route, screen)
        window.scrollTo(0.0, 0.0)
    }

    /**
     * Site-style breadcrumb trail (Home › Study Hub › …) — the app shares the site's navbar,
     * so in-app wayfinding rides on breadcrumbs and the hub cards instead of a tab row.
     */
    private fun breadcrumbs(route: String) {
        if (route == Routes.STUDY) return // the hub is the app's landing page
        app.child("nav") {
            setAttribute("aria-label", "breadcrumb")
            child("ol", "breadcrumb small mb-3") {
                child("li", "breadcrumb-item") {
                    child("a", text = "Home") { setAttribute("href", "../") }
                }
                child("li", "breadcrumb-item") {
                    child("a", text = "Study Hub") { setAttribute("href", "#${Routes.STUDY}") }
                }
                val top = topDestinationOf(route)
                if (top != null && top != TopDestination.STUDY && top.route != route) {
                    child("li", "breadcrumb-item") {
                        child("a", text = top.label) { setAttribute("href", "#${top.route}") }
                    }
                }
                child("li", "breadcrumb-item active", routeLabel(route)) {
                    setAttribute("aria-current", "page")
                }
            }
        }
    }

    private fun updateNav(route: String) {
        // The merged navbar's app links (Study Resources dropdown items) carry data-route.
        document.querySelectorAll("[data-route]").asList().forEach { node ->
            val el = node as? HTMLElement ?: return@forEach
            val dest = el.getAttribute("data-route") ?: return@forEach
            el.classList.toggle("active", route == dest || route.startsWith("$dest/"))
        }
        // Collapse the mobile menu after navigating — a hash change doesn't reload the page,
        // so Bootstrap would otherwise leave it open over the new screen.
        document.getElementById("mainNav")?.classList?.remove("show")

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
            Routes.REGISTER -> feature(container, Session.registrationVisible) {
                signedIn(container) { RegisterScreen.render(container) }
            }
            // Sign-in only: the server scopes the response (owned entries + coached congregations).
            Routes.MY_SCORES -> feature(container, Session.gradingVisible) {
                signedIn(container) { MyScoresScreen.render(container) }
            }
            Routes.GRADING -> feature(container, Session.gradingVisible) {
                gatedEventWide(container, Permission.SCORE_ENTER) { GradingScreen.render(container) }
            }
            Routes.STANDINGS -> feature(container, Session.gradingVisible) {
                gatedEventWide(container, Permission.SCORE_VIEW_ALL) { StandingsScreen.render(container) }
            }
            Routes.QUESTIONS_NEW -> gated(container, Permission.QUESTION_SUBMIT) {
                ContributeScreen.render(container)
            }
            Routes.QUESTIONS_MODERATE -> gated(container, Permission.QUESTION_MODERATE) {
                ModerateScreen.render(container)
            }
            Routes.ADMIN_SEASON -> gated(container, Permission.SEASON_MANAGE) {
                AdminSeasonScreen.render(container)
            }
            Routes.ADMIN_REGISTRATIONS -> feature(container, Session.registrationVisible) {
                gatedEventWide(container, Permission.REGISTRATION_MANAGE) {
                    AdminRegistrationsScreen.render(container)
                }
            }
            Routes.ADMIN_USERS -> gated(container, Permission.USER_MANAGE) {
                AdminUsersScreen.render(container)
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

    /**
     * Renders [render] only while [visible] — a season feature toggle (or the admin preview
     * bypass; see Session). A dark feature shows a launch notice instead, so deep links from the
     * Hugo site land somewhere sensible before the feature goes live.
     */
    private fun feature(container: HTMLElement, visible: Boolean, render: () -> Unit) {
        if (visible) {
            render()
        } else {
            container.child("h1", "page-title", "Not open yet")
            container.child(
                "p", "text-muted",
                "This part of the app hasn't opened for the season — check back soon.",
            )
        }
    }

    /**
     * Like [gated], but requires [permission] via a GLOBAL or EVENT-scoped grant. A coach's
     * congregation-scoped REGISTRATION_MANAGE (which IS in the permission union [gated] checks)
     * deliberately does not qualify — mirrors the server's requireEventWidePermission.
     */
    private fun gatedEventWide(container: HTMLElement, permission: Permission, render: () -> Unit) {
        val user = Session.user
        if (user != null && hasEventWidePermission(user.roles, permission)) render()
        else AuthScreen.render(container)
    }

}
