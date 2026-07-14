package net.markdrew.biblebowl.web.screens

import net.markdrew.biblebowl.api.Permission
import net.markdrew.biblebowl.web.Routes
import net.markdrew.biblebowl.web.Session
import net.markdrew.biblebowl.web.Shell
import net.markdrew.biblebowl.web.child
import net.markdrew.biblebowl.web.onClick
import org.w3c.dom.HTMLElement

/**
 * Account screen (docs/gui-redesign.md §5H): profile, roles held, sign out. Claiming a roster
 * entry by coach-shared code arrives with registration (Phase 4).
 */
object AccountScreen {

    fun render(container: HTMLElement) {
        val user = Session.user
        if (user == null) {
            // Not signed in: the account route is just the sign-in form.
            AuthScreen.render(container)
            return
        }

        container.child("h1", "page-title", user.displayName)
        container.child("p", "fs-5 mb-1", user.email)
        user.division?.let { container.child("p", "text-muted", "Division: ${it.displayName}") }

        container.child("div", "card section-card mb-3") {
            child("div", "card-body") {
                child("h5", "card-title", "Roles")
                if (user.roles.isEmpty()) {
                    child("p", "text-muted mb-0", "Contestant (default)")
                } else {
                    user.roles.forEach { grant ->
                        child("p", "mb-1", grant.role.name.lowercase().replaceFirstChar { it.uppercase() })
                    }
                }
            }
        }

        container.child("div", "d-grid gap-2") {
            if (Permission.SEASON_MANAGE in user.permissions) {
                child("a", "btn btn-outline-primary", "Season settings") {
                    setAttribute("href", "#${Routes.ADMIN_SEASON}")
                }
            }
            child("button", "btn btn-outline-primary", "Sign out") {
                setAttribute("type", "button")
                onClick {
                    Session.signOut()
                    Shell.navigate(Routes.STUDY)
                }
            }
        }
    }
}
