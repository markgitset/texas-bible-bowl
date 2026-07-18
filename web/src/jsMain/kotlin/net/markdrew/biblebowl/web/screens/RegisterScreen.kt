package net.markdrew.biblebowl.web.screens

import kotlinx.browser.window
import kotlinx.coroutines.launch
import net.markdrew.biblebowl.api.CongregationDto
import net.markdrew.biblebowl.api.CreateCongregationRequest
import net.markdrew.biblebowl.api.MyRegistrationResponse
import net.markdrew.biblebowl.api.RegistrationDto
import net.markdrew.biblebowl.api.RegistrationStatus
import net.markdrew.biblebowl.api.Role
import net.markdrew.biblebowl.api.ScopeType
import net.markdrew.biblebowl.api.ShirtSize
import net.markdrew.biblebowl.api.TeamDto
import net.markdrew.biblebowl.api.UpsertRosterEntryRequest
import net.markdrew.biblebowl.api.division
import net.markdrew.biblebowl.api.feesNote
import net.markdrew.biblebowl.api.formatCents
import net.markdrew.biblebowl.api.formatClaimCode
import net.markdrew.biblebowl.api.formatIsoDate
import net.markdrew.biblebowl.client.ApiException
import net.markdrew.biblebowl.web.Session
import net.markdrew.biblebowl.web.Shell
import net.markdrew.biblebowl.web.child
import net.markdrew.biblebowl.web.clear
import net.markdrew.biblebowl.web.errorLine
import net.markdrew.biblebowl.web.onClick
import net.markdrew.biblebowl.web.spinner
import net.markdrew.biblebowl.api.isValidBirthdate
import org.w3c.dom.Element
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLOptionElement
import org.w3c.dom.HTMLSelectElement

/**
 * Coach registration flow (docs/gui-redesign.md §5E): a 4-step stepper — congregation → teams →
 * roster → review & submit — on a single hash route. Resumability comes from the server
 * (`GET /registration/mine`), not the URL: reloading lands on the first incomplete step. The
 * route itself only requires sign-in; step 1 is exactly where a signed-in non-coach becomes a
 * coach (creating a new congregation self-serve grants the scoped COACH role), and every
 * mutation is scope-checked server-side regardless of what's rendered.
 */
object RegisterScreen {

    private const val STEPS = 4

    private var loaded: MyRegistrationResponse? = null
    private var step = 1
    private var error: String? = null
    private lateinit var content: HTMLElement

    private val congregation: CongregationDto? get() = loaded?.congregations?.firstOrNull()
    private val registration: RegistrationDto? get() = loaded?.registration

    /** Admins may keep editing outside the window (the server exempts them too). */
    private val canEdit: Boolean
        get() = loaded?.windowOpen == true ||
            Session.user?.roles?.any { it.role == Role.ADMIN && it.scopeType == ScopeType.GLOBAL } == true

    fun render(container: HTMLElement) {
        container.child("h1", "page-title", "Register my teams")
        content = container.child("div")
        content.spinner()
        error = null
        Shell.scope.launch {
            try {
                loaded = Session.api.myRegistration()
                step = firstIncompleteStep()
                renderContent()
            } catch (e: Throwable) {
                content.clear()
                content.errorLine("Could not load your registration: ${e.message}")
            }
        }
    }

    private fun firstIncompleteStep(): Int = when {
        congregation == null -> 1
        registration?.teams.isNullOrEmpty() -> 2
        registration!!.teams.all { it.members.isEmpty() } -> 3
        else -> 4
    }

    /** The furthest step the current data supports (completed steps stay clickable). */
    private fun maxReachableStep(): Int = when {
        congregation == null -> 1
        registration?.teams.isNullOrEmpty() -> 2
        registration!!.teams.all { it.members.isEmpty() } -> 3
        else -> STEPS
    }

    private fun renderContent() {
        content.clear()
        renderWindowBanner(content)
        renderStepper(content)
        error?.let { content.errorLine(it) }
        when (step.coerceAtMost(maxReachableStep())) {
            1 -> renderCongregationStep(content)
            2 -> renderTeamsStep(content)
            3 -> renderRosterStep(content)
            else -> renderReviewStep(content)
        }
    }

