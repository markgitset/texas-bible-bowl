package net.markdrew.biblebowl.web

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.MainScope
import net.markdrew.biblebowl.api.Permission
import net.markdrew.biblebowl.api.hasEventWidePermission
import net.markdrew.biblebowl.web.screens.AccountScreen
import net.markdrew.biblebowl.web.screens.AdminCountsScreen
import net.markdrew.biblebowl.web.screens.AdminHousingScreen
import net.markdrew.biblebowl.web.screens.AdminTribesScreen
import net.markdrew.biblebowl.web.screens.AdminRegistrationsScreen
import net.markdrew.biblebowl.web.screens.AdminSeasonScreen
import net.markdrew.biblebowl.web.screens.AdminTestersScreen
import net.markdrew.biblebowl.web.screens.AdminMergePeopleScreen
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
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList

/**
 * Owns the render loop: listens for hash changes and session changes, keeps the static navbar's
 * active state in sync, and renders the current route's screen into `#app`.
 */
object Shell {
    val scope = MainScope()
    private lateinit var app: HTMLElement

    /**
     * Screen-registered dirty check (e.g. the season editor's unsaved draft). While it reports
     * true, leaving the route — by hash navigation or by unloading the page for a static-site
     * link — first asks the user. Cleared on every route change, so a screen must re-register
     * it each render.
     */
    var unsavedChanges: (() -> Boolean)? = null
    private var routeShown: String? = null
    private var restoringHash = false

    /** The Hugo study hub page, sibling of /app/ — resolves correctly under the GH Pages subpath. */
    private const val STUDY_OVERVIEW_HREF = "../study-resources/"

    /** True only under the dev-only web/index.html shell, where no Hugo site surrounds the app. */
    private val standalone: Boolean get() = window.asDynamic().TBB_STANDALONE == true

    fun start() {
        app = document.getElementById("app") as HTMLElement
        Session.onChange = { render() }
        Session.boot(scope)
        window.addEventListener("hashchange", { render() })
        // Static-site links leave by unloading the page, not by hashchange — same guard applies
        // (the browser shows its own generic leave prompt).
        window.addEventListener("beforeunload", { event ->
            if (unsavedChanges?.invoke() == true) {
                event.preventDefault()
                event.asDynamic().returnValue = ""
            }
        })
        render()
    }

    /**
     * The route encoded in the URL hash; a blank hash falls back to [Routes.STUDY], which
     * (like any unknown route) redirects to the site's study hub page.
     */
    private fun currentRoute(): String =
        window.location.hash.substringAfter('#', "").ifBlank { Routes.STUDY }

    fun navigate(route: String) {
        window.location.hash = route // triggers hashchange → render
    }

