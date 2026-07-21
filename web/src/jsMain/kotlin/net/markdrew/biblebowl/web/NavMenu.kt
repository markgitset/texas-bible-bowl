package net.markdrew.biblebowl.web

import kotlinx.serialization.Serializable
import net.markdrew.biblebowl.api.Permission
import net.markdrew.biblebowl.api.SeasonDto
import net.markdrew.biblebowl.api.UserDto
import net.markdrew.biblebowl.api.hasEventWidePermission
import net.markdrew.biblebowl.api.isGlobalAdmin

/**
 * The signed-in user menu: the grouped dropdown behind the navbar account button. Built here
 * (once) from live session state, rendered twice — by [Shell.updateNav] in the app, and by
 * the site's params.js on static pages from the JSON that [Session] caches under `tbb.nav`.
 * Keep this the single source of truth for which destinations a user sees; the Shell's route
 * gates and the server still enforce access, so a stale cached menu is only a cosmetic issue.
 */
@Serializable
data class NavMenu(val name: String, val sections: List<NavSection>)

@Serializable
data class NavSection(val label: String, val items: List<NavItem>)

/** [route] is a hash route without the `#`; [badge] marks a dark feature an admin is previewing. */
@Serializable
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
        }
    }
    val admin = buildList {
        if (Permission.SEASON_MANAGE in user.permissions) add(NavItem("Season Settings", Routes.ADMIN_SEASON))
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