    private fun renderWindowBanner(parent: Element) {
        if (canEdit) return
        val season = Session.season
        val message = when {
            season.registrationOpensOn == null ->
                "Registration for ${season.eventYear} hasn't opened yet — check back soon."
            else ->
                "Registration is closed (it ran ${formatIsoDate(season.registrationOpensOn)} through " +
                    "${formatIsoDate(season.registrationClosesOn)}). Your registration is shown read-only."
        }
        parent.child("div", "alert alert-warning", message)
    }

    private fun renderStepper(parent: Element) {
        val labels = listOf("Congregation", "Teams", "Roster", "Review & submit")
        val reachable = maxReachableStep()
        parent.child("div", "d-flex flex-wrap gap-2 mb-3") {
            labels.forEachIndexed { i, label ->
                val n = i + 1
                val classes = when {
                    n == step -> "btn btn-sm btn-primary"
                    n <= reachable -> "btn btn-sm btn-outline-primary"
                    else -> "btn btn-sm btn-outline-secondary disabled"
                }
                child("button", classes, "$n. $label") {
                    setAttribute("type", "button")
                    if (n <= reachable) onClick { step = n; error = null; renderContent() }
                }
            }
        }
    }

    // --- Step 1: congregation ---------------------------------------------------------------

    private fun renderCongregationStep(parent: Element) {
        val existing = congregation
        if (existing != null) {
            parent.child("div", "card section-card mb-3") {
                child("div", "card-body") {
                    child("h5", "card-title", "Your congregation")
                    child("p", "mb-0", "${existing.name} — ${cityStateLine(existing)}")
                    if (existing.mailingAddress.isNotBlank()) {
                        child("p", "text-muted small mb-0", "${existing.mailingAddress}, ${cityStateLine(existing)} ${existing.zip}")
                    }
                }
            }
            parent.nextButton("Continue to teams", 2)
            return
        }

        parent.child("p", "text-muted", "Registration is done by congregation. Find yours, or start it if it's new.")

        // Start a new congregation (self-serve: creating one makes you its coach).
        parent.child("div", "card section-card mb-3") {
            child("div", "card-body") {
                child("h5", "card-title", "Start a new congregation")
                val form = child("form")
                lateinit var name: HTMLInputElement
                lateinit var address: HTMLInputElement
                lateinit var city: HTMLInputElement
                lateinit var state: HTMLInputElement
                lateinit var zip: HTMLInputElement
                form.child("div", "mb-2") {
                    child("label", "form-label", "Congregation name")
                    name = child("input", "form-control") as HTMLInputElement
                }
                form.child("div", "mb-2") {
                    child("label", "form-label", "Mailing address")
                    address = child("input", "form-control") as HTMLInputElement
                    address.setAttribute("placeholder", "Street address or PO Box")
                }
                form.child("div", "d-flex flex-wrap gap-2 mb-3") {
                    child("div", "flex-grow-1") {
                        child("label", "form-label", "City")
                        city = child("input", "form-control") as HTMLInputElement
                    }
                    child("div") {
                        child("label", "form-label", "State")
                        state = child("input", "form-control") as HTMLInputElement
                        state.value = "TX"
                        state.setAttribute("maxlength", "2")
                        state.setAttribute("size", "3")
                    }
                    child("div") {
                        child("label", "form-label", "ZIP")
                        zip = child("input", "form-control") as HTMLInputElement
                        zip.setAttribute("maxlength", "10")
                        zip.setAttribute("size", "6")
                    }
                }
                // Deliberately enabled even outside the window: creating a congregation is
                // onboarding, not registration (the server draws the same line).
                val create = form.child("button", "btn btn-primary", "Create & continue") {
                    setAttribute("type", "submit")
                } as HTMLButtonElement
                val slot = form.child("div")
                form.addEventListener("submit", { event ->
                    event.preventDefault()
                    val fields = listOf(name, address, city, state, zip)
                    if (fields.any { it.value.isBlank() }) return@addEventListener
                    create.disabled = true
                    slot.clear()
                    Shell.scope.launch {
                        try {
                            Session.api.createCongregation(
                                CreateCongregationRequest(
                                    name = name.value,
                                    city = city.value,
                                    state = state.value,
                                    mailingAddress = address.value,
                                    zip = zip.value,
                                )
                            )
                            Session.api.refreshUser() // pick up the new scoped COACH grant
                            loaded = Session.api.myRegistration()
                            step = 2
                            renderContent()
                        } catch (e: Throwable) {
                            create.disabled = false
                            // Only the name+city dupe (409) gets the "contact us to claim it" flow;
                            // other errors (e.g. the adult-only rule) show the server's message as-is.
                            val message = if ((e as? ApiException)?.status == 409) contactUsMessage(e.message)
                                else e.message ?: "Something went wrong"
                            slot.child("div", "alert alert-warning mt-3", message)
                        }
                    }
                })
            }
        }

        // Find an existing congregation (claiming one goes through an admin).
        parent.child("div", "card section-card mb-3") {
            child("div", "card-body") {
                child("h5", "card-title", "Find your congregation")
                val search = child("input", "form-control mb-2") as HTMLInputElement
                search.setAttribute("placeholder", "Search by name or city…")
                val results = child("div")
                search.addEventListener("input", {
                    val query = search.value.trim()
                    results.clear()
                    if (query.length < 2) return@addEventListener
                    Shell.scope.launch {
                        runCatching { Session.api.searchCongregations(query) }.onSuccess { found ->
                            results.clear()
                            if (found.isEmpty()) {
                                results.child("p", "text-muted mb-0", "No match — start it above.")
                            }
                            found.forEach { c ->
                                results.child("button", "btn btn-outline-primary btn-sm me-2 mb-2", "${c.name} — ${c.city}") {
                                    setAttribute("type", "button")
                                    onClick {
                                        results.clear()
                                        results.child("div", "alert alert-warning", contactUsMessage(null))
                                    }
                                }
                            }
                        }
                    }
                })
            }
        }
    }

