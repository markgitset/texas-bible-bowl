package net.markdrew.biblebowl.web.screens

import kotlinx.browser.window
import kotlinx.coroutines.launch
import net.markdrew.biblebowl.api.AuthResponse
import net.markdrew.biblebowl.api.ForgotPasswordRequest
import net.markdrew.biblebowl.api.LoginRequest
import net.markdrew.biblebowl.api.RegisterRequest
import net.markdrew.biblebowl.api.ResetPasswordRequest
import net.markdrew.biblebowl.api.divisionForBirthdate
import net.markdrew.biblebowl.api.isValidBirthdate
import net.markdrew.biblebowl.api.schoolYear
import net.markdrew.biblebowl.client.ApiException
import net.markdrew.biblebowl.web.Routes
import net.markdrew.biblebowl.web.Session
import net.markdrew.biblebowl.web.Shell
import net.markdrew.biblebowl.web.child
import net.markdrew.biblebowl.web.clear
import net.markdrew.biblebowl.web.onClick
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLFormElement
import org.w3c.dom.HTMLInputElement

/**
 * Sign up / sign in / password reset. Renders in two situations: at #signin (e.g. an anonymous vote
 * redirect) and inline as the gate for permission-guarded routes ([gate] = true). On success the
 * session updates; if we're at #signin we go back to where the user came from, while a gated route
 * simply re-renders into the destination (the hash never changed).
 */
object AuthScreen {

    private enum class Mode { SIGN_IN, REGISTER, FORGOT, RESET }

    private var mode = Mode.SIGN_IN
    private var resetEmail = "" // carries the address across FORGOT → RESET

    fun render(container: HTMLElement, gate: Boolean = false) {
        mode = Mode.SIGN_IN // each visit starts on sign-in; mode switches only last within the visit
        val season = Session.season
        val box = container.child("div", "mx-auto") { setAttribute("style", "max-width: 460px;") }

        box.child("div", "text-center mb-3") {
            child("h1", "fw-bold", "Texas Bible Bowl") { setAttribute("style", "color:var(--tbb-navy);") }
            child("p", "tbb-gold fw-semibold", "Study ${season.eventScripture} · ${season.schoolYear} Season")
        }
        if (gate) {
            val notice =
                if (Session.user != null) {
                    "Your account doesn't have access to this page — sign in with an account that does."
                } else "Sign in to continue."
            box.child("p", "text-center text-muted mb-3", notice)
        }

        val card = box.child("div", "card section-card")
        renderCard(card as HTMLElement)
    }

    private fun renderCard(card: HTMLElement) {
        card.clear()
        val body = card.child("div", "card-body p-4")
        when (mode) {
            Mode.SIGN_IN, Mode.REGISTER -> renderAuthForm(card, body)
            Mode.FORGOT -> renderForgotForm(card, body)
            Mode.RESET -> renderResetForm(card, body)
        }
    }

    /** Adds a labeled input (with a `for`/`id` pair, so labels focus their fields) to this form. */
    private fun HTMLElement.field(
        label: String,
        type: String,
        autocomplete: String? = null,
        help: String? = null,
    ): HTMLInputElement {
        lateinit var input: HTMLInputElement
        child("div", "mb-3") {
            val id = "auth-" + label.lowercase().replace(' ', '-')
            child("label", "form-label", label) { setAttribute("for", id) }
            input = child("input", "form-control") as HTMLInputElement
            input.type = type
            input.id = id
            autocomplete?.let { input.setAttribute("autocomplete", it) }
            help?.let { child("div", "form-text", it) }
        }
        return input
    }

    private fun switchLink(card: HTMLElement, body: HTMLElement, label: String, target: Mode) {
        body.child("button", "btn btn-link w-100 mt-2", label) {
            setAttribute("type", "button")
            onClick { mode = target; renderCard(card) }
        }
    }

    /**
     * ApiException carries the server's human-readable reason; anything else means the request
     * never got an answer (offline, cold start, DNS).
     */
    private fun errorMessage(e: Throwable): String =
        (e as? ApiException)?.message ?: "Couldn't reach the server — check your connection and try again."

    /** Post-auth: at #signin return to where the user came from; a gated route re-renders in place. */
    private fun finishSignIn(resp: AuthResponse) {
        if (window.location.hash.substringAfter('#') == Routes.SIGN_IN) {
            if (window.history.length > 1) window.history.back() else Shell.navigate(Routes.ACCOUNT)
        }
        Session.signedIn(resp)
    }

    /**
     * Wires the form's submit: disables the button behind [busyLabel], runs [action], and on
     * failure shows the error and restores the button.
     */
    private fun onSubmit(form: HTMLFormElement, submit: HTMLButtonElement, busyLabel: String, action: suspend () -> Unit) {
        val errorSlot = form.child("div")
        form.addEventListener("submit", { event ->
            event.preventDefault()
            submit.disabled = true
            val idleLabel = submit.textContent
            submit.textContent = busyLabel
            errorSlot.clear()
            Shell.scope.launch {
                try {
                    action()
                } catch (e: Throwable) {
                    errorSlot.child("p", "text-danger mt-3 mb-0", errorMessage(e))
                    submit.textContent = idleLabel
                    submit.disabled = false
                }
            }
        })
    }

