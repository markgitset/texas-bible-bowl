package net.markdrew.biblebowl.web.screens

import kotlinx.coroutines.launch
import net.markdrew.biblebowl.api.Permission
import net.markdrew.biblebowl.api.UpdateProfileRequest
import net.markdrew.biblebowl.api.UserDto
import net.markdrew.biblebowl.api.division
import net.markdrew.biblebowl.api.divisionForBirthdate
import net.markdrew.biblebowl.api.hasEventWidePermission
import net.markdrew.biblebowl.api.isValidBirthdate
import net.markdrew.biblebowl.web.Routes
import net.markdrew.biblebowl.web.Session
import net.markdrew.biblebowl.web.Shell
import net.markdrew.biblebowl.web.child
import net.markdrew.biblebowl.web.clear
import net.markdrew.biblebowl.web.onClick
import org.w3c.dom.Element
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement

/**
 * Account screen (docs/gui-redesign.md §5H): editable profile (display name + the birthdate/adult
 * fields that drive division eligibility), roles held, sign out. Claiming a roster entry by
 * coach-shared code arrives with registration (Phase 4).
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
        val division = user.division(Session.season)
        when {
            division != null -> container.child("p", "text-muted", "Division: ${division.displayName}")
            else -> container.child(
                "p", "text-muted",
                "No division yet — add your birthdate below (or mark yourself an adult).",
            )
        }

        profileCard(container, user)

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

    /** Display name plus the adult/birthdate eligibility fields, saved via `PUT /auth/me`. */
    private fun profileCard(container: Element, user: UserDto) {
        container.child("div", "card section-card mb-3") {
            child("div", "card-body") {
                child("h5", "card-title", "Profile")
                val form = child("form")

                lateinit var name: HTMLInputElement
                form.child("div", "mb-3") {
                    child("label", "form-label", "Display name")
                    name = (child("input", "form-control") as HTMLInputElement).apply {
                        value = user.displayName
                        setAttribute("autocomplete", "name")
                    }
                }

                lateinit var adult: HTMLInputElement
                form.child("div", "form-check mb-3") {
                    adult = (child("input", "form-check-input") as HTMLInputElement).apply {
                        type = "checkbox"
                        id = "account-adult"
                        checked = user.adult
                    }
                    child("label", "form-check-label", "I'm an adult (18+ or finished high school)") {
                        setAttribute("for", "account-adult")
                    }
                }

                lateinit var birthdate: HTMLInputElement
                val birthdateRow = form.child("div", "mb-3") {
                    child("label", "form-label", "Birthdate")
                    birthdate = (child("input", "form-control") as HTMLInputElement).apply {
                        type = "date"
                        value = user.birthdate ?: ""
                        setAttribute("autocomplete", "bday")
                    }
                    child("div", "form-text", "Used to place contestants in the right division each season.")
                }
                val divisionHint = form.child("p", "form-text mt-n2 mb-3", "")

                val save = form.child("button", "btn btn-primary", "Save profile") {
                    setAttribute("type", "submit")
                } as HTMLButtonElement
                val messageSlot = form.child("div")

                fun refresh() {
                    val isAdult = adult.checked
                    birthdateRow.classList.toggle("d-none", isAdult)
                    divisionHint.textContent = when {
                        isAdult -> "Division: Adult"
                        else -> birthdate.value.takeIf { it.isNotBlank() }
                            ?.let { Session.season.divisionForBirthdate(it) }
                            ?.let { "Division: ${it.displayName}" }
                            ?: ""
                    }
                    save.disabled = name.value.isBlank() ||
                        !(isAdult || birthdate.value.let { it.isNotBlank() && isValidBirthdate(it) })
                }
                refresh()
                listOf(name, adult, birthdate).forEach { it.addEventListener("input", { refresh() }) }

                form.addEventListener("submit", { event ->
                    event.preventDefault()
                    save.disabled = true
                    messageSlot.clear()
                    Shell.scope.launch {
                        try {
                            Session.api.updateProfile(
                                UpdateProfileRequest(
                                    displayName = name.value.trim(),
                                    birthdate = birthdate.value.takeIf { it.isNotBlank() }
                                        ?.takeUnless { adult.checked },
                                    adult = adult.checked,
                                )
                            )
                            Session.profileSaved() // re-renders the screen with the fresh user
                        } catch (e: Throwable) {
                            messageSlot.child("p", "text-danger mt-3 mb-0", "Save failed: ${e.message}")
                            refresh()
                        }
                    }
                })
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
