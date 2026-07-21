package net.markdrew.biblebowl.web.screens

import kotlinx.coroutines.launch
import net.markdrew.biblebowl.api.CongregationDto
import net.markdrew.biblebowl.api.Role
import net.markdrew.biblebowl.api.RoleGrant
import net.markdrew.biblebowl.api.ScopeType
import net.markdrew.biblebowl.api.UserDto
import net.markdrew.biblebowl.api.division
import net.markdrew.biblebowl.web.Session
import net.markdrew.biblebowl.web.Shell
import net.markdrew.biblebowl.web.child
import net.markdrew.biblebowl.web.clear
import net.markdrew.biblebowl.web.errorLine
import net.markdrew.biblebowl.web.onClick
import org.w3c.dom.Element
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLOptionElement
import org.w3c.dom.HTMLSelectElement

/**
 * User management (docs/gui-redesign.md §5G): search users, view their role grants, grant/revoke
 * with a congregation picker for COACH. This closes the coach flow's "contact us" loop — an admin
 * finds the user and grants COACH scoped to the existing congregation. Route-gated on USER_MANAGE;
 * every mutation is re-checked server-side (ROLE_GRANT), including the you-can't-revoke-your-own-
 * admin guard, which the UI mirrors by hiding that Revoke button.
 */
object AdminUsersScreen {

    private var results: List<UserDto> = emptyList()
    private var searched = false
    private var searchSeq = 0 // typeahead guard: responses landing out of order are dropped
    private lateinit var resultsSlot: HTMLElement

    fun render(container: HTMLElement) {
        container.child("h1", "page-title", "Manage users")
        container.child("p", "text-muted", "Search by name or email, then grant or revoke roles.")
        results = emptyList()
        searched = false
        searchSeq++

        val search = container.child("input", "form-control mb-3") as HTMLInputElement
        search.setAttribute("placeholder", "Search users by name or email…")
        resultsSlot = container.child("div")
        search.addEventListener("input", {
            val query = search.value.trim()
            val seq = ++searchSeq
            if (query.length < 2) {
                results = emptyList()
                searched = false
                renderResults()
                return@addEventListener
            }
            Shell.scope.launch {
                runCatching { Session.api.searchUsers(query) }.onSuccess { found ->
                    if (seq != searchSeq) return@onSuccess // a newer search superseded this one
                    results = found
                    searched = true
                    renderResults()
                }
            }
        })
    }

    private fun renderResults() {
        resultsSlot.clear()
        if (results.isEmpty()) {
            if (searched) resultsSlot.child("p", "text-muted", "No matching users.")
            return
        }
        results.forEach { user -> renderUserCard(user) }
    }

    /** Replaces one user's card data after a grant/revoke and re-renders. */
    private fun updateUser(updated: UserDto) {
        results = results.map { if (it.id == updated.id) updated else it }
        renderResults()
        // Editing yourself (e.g. granting yourself REGISTRAR) changes what the shell should show.
        if (updated.id == Session.user?.id) {
            Shell.scope.launch {
                runCatching { Session.api.refreshUser() }
                Session.onChange()
            }
        }
    }

    private fun renderUserCard(user: UserDto) {
        resultsSlot.child("div", "card section-card mb-3") {
            child("div", "card-body") {
                child("h5", "card-title mb-0", user.displayName)
                child("p", "text-muted mb-2",
                    user.email + (user.division(Session.season)?.let { " · ${it.displayName}" } ?: ""))

                if (user.roles.isEmpty()) {
                    child("p", "text-muted", "Contestant (default) — no explicit grants")
                }
                user.roles.forEach { grant -> renderGrantLine(this, user, grant) }

                renderGrantForm(this, user)
            }
        }
    }

