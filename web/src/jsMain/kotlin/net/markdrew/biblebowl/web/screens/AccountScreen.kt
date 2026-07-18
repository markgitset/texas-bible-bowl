package net.markdrew.biblebowl.web.screens

import kotlinx.coroutines.launch
import net.markdrew.biblebowl.api.Permission
import net.markdrew.biblebowl.api.hasEventWidePermission
import net.markdrew.biblebowl.web.Routes
import net.markdrew.biblebowl.web.Session
import net.markdrew.biblebowl.web.Shell
import net.markdrew.biblebowl.web.child
import net.markdrew.biblebowl.web.clear
import net.markdrew.biblebowl.web.onClick
import org.w3c.dom.Element
import org.w3c.dom.HTMLButtonElement
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
            child("a", "btn btn-outline-primary", "Register my teams") {
                setAttribute("href", "#${Routes.REGISTER}")
            }
            if (hasEventWidePermission(user.roles, Permission.REGISTRATION_MANAGE)) {
                child("a", "btn btn-outline-primary", "Registration desk") {
                    setAttribute("href", "#${Routes.ADMIN_REGISTRATIONS}")
                }
            }
            if (Permission.USER_MANAGE in user.permissions) {
                child("a", "btn btn-outline-primary", "Manage users") {
                    setAttribute("href", "#${Routes.ADMIN_USERS}")
                }
            }
            if (Permission.SEASON_MANAGE in user.permissions) {
                child("a", "btn btn-outline-primary", "Season settings") {
                    setAttribute("href", "#${Routes.ADMIN_SEASON}")
                }
                clearPdfCacheButton(this)
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

    /**
     * Admin: drops the server's compiled-PDF cache so every study document regenerates on its next
     * download — for after a generation-code change (season/word-list changes invalidate on their own).
     */
    private fun clearPdfCacheButton(container: Element) {
        val button = container.child("button", "btn btn-outline-primary", "Clear PDF cache") {
            setAttribute("type", "button")
        } as HTMLButtonElement
        val messageSlot = container.child("div")
        button.onClick {
            button.disabled = true
            messageSlot.clear()
            Shell.scope.launch {
                try {
                    val cleared = Session.api.clearPdfCache().cleared
                    messageSlot.child(
                        "p", "tbb-gold fw-semibold mb-0",
                        "Cleared $cleared cached PDF(s) — next downloads regenerate.",
                    )
                } catch (e: Throwable) {
                    messageSlot.child("p", "text-danger mb-0", "Clear failed: ${e.message}")
                } finally {
                    button.disabled = false
                }
            }
        }
    }
}
