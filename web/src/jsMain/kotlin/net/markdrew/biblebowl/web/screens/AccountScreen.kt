package net.markdrew.biblebowl.web.screens

import kotlinx.coroutines.launch
import net.markdrew.biblebowl.api.ContactInfoDto
import net.markdrew.biblebowl.api.ContactPreference
import net.markdrew.biblebowl.api.PersonRelation
import net.markdrew.biblebowl.api.UpdateProfileRequest
import net.markdrew.biblebowl.api.UserDto
import net.markdrew.biblebowl.api.division
import net.markdrew.biblebowl.api.divisionForBirthdate
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
import org.w3c.dom.HTMLOptionElement
import org.w3c.dom.HTMLSelectElement

/**
 * Account screen (docs/gui-redesign.md §5H): editable profile (display name + the birthdate/adult
 * fields that drive division eligibility), claiming a roster entry by coach-shared code, roles
 * held, sign out. Role-gated destinations live in the navbar user menu (NavMenu), not here.
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
        if (Session.registrationVisible) claimCard(container)

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
            child("button", "btn btn-outline-primary", "Sign out") {
                setAttribute("type", "button")
                onClick {
                    Session.signOut()
                    Shell.navigate(Routes.STUDY) // → redirects to the site's study hub page
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

                // Contact info (adults): optional event-communication details for registrars.
                fun contactInput(parent: Element, label: String, initial: String, autocomplete: String, cls: String = "") =
                    (parent.child("div", cls.ifEmpty { "flex-grow-1" }) {
                        child("label", "form-label", label)
                    }.let { wrap ->
                        wrap.child("input", "form-control") as HTMLInputElement
                    }).apply {
                        value = initial
                        setAttribute("autocomplete", autocomplete)
                    }
                val storedContact = user.contact ?: ContactInfoDto()
                lateinit var address: HTMLInputElement
                lateinit var city: HTMLInputElement
                lateinit var state: HTMLInputElement
                lateinit var zip: HTMLInputElement
                lateinit var phone: HTMLInputElement
                lateinit var preference: HTMLSelectElement
                val contactRows = form.child("div", "mb-3") {
                    child("h6", "mt-2", "Contact info")
                    child("p", "form-text mt-0",
                        "Optional — how event organizers can reach you (only registrars see it).")
                    address = contactInput(this, "Street address", storedContact.address, "street-address", "mb-2")
                    child("div", "d-flex flex-wrap gap-2 mb-2") {
                        city = contactInput(this, "City", storedContact.city, "address-level2")
                        state = contactInput(this, "State", storedContact.state, "address-level1", "w-25")
                        zip = contactInput(this, "Zip", storedContact.zip, "postal-code", "w-25")
                    }
                    child("div", "d-flex flex-wrap gap-2") {
                        phone = contactInput(this, "Phone", storedContact.phone, "tel")
                        child("div", "w-50") {
                            child("label", "form-label", "Preferred contact method")
                            preference = child("select", "form-select") as HTMLSelectElement
                            (preference.child("option", text = "No preference") as HTMLOptionElement).value = ""
                            ContactPreference.entries.forEach { pref ->
                                val option = preference.child("option", text = pref.displayName) as HTMLOptionElement
                                option.value = pref.name
                                if (pref == storedContact.preference) option.selected = true
                            }
                        }
                    }
                }

                val save = form.child("button", "btn btn-primary", "Save profile") {
                    setAttribute("type", "submit")
                } as HTMLButtonElement
                val messageSlot = form.child("div")

                fun refresh() {
                    val isAdult = adult.checked
                    birthdateRow.classList.toggle("d-none", isAdult)
                    contactRows.classList.toggle("d-none", !isAdult)
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
                                    // Always sent (empty clears); the server keeps stored contact
                                    // only when the field is omitted entirely (older clients).
                                    contact = ContactInfoDto(
                                        address = address.value.trim(),
                                        city = city.value.trim(),
                                        state = state.value.trim(),
                                        zip = zip.value.trim(),
                                        phone = phone.value.trim(),
                                        preference = preference.value.takeIf { it.isNotEmpty() }
                                            ?.let { ContactPreference.valueOf(it) },
                                    ),
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
     * Claim a roster entry by the coach-shared code, linking it to this account — that link is
     * what My Scores' owner scoping keys off (dashes/case in the code are tolerated server-side).
     */
    private fun claimCard(container: Element) {
        container.child("div", "card section-card mb-3") {
            child("div", "card-body") {
                child("h5", "card-title", "Claim a contestant")
                child(
                    "p", "text-muted",
                    "Enter the claim code your coach shared (like ABCD-2345) to link a contestant to this " +
                        "account and see their scores once they're released. If the code is your own and " +
                        "your email matches, it becomes you; otherwise you'll manage them (e.g. a parent " +
                        "claiming a child).",
                )
                val form = child("form", "d-flex flex-wrap gap-2 align-items-start")
                val code = (form.child("input", "form-control") as HTMLInputElement).apply {
                    setAttribute("placeholder", "ABCD-2345")
                    setAttribute("autocomplete", "off")
                    style.maxWidth = "12rem"
                }
                val claim = form.child("button", "btn btn-primary", "Claim") {
                    setAttribute("type", "submit")
                } as HTMLButtonElement
                val messageSlot = child("div")
                form.addEventListener("submit", { event ->
                    event.preventDefault()
                    if (code.value.isBlank()) return@addEventListener
                    claim.disabled = true
                    messageSlot.clear()
                    Shell.scope.launch {
                        try {
                            val result = Session.api.claimPerson(code.value)
                            code.value = ""
                            val who = if (result.relation == PersonRelation.SELF) "you" else result.person.name
                            messageSlot.child("p", "tbb-gold fw-semibold mt-2 mb-0") {
                                append("Claimed ${result.person.name} (as $who) — see ")
                                child("a", text = "My scores") { setAttribute("href", "#${Routes.MY_SCORES}") }
                                append(" once scores are released.")
                            }
                        } catch (e: Throwable) {
                            messageSlot.child("p", "text-danger mt-2 mb-0", "Claim failed: ${e.message}")
                        } finally {
                            claim.disabled = false
                        }
                    }
                })
            }
        }
    }

}