    private fun renderAuthForm(card: HTMLElement, body: HTMLElement) {
        val registering = mode == Mode.REGISTER
        body.child("h5", "card-title mb-3", if (registering) "Create your account" else "Welcome back")

        val form = body.child("form") as HTMLFormElement
        val email = form.field("Email", "email", "email")
        val password =
            if (registering) form.field("Password", "password", "new-password", help = "At least 8 characters.")
            else form.field("Password", "password", "current-password")
        var name: HTMLInputElement? = null
        var adult: HTMLInputElement? = null
        var birthdate: HTMLInputElement? = null
        var birthdateRow: HTMLElement? = null
        var divisionHint: HTMLElement? = null
        if (registering) {
            name = form.field("Display name", "text", "name")
            form.child("div", "form-check mb-3") {
                adult = (child("input", "form-check-input") as HTMLInputElement).apply {
                    type = "checkbox"
                    id = "auth-adult"
                }
                child("label", "form-check-label", "I'm an adult (18+ or finished high school)") {
                    setAttribute("for", "auth-adult")
                }
            }
            birthdateRow = form.child("div", "mb-3") {
                child("label", "form-label", "Birthdate") { setAttribute("for", "auth-birthdate") }
                birthdate = (child("input", "form-control") as HTMLInputElement).apply {
                    type = "date"
                    id = "auth-birthdate"
                    setAttribute("autocomplete", "bday")
                }
                child("div", "form-text", "Used to place contestants in the right division each season.")
            }
            divisionHint = form.child("p", "form-text mt-n2 mb-3", "")
        }

        val submit = form.child("button", "btn btn-primary w-100", if (registering) "Sign up" else "Sign in") {
            setAttribute("type", "submit")
        } as HTMLButtonElement

        fun refresh() {
            val isAdult = adult?.checked == true
            birthdateRow?.classList?.toggle("d-none", isAdult)
            divisionHint?.textContent = when {
                isAdult -> "Division: Adult"
                else -> birthdate?.value?.takeIf { it.isNotBlank() }
                    ?.let { Session.season.divisionForBirthdate(it) }
                    ?.let { "Division: ${it.displayName}" }
                    ?: ""
            }
            val birthdateOk = isAdult || birthdate?.value?.let { isValidBirthdate(it) } == true
            // The 8-char minimum is a registration rule; on sign-in the server is the judge.
            submit.disabled = email.value.isBlank() || password.value.isEmpty() ||
                (registering && (password.value.length < 8 || name?.value.isNullOrBlank() || !birthdateOk))
        }
        refresh()
        listOfNotNull(email, password, name, adult, birthdate).forEach { it.addEventListener("input", { refresh() }) }

        onSubmit(form, submit, busyLabel = if (registering) "Signing up…" else "Signing in…") {
            val resp = if (registering) {
                val isAdult = adult?.checked == true
                Session.api.register(
                    RegisterRequest(
                        email.value.trim(), password.value, name?.value?.trim().orEmpty(),
                        birthdate = birthdate?.value?.takeIf { it.isNotBlank() }?.takeUnless { isAdult },
                        adult = isAdult,
                    )
                )
            } else {
                Session.api.login(LoginRequest(email.value.trim(), password.value))
            }
            finishSignIn(resp)
        }

        if (!registering) {
            body.child("button", "btn btn-link w-100 mt-2", "Forgot your password?") {
                setAttribute("type", "button")
                onClick {
                    resetEmail = email.value.trim() // carry a typed address into the reset form
                    mode = Mode.FORGOT
                    renderCard(card)
                }
            }
        }
        switchLink(
            card, body,
            if (registering) "Have an account? Sign in" else "New here? Create an account",
            if (registering) Mode.SIGN_IN else Mode.REGISTER,
        )
    }

    private fun renderForgotForm(card: HTMLElement, body: HTMLElement) {
        body.child("h5", "card-title mb-3", "Reset your password")
        body.child("p", "text-muted", "Enter your account email and we'll send you a 6-digit reset code.")

        val form = body.child("form") as HTMLFormElement
        val email = form.field("Email", "email", "email").apply { value = resetEmail }
        val submit = form.child("button", "btn btn-primary w-100", "Email me a code") {
            setAttribute("type", "submit")
        } as HTMLButtonElement

        fun refresh() { submit.disabled = email.value.isBlank() }
        refresh()
        email.addEventListener("input", { refresh() })

        onSubmit(form, submit, busyLabel = "Sending…") {
            resetEmail = email.value.trim()
            Session.api.forgotPassword(ForgotPasswordRequest(resetEmail))
            mode = Mode.RESET
            renderCard(card)
        }

        switchLink(card, body, "Back to sign in", Mode.SIGN_IN)
    }

    private fun renderResetForm(card: HTMLElement, body: HTMLElement) {
        body.child("h5", "card-title mb-3", "Enter your reset code")
        body.child(
            "p", "text-muted",
            "If $resetEmail has an account, we emailed it a 6-digit code. Enter the code within 15 minutes.",
        )

        val form = body.child("form") as HTMLFormElement
        val email = form.field("Email", "email", "email").apply { value = resetEmail }
        val code = form.field("Reset code", "text", "one-time-code").apply {
            setAttribute("inputmode", "numeric")
            setAttribute("maxlength", "6")
        }
        val password = form.field("New password", "password", "new-password", help = "At least 8 characters.")
        val submit = form.child("button", "btn btn-primary w-100", "Reset password") {
            setAttribute("type", "submit")
        } as HTMLButtonElement

        fun refresh() {
            submit.disabled = email.value.isBlank() || code.value.trim().length != 6 || password.value.length < 8
        }
        refresh()
        listOf(email, code, password).forEach { it.addEventListener("input", { refresh() }) }

        onSubmit(form, submit, busyLabel = "Resetting…") {
            val resp = Session.api.resetPassword(
                ResetPasswordRequest(email.value.trim(), code.value.trim(), password.value)
            )
            finishSignIn(resp)
        }

        switchLink(card, body, "Didn't get a code? Send a new one", Mode.FORGOT)
        switchLink(card, body, "Back to sign in", Mode.SIGN_IN)
    }
}