    private fun renderGrantLine(parent: Element, user: UserDto, grant: RoleGrant) {
        parent.child("div", "d-flex align-items-center gap-2 mb-1") {
            child("span", "badge text-bg-primary", grant.role.displayName)
            child("span", "text-muted small", grantScopeLabel(user, grant))
            // Mirror of the server's lockout guard: no revoking your own GLOBAL ADMIN.
            val isOwnAdmin = user.id == Session.user?.id &&
                grant.role == Role.ADMIN && grant.scopeType == ScopeType.GLOBAL
            if (!isOwnAdmin) {
                val revoke = child("button", "btn btn-outline-danger btn-sm", "Revoke") {
                    setAttribute("type", "button")
                } as HTMLButtonElement
                val errorSlot = child("span")
                revoke.onClick {
                    revoke.disabled = true
                    Shell.scope.launch {
                        try {
                            updateUser(Session.api.revokeRole(user.id, grant))
                        } catch (e: Throwable) {
                            revoke.disabled = false
                            errorSlot.clear()
                            errorSlot.child("span", "text-danger small", e.message ?: "Revoke failed")
                        }
                    }
                }
            }
        }
    }

    private fun grantScopeLabel(user: UserDto, grant: RoleGrant): String = when (grant.scopeType) {
        ScopeType.GLOBAL -> "everywhere"
        ScopeType.SELF -> "self"
        // The server resolves congregation names into UserDto.congregationNames; the short-id
        // fallback covers a grant whose congregation no longer exists.
        else -> grant.scopeType.name.lowercase() +
            (grant.scopeId?.let { " ${user.congregationNames[it] ?: it.take(8)}" } ?: "")
    }

    /** Role select + (for COACH) a congregation typeahead + Grant button. */
    private fun renderGrantForm(parent: Element, user: UserDto) {
        parent.child("div", "border-top pt-2 mt-2") {
            var selectedCongregation: CongregationDto? = null

            val row = child("div", "d-flex flex-wrap align-items-center gap-2")
            val roleSelect = row.child("select", "form-select form-select-sm w-auto") as HTMLSelectElement
            // CONTESTANT is everyone's default — granting it explicitly does nothing useful.
            listOf(Role.COACH, Role.REGISTRAR, Role.GRADER, Role.ADMIN).forEach { role ->
                val opt = roleSelect.child("option", text = role.displayName) as HTMLOptionElement
                opt.value = role.name
            }

            val congSlot = row.child("span")
            val grantButton = row.child("button", "btn btn-primary btn-sm", "Grant") {
                setAttribute("type", "button")
            } as HTMLButtonElement
            val messageSlot = child("div")

            fun selectedRole() = Role.valueOf(roleSelect.value)
            fun refreshEnabled() {
                grantButton.disabled = selectedRole() == Role.COACH && selectedCongregation == null
            }

            fun renderCongPicker() {
                congSlot.clear()
                selectedCongregation = null
                if (selectedRole() != Role.COACH) return
                val search = congSlot.child("input", "form-control form-control-sm d-inline-block w-auto")
                    as HTMLInputElement
                search.setAttribute("placeholder", "Congregation…")
                val suggestions = congSlot.child("span")
                search.addEventListener("input", {
                    val query = search.value.trim()
                    suggestions.clear()
                    selectedCongregation = null
                    refreshEnabled()
                    if (query.length < 2) return@addEventListener
                    Shell.scope.launch {
                        runCatching { Session.api.searchCongregations(query) }.onSuccess { found ->
                            suggestions.clear()
                            found.take(5).forEach { cong ->
                                suggestions.child(
                                    "button", "btn btn-outline-primary btn-sm ms-1",
                                    "${cong.name} — ${cong.city}",
                                ) {
                                    setAttribute("type", "button")
                                    onClick {
                                        selectedCongregation = cong
                                        search.value = "${cong.name} — ${cong.city}"
                                        suggestions.clear()
                                        refreshEnabled()
                                    }
                                }
                            }
                        }
                    }
                })
            }

            roleSelect.addEventListener("change", { renderCongPicker(); refreshEnabled() })
            renderCongPicker()
            refreshEnabled()

            grantButton.onClick {
                val role = selectedRole()
                val grant =
                    if (role == Role.COACH) RoleGrant(role, ScopeType.CONGREGATION, selectedCongregation?.id)
                    else RoleGrant(role)
                grantButton.disabled = true
                messageSlot.clear()
                Shell.scope.launch {
                    try {
                        updateUser(Session.api.grantRole(user.id, grant))
                    } catch (e: Throwable) {
                        grantButton.disabled = false
                        messageSlot.errorLine(e.message ?: "Grant failed")
                    }
                }
            }
        }
    }
}
