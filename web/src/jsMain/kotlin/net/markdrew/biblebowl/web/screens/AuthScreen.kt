package net.markdrew.biblebowl.web.screens

import kotlinx.browser.window
import kotlinx.coroutines.launch
import net.markdrew.biblebowl.api.Division
import net.markdrew.biblebowl.api.LoginRequest
import net.markdrew.biblebowl.api.RegisterRequest
import net.markdrew.biblebowl.api.schoolYear
import net.markdrew.biblebowl.model.Round
import net.markdrew.biblebowl.web.Routes
import net.markdrew.biblebowl.web.Session
import net.markdrew.biblebowl.web.Shell
import net.markdrew.biblebowl.web.child
import net.markdrew.biblebowl.web.clear
import net.markdrew.biblebowl.web.onClick
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement

/**
 * Sign up / sign in. Renders in two situations: at #signin (e.g. an anonymous vote redirect) and
 * inline as the gate for permission-guarded routes. On success the session updates; if we're at
 * #signin we go back to where the user came from, while a gated route simply re-renders into the
 * destination (the hash never changed).
 */
object AuthScreen {

    private var registering = true

    fun render(container: HTMLElement) {
        val season = Session.season
        val box = container.child("div", "mx-auto", ) { setAttribute("style", "max-width: 460px;") }

        box.child("div", "text-center mb-3") {
            child("h1", "fw-bold", "Texas Bible Bowl") { setAttribute("style", "color:var(--tbb-navy);") }
            child("p", "tbb-gold fw-semibold", "Study ${season.eventScripture} · ${season.schoolYear} Season")
            child("div", "d-flex flex-wrap justify-content-center gap-1") {
                Round.entries.forEach { round ->
                    child(
                        "span",
                        "badge rounded-pill text-bg-light border " + if (round.openBible) "" else "tbb-gold",
                        round.displayName,
                    )
                }
            }
        }

        val card = box.child("div", "card section-card")
        renderCard(card as HTMLElement)
    }

    private fun renderCard(card: HTMLElement) {
        card.clear()
        val body = card.child("div", "card-body p-4")
        body.child("h5", "card-title mb-3", if (registering) "Create your account" else "Welcome back")

        val form = body.child("form")
        fun field(label: String, type: String, autocomplete: String? = null): HTMLInputElement {
            lateinit var input: HTMLInputElement
            form.child("div", "mb-3") {
                child("label", "form-label", label)
                input = child("input", "form-control") as HTMLInputElement
                input.type = type
                autocomplete?.let { input.setAttribute("autocomplete", it) }
            }
            return input
        }

        val email = field("Email", "email", "email")
        val password = field("Password", "password", if (registering) "new-password" else "current-password")
        var name: HTMLInputElement? = null
        var grade: HTMLInputElement? = null
        var divisionHint: HTMLElement? = null
        if (registering) {
            name = field("Display name", "text", "name")
            grade = field("Grade (3–12, optional)", "text").apply { setAttribute("inputmode", "numeric") }
            divisionHint = form.child("p", "form-text mt-n2 mb-3", "")
        }

        val submit = form.child("button", "btn btn-primary w-100", if (registering) "Sign up" else "Sign in") {
            setAttribute("type", "submit")
        } as HTMLButtonElement
        val errorSlot = form.child("div")

        fun refresh() {
            grade?.let { g ->
                g.value = g.value.filter(Char::isDigit)
                val division = g.value.toIntOrNull()?.let { Division.forGrade(it) }
                divisionHint?.textContent = division?.let { "Division: ${it.displayName}" } ?: ""
            }
            submit.disabled = email.value.isBlank() || password.value.length < 8 ||
                (registering && name?.value.isNullOrBlank())
        }
        refresh()
        listOfNotNull(email, password, name, grade).forEach { it.addEventListener("input", { refresh() }) }

        form.addEventListener("submit", { event ->
            event.preventDefault()
            submit.disabled = true
            errorSlot.clear()
            Shell.scope.launch {
                try {
                    val resp = if (registering) {
                        Session.api.register(
                            RegisterRequest(
                                email.value.trim(), password.value, name?.value?.trim().orEmpty(),
                                grade?.value?.toIntOrNull(),
                            )
                        )
                    } else {
                        Session.api.login(LoginRequest(email.value.trim(), password.value))
                    }
                    // At #signin, return to where the user came from; a gated route re-renders in place.
                    if (window.location.hash.substringAfter('#') == Routes.SIGN_IN) {
                        if (window.history.length > 1) window.history.back() else Shell.navigate(Routes.STUDY)
                    }
                    Session.signedIn(resp)
                } catch (e: Throwable) {
                    errorSlot.child("p", "text-danger mt-3 mb-0", "Error: ${e.message}")
                    refresh()
                }
            }
        })

        body.child("button", "btn btn-link w-100 mt-2", if (registering) "Have an account? Sign in" else "New here? Create an account") {
            setAttribute("type", "button")
            onClick { registering = !registering; renderCard(card) }
        }
    }
}
