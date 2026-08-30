package net.markdrew.biblebowl.web

import net.markdrew.biblebowl.api.Permission
import net.markdrew.biblebowl.api.SeasonDto
import net.markdrew.biblebowl.api.UserDto
import net.markdrew.biblebowl.api.hasEventWidePermission
import net.markdrew.biblebowl.api.isGlobalAdmin
import org.w3c.dom.HTMLElement

/**
 * The signed-in user menu: the grouped dropdown behind the navbar account button. Built and
 * rendered here, once — [Shell.updateNav] calls [renderAccountMenu] for the live slot, and
 * [Session] caches the same rendered HTML under `tbb.navHtml` for the site's params.js to
 * inject into static pages' navbars (no second renderer). Keep this the single source of truth
 * for which destinations a user sees; the Shell's route gates and the server still enforce
 * access, so a stale cached menu is only a cosmetic issue.
 */
data class NavMenu(val name: String, val sections: List<NavSection>)

data class NavSection(val label: String, val items: List<NavItem>)

/** [route] is a hash route without the `#`; [badge] marks a dark feature an admin is previewing. */
data class NavItem(val label: String, val route: String, val badge: Boolean = false)

/** Mirrors the route gates in [Shell.renderScreen] — update both together. */
fun buildNavMenu(user: UserDto, season: SeasonDto): NavMenu {
    // Same visibility rules as Session.registrationVisible/gradingVisible: feature toggle on,
    // or a global admin previewing the dark-deployed feature (those items get the badge).
    val adminPreview = isGlobalAdmin(user.roles)
    val registrationVisible = season.registrationEnabled || adminPreview
    val gradingVisible = season.gradingEnabled || adminPreview

    val personal = buildList {
        add(NavItem("Account", Routes.ACCOUNT))
        if (gradingVisible) add(NavItem("My Scores", Routes.MY_SCORES, badge = !season.gradingEnabled))
    }
    // Sign-in is the only gate on registration: step 1 is where a user *becomes* a coach.
    val coach = buildList {
        if (registrationVisible) {
            add(NavItem("Register My Teams", Routes.REGISTER, badge = !season.registrationEnabled))
        }
    }
    val staff = buildList {
        if (gradingVisible && hasEventWidePermission(user.roles, Permission.SCORE_ENTER)) {
            add(NavItem("Grading Desk", Routes.GRADING, badge = !season.gradingEnabled))
        }
        if (gradingVisible && hasEventWidePermission(user.roles, Permission.SCORE_VIEW_ALL)) {
            add(NavItem("Standings", Routes.STANDINGS, badge = !season.gradingEnabled))
        }
        if (registrationVisible && hasEventWidePermission(user.roles, Permission.REGISTRATION_MANAGE)) {
            add(NavItem("Registration Desk", Routes.ADMIN_REGISTRATIONS, badge = !season.registrationEnabled))
            add(NavItem("Registration Counts", Routes.ADMIN_COUNTS, badge = !season.registrationEnabled))
            add(NavItem("Housing", Routes.ADMIN_HOUSING, badge = !season.registrationEnabled))
            add(NavItem("Tribes", Routes.ADMIN_TRIBES, badge = !season.registrationEnabled))
            add(NavItem("Merge People", Routes.ADMIN_MERGE_PEOPLE, badge = !season.registrationEnabled))
        }
        // Registrars prep IDs/nametags; graders run the ZipGrade export — both get the link.
        val testerAccess = hasEventWidePermission(user.roles, Permission.REGISTRATION_MANAGE) ||
            hasEventWidePermission(user.roles, Permission.SCORE_ENTER)
        if (registrationVisible && testerAccess) {
            add(NavItem("Tester IDs", Routes.ADMIN_TESTERS, badge = !season.registrationEnabled))
        }
    }
    val admin = buildList {
        if (Permission.SEASON_MANAGE in user.permissions) {
            add(NavItem("Season Settings", Routes.ADMIN_SEASON))
            add(NavItem("Study Materials", Routes.ADMIN_MATERIALS))
        }
        if (Permission.USER_MANAGE in user.permissions) add(NavItem("User Management", Routes.ADMIN_USERS))
    }
    val sections = listOf(
        NavSection("Personal", personal),
        NavSection("Coach", coach),
        NavSection("Event Staff", staff),
        NavSection("Admin", admin),
    ).filter { it.items.isNotEmpty() }
    return NavMenu(user.displayName.ifBlank { "Account" }, sections)
}

/**
 * Renders [menu] into [slot] (the navbar account `<li>`): the dropdown toggle plus the grouped
 * item list. Hrefs are app-relative (`#account`) — params.js prefixes them with the /app/ base
 * when it injects the cached copy into a static page, and re-wires the sign-out button there
 * (a listener doesn't survive innerHTML serialization), matching it as `button.dropdown-item`.
 */
fun renderAccountMenu(slot: HTMLElement, menu: NavMenu, onSignOut: () -> Unit) {
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
                onClick(onSignOut)
            }
        }
    }
}