    private fun render() {
        val route = currentRoute()
        if (restoringHash) {
            // The hashchange we triggered ourselves while vetoing a navigation — swallow it.
            restoringHash = false
            return
        }
        val previous = routeShown
        if (previous != null && previous != route && unsavedChanges?.invoke() == true &&
            !window.confirm("You have unsaved changes on this page. Leave without saving?")
        ) {
            restoringHash = true
            window.location.hash = previous
            return
        }
        // Cleared on every render (not just route changes): whatever screen renders below
        // re-registers its own check, so a replaced screen can't leave a stale one behind.
        unsavedChanges = null
        routeShown = route
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
     * Site-style breadcrumb trail — the app shares the site's navbar, so in-app wayfinding
     * rides on breadcrumbs instead of a tab row. Study-family routes trace back through the
     * site's hub page (Home › Study Resources › …); account/event/admin routes are entered
     * from the user menu, so they get just Home › {label}.
     */
    private fun breadcrumbs(route: String) {
        if (route == Routes.STUDY) return // redirecting to the site's hub page
        app.child("nav") {
            setAttribute("aria-label", "breadcrumb")
            child("ol", "breadcrumb small mb-3") {
                child("li", "breadcrumb-item") {
                    child("a", text = "Home") { setAttribute("href", "../") }
                }
                val top = topDestinationOf(route)
                if (top != null || route.startsWith("study/")) {
                    child("li", "breadcrumb-item") {
                        child("a", text = "Study Resources") { setAttribute("href", STUDY_OVERVIEW_HREF) }
                    }
                }
                if (top != null && top.route != route) {
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
        slot.classList.toggle("dropdown", user != null)
        if (user == null) {
            slot.child("a", "btn btn-warning btn-sm fw-bold px-3", "Sign in") {
                setAttribute("href", "#${Routes.SIGN_IN}")
            }
        } else {
            // Grouped user menu — params.js renders the same markup on static pages from the
            // tbb.nav cache (Session), so keep the two renderers' DOM identical.
            val menu = buildNavMenu(user, Session.season)
            slot.child("a", "btn btn-outline-light btn-sm px-3 dropdown-toggle") {
                setAttribute("href", "#${Routes.ACCOUNT}")
                setAttribute("role", "button")
                setAttribute("data-bs-toggle", "dropdown")
                setAttribute("aria-expanded", "false")
                child("i", "bi bi-person-circle me-1")
                append(menu.name)
            }
            slot.child("ul", "dropdown-menu dropdown-menu-end") {
                menu.sections.forEachIndexed { i, section ->
                    if (i > 0) child("li") { child("hr", "dropdown-divider") }
                    child("li") { child("h6", "dropdown-header", section.label) }
                    section.items.forEach { item ->
                        child("li") {
                            child("a", "dropdown-item") {
                                setAttribute("href", "#${item.route}")
                                append(item.label)
                                if (item.badge) child("span", "badge text-bg-warning ms-2", "hidden until launch")
                            }
                        }
                    }
                }
                child("li") { child("hr", "dropdown-divider") }
                child("li") {
                    child("button", "dropdown-item", "Sign out") {
                        setAttribute("type", "button")
                        onClick { Session.signOut() }
                    }
                }
            }
        }
    }

    private fun renderScreen(route: String, container: HTMLElement) {
        when (route) {
            Routes.STUDY -> studyOverview(container)
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
            Routes.ADMIN_COUNTS -> feature(container, Session.registrationVisible) {
                gatedEventWide(container, Permission.REGISTRATION_MANAGE) {
                    AdminCountsScreen.render(container)
                }
            }
            Routes.ADMIN_HOUSING -> feature(container, Session.registrationVisible) {
                gatedEventWide(container, Permission.REGISTRATION_MANAGE) {
                    AdminHousingScreen.render(container)
                }
            }
            Routes.ADMIN_TRIBES -> feature(container, Session.registrationVisible) {
                gatedEventWide(container, Permission.REGISTRATION_MANAGE) {
                    AdminTribesScreen.render(container)
                }
            }
            // Registrars prep tester IDs/nametags; graders need the ZipGrade export — either works.
            Routes.ADMIN_TESTERS -> feature(container, Session.registrationVisible) {
                gatedEventWideAny(
                    container, Permission.REGISTRATION_MANAGE, Permission.SCORE_ENTER,
                ) { AdminTestersScreen.render(container) }
            }
            Routes.ADMIN_USERS -> gated(container, Permission.USER_MANAGE) {
                AdminUsersScreen.render(container)
            }
            Routes.ADMIN_MERGE_PEOPLE -> feature(container, Session.registrationVisible) {
                gatedEventWide(container, Permission.REGISTRATION_MANAGE) {
                    AdminMergePeopleScreen.render(container)
                }
            }
            else -> studyOverview(container) // unknown deep link → the site's hub page
        }
    }

    /**
     * The study hub is the site's /study-resources/ page since the nav redesign — legacy
     * `#study` links, a blank hash, and unknown routes all land there. The dev shell has no
     * site around it, so it renders a plain tool list instead of redirecting into a 404.
     */
    private fun studyOverview(container: HTMLElement) {
        if (!standalone) {
            window.location.replace(STUDY_OVERVIEW_HREF) // replace: Back skips the bounce
            return
        }
        container.child("h1", "page-title", "Study tools")
        container.child(
            "p", "text-muted",
            "Dev shell only — in production this route redirects to the site's Study Resources page.",
        )
        container.child("div", "list-group") {
            listOf(
                Routes.DOWNLOADS to "Downloads",
                Routes.QUIZ to "Quiz Me",
                Routes.STUDY_INDICES to "Names & Numbers Indices",
                Routes.STUDY_HEADINGS to "Chapter Headings",
                Routes.QUESTIONS to "Community Questions",
            ).forEach { (route, label) ->
                child("a", "list-group-item list-group-item-action", label) {
                    setAttribute("href", "#$route")
                }
            }
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

    /** Like [gatedEventWide], but any one of [permissions] suffices. */
    private fun gatedEventWideAny(container: HTMLElement, vararg permissions: Permission, render: () -> Unit) {
        val user = Session.user
        if (user != null && permissions.any { hasEventWidePermission(user.roles, it) }) render()
        else AuthScreen.render(container)
    }

}