    /** "Austin, TX" — or just "Austin" for congregations created before state was collected. */
    private fun cityStateLine(cong: CongregationDto): String =
        if (cong.state.isBlank()) cong.city else "${cong.city}, ${cong.state}"

    private fun contactUsMessage(serverMessage: String?): String =
        (serverMessage?.let { "$it. " } ?: "") +
            "That congregation is already in the system — contact us " +
            "(texasbiblebowl.org/contact) and an admin will add you as its coach."

    // --- Step 2: teams ----------------------------------------------------------------------

    private fun renderTeamsStep(parent: Element) {
        val cong = congregation ?: return
        parent.child("p", "text-muted",
            "A team has up to 4 contestants and competes in the division of its highest member " +
                "(divisions come from birthdates).")

        registration?.teams?.forEach { team ->
            parent.child("div", "d-flex align-items-center gap-2 mb-2") {
                val name = child("input", "form-control w-auto flex-grow-1") as HTMLInputElement
                name.value = team.name
                name.disabled = !canEdit
                name.addEventListener("change", {
                    if (name.value.isNotBlank() && name.value != team.name) {
                        mutate { Session.api.renameTeam(team.id, name.value) }
                    }
                })
                divisionBadge(this, team)
                child("span", "text-muted small", "${team.members.size}/4")
                if (canEdit) {
                    child("button", "btn btn-outline-danger btn-sm", "Delete") {
                        setAttribute("type", "button")
                        onClick {
                            if (team.members.isEmpty() ||
                                window.confirm("Delete ${team.name} and its ${team.members.size} roster entries?")
                            ) {
                                mutate { Session.api.deleteTeam(team.id) }
                            }
                        }
                    }
                }
            }
        }

        if (canEdit) {
            parent.child("form", "d-flex gap-2 mt-3") {
                val name = child("input", "form-control w-auto flex-grow-1") as HTMLInputElement
                name.setAttribute("placeholder", "New team name")
                child("button", "btn btn-primary", "Add team") { setAttribute("type", "submit") }
                addEventListener("submit", { event ->
                    event.preventDefault()
                    if (name.value.isBlank()) return@addEventListener
                    mutate { Session.api.addTeam(cong.id, name.value) }
                })
            }
        }

        if (registration?.teams?.isNotEmpty() == true) parent.nextButton("Continue to roster", 3)
    }

    private fun divisionBadge(parent: Element, team: TeamDto) {
        val division = team.division(Session.season)
        parent.child(
            "span",
            "badge " + (if (division == null) "text-bg-secondary" else "text-bg-primary"),
            division?.displayName ?: "Empty",
        )
    }

    // --- Step 3: roster ---------------------------------------------------------------------

    private fun renderRosterStep(parent: Element) {
        val reg = registration ?: return
        reg.teams.forEach { team -> renderTeamRoster(parent, team) }

        val contestants = reg.teams.sumOf { it.members.size }
        parent.child("div", "card section-card mb-3") {
            child("div", "card-body py-2") {
                child(
                    "p", "mb-0 fw-semibold",
                    "Total: ${formatCents(reg.totalCents)}" +
                        " ($contestants contestant${if (contestants == 1) "" else "s"} × " +
                        "${formatCents(Session.season.priceContestantCents)}, t-shirts included)",
                )
                Session.season.feesNote.takeIf { it.isNotEmpty() }?.let { child("p", "text-muted small mb-0", it) }
            }
        }
        if (contestants > 0) parent.nextButton("Continue to review", 4)
    }

    private fun renderTeamRoster(parent: Element, team: TeamDto) {
        parent.child("div", "card section-card mb-3") {
            child("div", "card-body") {
                child("h5", "card-title") {
                    append("${team.name} ")
                    divisionBadge(this, team)
                }
                team.members.forEach { member ->
                    child("div", "d-flex flex-wrap align-items-center gap-2 mb-2") {
                        val name = child("input", "form-control w-auto flex-grow-1") as HTMLInputElement
                        name.value = member.name
                        name.disabled = !canEdit
                        val (adult, birthdate) = eligibilityControls(this, "member-${member.id}", member.birthdate)
                        val shirt = shirtSelect(this, member.shirtSize)
                        val save = {
                            if (name.value.isNotBlank() && (adult.checked || isValidBirthdate(birthdate.value))) {
                                mutate {
                                    Session.api.updateRosterEntry(
                                        member.id,
                                        UpsertRosterEntryRequest(
                                            name.value,
                                            birthdate.value.takeIf { it.isNotBlank() }?.takeUnless { adult.checked },
                                            ShirtSize.valueOf(shirt.value),
                                        ),
                                    )
                                }
                            }
                        }
                        listOf<HTMLElement>(name, adult, birthdate, shirt).forEach { el ->
                            el.addEventListener("change", { save() })
                            (el as? HTMLSelectElement)?.disabled = !canEdit
                            (el as? HTMLInputElement)?.disabled = !canEdit
                        }
                        claimCodeChip(this, member.claimCode)
                        if (canEdit) {
                            child("button", "btn btn-outline-danger btn-sm", "Remove") {
                                setAttribute("type", "button")
                                onClick { mutate { Session.api.deleteRosterEntry(member.id) } }
                            }
                        }
                    }
                }
                when {
                    !canEdit -> {}
                    team.members.size >= 4 ->
                        child("p", "text-muted small mb-0", "This team is full (4 contestants max).")
                    else -> renderAddMemberForm(this, team)
                }
            }
        }
    }

    private fun renderAddMemberForm(parent: Element, team: TeamDto) {
        parent.child("form", "d-flex flex-wrap gap-2 mt-2") {
            val name = child("input", "form-control w-auto flex-grow-1") as HTMLInputElement
            name.setAttribute("placeholder", "Contestant name")
            val (adult, birthdate) = eligibilityControls(this, "add-${team.id}", initialBirthdate = "")
            val shirt = shirtSelect(this, ShirtSize.AM)
            child("button", "btn btn-primary", "Add") { setAttribute("type", "submit") }
            addEventListener("submit", { event ->
                event.preventDefault()
                if (name.value.isBlank()) return@addEventListener
                if (!adult.checked && !isValidBirthdate(birthdate.value)) return@addEventListener
                mutate {
                    Session.api.addRosterEntry(
                        team.id,
                        UpsertRosterEntryRequest(
                            name.value,
                            birthdate.value.takeIf { it.isNotBlank() }?.takeUnless { adult.checked },
                            ShirtSize.valueOf(shirt.value),
                        ),
                    )
                }
            })
        }
        parent.child("p", "text-muted small mt-2 mb-0",
            "The birthdate places each contestant in the right division; check Adult instead for " +
                "adult-division contestants. Each contestant gets a claim code — share it so they " +
                "(or a parent) can link their own account later.")
    }

    /**
     * The birthdate/adult pair used on roster rows: a date input plus an "Adult" checkbox that
     * hides it (adults don't give a birthdate). Returns (adult, birthdate); null birthdates are
     * represented as an empty date value.
     */
    private fun eligibilityControls(
        parent: Element,
        idSuffix: String,
        initialBirthdate: String?,
    ): Pair<HTMLInputElement, HTMLInputElement> {
        lateinit var adult: HTMLInputElement
        lateinit var birthdate: HTMLInputElement
        birthdate = parent.child("input", "form-control w-auto") as HTMLInputElement
        birthdate.type = "date"
        birthdate.value = initialBirthdate ?: ""
        birthdate.setAttribute("title", "Birthdate")
        parent.child("div", "form-check align-self-center") {
            adult = (child("input", "form-check-input") as HTMLInputElement).apply {
                type = "checkbox"
                id = "roster-adult-$idSuffix"
                // An existing entry without a birthdate is an adult; the add form starts as youth.
                checked = initialBirthdate == null
            }
            child("label", "form-check-label", "Adult") { setAttribute("for", "roster-adult-$idSuffix") }
        }
        fun toggle() = birthdate.classList.toggle("d-none", adult.checked)
        toggle()
        adult.addEventListener("change", { toggle() })
        return adult to birthdate
    }

    private fun shirtSelect(parent: Element, selected: ShirtSize): HTMLSelectElement {
        val select = parent.child("select", "form-select w-auto") as HTMLSelectElement
        ShirtSize.entries.forEach { size ->
            val option = select.child("option", text = size.displayName) as HTMLOptionElement
            option.value = size.name
            if (size == selected) option.selected = true
        }
        return select
    }

    private fun claimCodeChip(parent: Element, code: String) {
        parent.child("span", "badge text-bg-light border font-monospace", formatClaimCode(code)) {
            setAttribute("title", "Claim code — click to copy")
            setAttribute("role", "button")
            onClick {
                window.navigator.clipboard.writeText(formatClaimCode(code))
                textContent = "Copied!"
                window.setTimeout({ textContent = formatClaimCode(code) }, 1200)
            }
        }
    }

    // --- Step 4: review & submit ------------------------------------------------------------

    private fun renderReviewStep(parent: Element) {
        val reg = registration ?: return
        val cong = reg.congregation

        parent.child("div", "card section-card mb-3") {
            child("div", "card-body") {
                child("h5", "card-title", "${cong.name} — ${cityStateLine(cong)} · ${reg.seasonYear} season")
                child("table", "table mb-2") {
                    child("thead") {
                        child("tr") {
                            listOf("Team", "Division", "Contestants").forEach { child("th", text = it) }
                        }
                    }
                    child("tbody") {
                        reg.teams.forEach { team ->
                            child("tr") {
                                child("td", text = team.name)
                                child("td", text = team.division(Session.season)?.displayName ?: "—")
                                child("td", text = team.members.joinToString { it.name })
                            }
                        }
                    }
                }
                val contestants = reg.teams.sumOf { it.members.size }
                child("p", "fw-semibold mb-1", "Total due: ${formatCents(reg.totalCents)} ($contestants contestants)")
                Session.season.feesNote.takeIf { it.isNotEmpty() }?.let { child("p", "text-muted small mb-0", it) }
            }
        }

        parent.child("div", "card section-card mb-3") {
            child("div", "card-body") {
                child("h5", "card-title", "Payment")
                child("p", "mb-1", "Mail a check payable to Texas Bible Bowl:")
                child("p", "mb-0") {
                    innerHTML = "Texas Bible Bowl<br>10431 Remuda View Drive<br>San Antonio, TX 78254"
                }
            }
        }

        if (reg.status == RegistrationStatus.SUBMITTED) {
            parent.child(
                "p", "tbb-gold fw-semibold",
                "Submitted ${reg.submittedAt?.take(10) ?: ""} — you can keep editing and re-submit until " +
                    "${formatIsoDate(Session.season.registrationClosesOn)}.",
            )
        }
        if (canEdit) {
            val label = if (reg.status == RegistrationStatus.SUBMITTED) "Update registration" else "Submit registration"
            parent.child("button", "btn btn-primary btn-lg", label) {
                setAttribute("type", "button")
                onClick { mutate { Session.api.submitRegistration(cong.id) } }
            }
        }
    }

    // --- shared -----------------------------------------------------------------------------

    /** Runs a mutation, adopts the returned registration, and re-renders (API errors show inline). */
    private fun mutate(call: suspend () -> RegistrationDto) {
        Shell.scope.launch {
            try {
                val updated = call()
                loaded = loaded?.copy(registration = updated)
                error = null
            } catch (e: Throwable) {
                error = e.message ?: "Something went wrong"
            }
            renderContent()
        }
    }

    private fun Element.nextButton(label: String, nextStep: Int) {
        child("div", "mt-3") {
            child("button", "btn btn-primary", label) {
                setAttribute("type", "button")
                onClick { step = nextStep; error = null; renderContent() }
            }
        }
    }
}
