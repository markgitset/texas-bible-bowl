package net.markdrew.biblebowl.web.screens

import kotlinx.browser.window
import kotlinx.coroutines.launch
import net.markdrew.biblebowl.api.CongregationDto
import net.markdrew.biblebowl.api.CreateCongregationRequest
import net.markdrew.biblebowl.api.Gender
import net.markdrew.biblebowl.api.MyRegistrationResponse
import net.markdrew.biblebowl.api.RegistrationDto
import net.markdrew.biblebowl.api.RegistrationStatus
import net.markdrew.biblebowl.api.ReturningContestantDto
import net.markdrew.biblebowl.api.Role
import net.markdrew.biblebowl.api.RosterEntryDto
import net.markdrew.biblebowl.api.ScopeType
import net.markdrew.biblebowl.api.ShirtSize
import net.markdrew.biblebowl.api.TeamDto
import net.markdrew.biblebowl.api.UpdateCongregationRequest
import net.markdrew.biblebowl.api.UpsertIndividualRequest
import net.markdrew.biblebowl.api.UpsertRosterEntryRequest
import net.markdrew.biblebowl.api.contestantCount
import net.markdrew.biblebowl.api.division
import net.markdrew.biblebowl.api.divisionForBirthdate
import net.markdrew.biblebowl.api.divisionLabel
import net.markdrew.biblebowl.api.feesNote
import net.markdrew.biblebowl.api.isInexperienced
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

    /** A globally-scoped admin (may edit outside the window, and may change a set congregation code). */
    private val isAdmin: Boolean
        get() = Session.user?.roles?.any { it.role == Role.ADMIN && it.scopeType == ScopeType.GLOBAL } == true

    /** Admins may keep editing outside the window (the server exempts them too). */
    private val canEdit: Boolean
        get() = loaded?.windowOpen == true || isAdmin

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

    /** Team members plus individual (adult) contestants. */
    private val contestants: Int get() = registration?.contestantCount ?: 0

    private fun firstIncompleteStep(): Int = when {
        congregation == null -> 1
        registration?.teams.isNullOrEmpty() && registration?.individuals.isNullOrEmpty() &&
            registration?.unassigned.isNullOrEmpty() && loaded?.returningCandidates.isNullOrEmpty() -> 2
        contestants == 0 -> 3
        registration?.unassigned.isNullOrEmpty() == false -> 3 // land on the roster to place them
        else -> 4
    }

    /**
     * The furthest step the current data supports (completed steps stay clickable). The roster step
     * is reachable without any teams: adults are added there as individuals, so an adults-only
     * congregation never touches the teams step.
     */
    private fun maxReachableStep(): Int = when {
        congregation == null -> 1
        contestants == 0 -> 3
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
            if (canEdit) renderCongregationEditForm(parent, existing)
            else renderCongregationSummary(parent, existing)
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
                lateinit var code: HTMLInputElement
                lateinit var address: HTMLInputElement
                lateinit var city: HTMLInputElement
                lateinit var state: HTMLInputElement
                lateinit var zip: HTMLInputElement
                form.child("div", "mb-2") {
                    child("label", "form-label", "Congregation name")
                    name = child("input", "form-control") as HTMLInputElement
                }
                // The two-letter code, suggested from the name (a coach can override). Once saved,
                // only an admin can change it.
                form.child("div", "mb-2") {
                    child("label", "form-label", "Congregation code")
                    code = child("input", "form-control w-auto") as HTMLInputElement
                    code.setAttribute("maxlength", "2")
                    code.setAttribute("size", "4")
                    code.setAttribute("placeholder", "e.g. WB")
                    code.style.textTransform = "uppercase"
                    child("div", "form-text",
                        "A two-letter shorthand for your congregation (suggested from the name — " +
                            "\"West Bexar County Church of Christ\" → WB). You can change it now; once saved, " +
                            "only an admin can.")
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
                // Suggest a code from the name until the coach types their own; debounced so we
                // don't hit the backend on every keystroke.
                var codeEdited = false
                var suggestTimer: Int? = null
                name.addEventListener("input", {
                    if (codeEdited) return@addEventListener
                    suggestTimer?.let { window.clearTimeout(it) }
                    val query = name.value.trim()
                    if (query.isBlank()) return@addEventListener
                    suggestTimer = window.setTimeout({
                        Shell.scope.launch {
                            runCatching { Session.api.suggestCongregationCode(query) }
                                .onSuccess { suggestion -> if (!codeEdited) code.value = suggestion }
                        }
                    }, 300)
                })
                code.addEventListener("input", { codeEdited = true })
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
                                    code = code.value,
                                )
                            )
                            Session.api.refreshUser() // pick up the new scoped COACH grant
                            loaded = Session.api.myRegistration()
                            step = 2
                            renderContent()
                        } catch (e: Throwable) {
                            create.disabled = false
                            // Only the name+city dupe gets the "contact us to claim it" flow; a taken
                            // code or any other error shows the server's message as-is.
                            val ex = e as? ApiException
                            val message = if (ex?.status == 409 && ex.errorCode == "congregation_exists")
                                contactUsMessage(e.message)
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

    /** Read-only congregation card, shown once registration has closed (or before it opens). */
    private fun renderCongregationSummary(parent: Element, existing: CongregationDto) {
        parent.child("div", "card section-card mb-3") {
            child("div", "card-body") {
                child("h5", "card-title", "Your congregation")
                child("p", "mb-0") {
                    append("${existing.name} — ${cityStateLine(existing)}")
                    if (existing.code.isNotBlank()) child("span", "badge text-bg-secondary ms-2", existing.code)
                }
                if (existing.mailingAddress.isNotBlank()) {
                    child("p", "text-muted small mb-0", "${existing.mailingAddress}, ${cityStateLine(existing)} ${existing.zip}")
                }
            }
        }
    }

    /**
     * Editable congregation card, shown while registration is open. Name, address, city, state, and
     * ZIP are freely editable. The two-letter congregation code is set-once for a coach: editable
     * while it's still blank, then locked (only an admin can change it) — the server enforces this.
     */
    private fun renderCongregationEditForm(parent: Element, existing: CongregationDto) {
        parent.child("div", "card section-card mb-3") {
            child("div", "card-body") {
                child("h5", "card-title", "Your congregation")
                child("p", "text-muted small", "Fix a typo in your congregation's details below.")
                val form = child("form")
                lateinit var name: HTMLInputElement
                lateinit var address: HTMLInputElement
                lateinit var city: HTMLInputElement
                lateinit var state: HTMLInputElement
                lateinit var zip: HTMLInputElement
                lateinit var code: HTMLInputElement
                form.child("div", "mb-2") {
                    child("label", "form-label", "Congregation name")
                    name = child("input", "form-control") as HTMLInputElement
                    name.value = existing.name
                }
                // The two-letter code: a coach picks it once, then only an admin can change it.
                val codeLocked = existing.code.isNotBlank() && !isAdmin
                form.child("div", "mb-2") {
                    child("label", "form-label", "Congregation code")
                    code = child("input", "form-control w-auto") as HTMLInputElement
                    code.value = existing.code
                    code.setAttribute("maxlength", "2")
                    code.setAttribute("size", "4")
                    code.setAttribute("placeholder", "e.g. FB")
                    code.style.textTransform = "uppercase"
                    code.disabled = codeLocked
                    val note = if (codeLocked)
                        "Your two-letter code is set — contact us and an admin can change it."
                    else
                        "Pick a unique two-letter code for your congregation. Once you save it, only an " +
                            "admin can change it."
                    child("div", "form-text", note)
                }
                // A congregation that predates codes has none — suggest one from its name.
                if (!codeLocked && existing.code.isBlank()) {
                    Shell.scope.launch {
                        runCatching { Session.api.suggestCongregationCode(existing.name) }
                            .onSuccess { if (code.value.isBlank()) code.value = it }
                    }
                }
                form.child("div", "mb-2") {
                    child("label", "form-label", "Mailing address")
                    address = child("input", "form-control") as HTMLInputElement
                    address.setAttribute("placeholder", "Street address or PO Box")
                    address.value = existing.mailingAddress
                }
                form.child("div", "d-flex flex-wrap gap-2 mb-3") {
                    child("div", "flex-grow-1") {
                        child("label", "form-label", "City")
                        city = child("input", "form-control") as HTMLInputElement
                        city.value = existing.city
                    }
                    child("div") {
                        child("label", "form-label", "State")
                        state = child("input", "form-control") as HTMLInputElement
                        state.value = existing.state
                        state.setAttribute("maxlength", "2")
                        state.setAttribute("size", "3")
                    }
                    child("div") {
                        child("label", "form-label", "ZIP")
                        zip = child("input", "form-control") as HTMLInputElement
                        zip.setAttribute("maxlength", "10")
                        zip.setAttribute("size", "6")
                        zip.value = existing.zip
                    }
                }
                val save = form.child("button", "btn btn-primary", "Save changes") {
                    setAttribute("type", "submit")
                } as HTMLButtonElement
                val slot = form.child("div")
                form.addEventListener("submit", { event ->
                    event.preventDefault()
                    if (listOf(name, address, city, state, zip).any { it.value.isBlank() }) return@addEventListener
                    save.disabled = true
                    slot.clear()
                    Shell.scope.launch {
                        try {
                            Session.api.updateCongregation(
                                existing.id,
                                UpdateCongregationRequest(
                                    name = name.value,
                                    city = city.value,
                                    state = state.value,
                                    mailingAddress = address.value,
                                    zip = zip.value,
                                    code = code.value,
                                ),
                            )
                            // Refresh so the embedded registration.congregation (review step) matches too.
                            loaded = Session.api.myRegistration()
                            save.disabled = false
                            slot.clear()
                            slot.child("div", "alert alert-success mt-3 mb-0", "Saved.")
                        } catch (e: Throwable) {
                            save.disabled = false
                            slot.child("div", "alert alert-warning mt-3 mb-0", e.message ?: "Something went wrong")
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
            "A team has up to 4 contestants (grades 3–12, from birthdates) and competes in the " +
                "division of its highest member. Adults aren't placed on a team — add them as " +
                "individual contestants on the roster step.")

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
                                window.confirm(
                                    "Delete ${team.name}? Its ${team.members.size} contestant" +
                                        "${if (team.members.size == 1) "" else "s"} won't be deleted — " +
                                        "they'll move to Unassigned so you can place them on another team.",
                                )
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

        parent.nextButton("Continue to roster", 3)
    }

    /** The registration's season (falls back to the current season before the draft exists). */
    private val seasonYear: String get() = registration?.seasonYear ?: Session.season.eventYear

    private fun divisionBadge(parent: Element, team: TeamDto) {
        val division = team.division(Session.season)
        parent.child(
            "span",
            "badge " + (if (division == null) "text-bg-secondary" else "text-bg-primary"),
            division?.let { divisionLabel(it, team.isInexperienced(seasonYear)) } ?: "Empty",
        )
    }

    // --- Step 3: roster ---------------------------------------------------------------------

    private fun renderRosterStep(parent: Element) {
        registration?.teams?.forEach { team -> renderTeamRoster(parent, team) }
        renderUnassignedCard(parent)
        renderReturningCard(parent)
        renderIndividualsCard(parent)

        registration?.let { reg ->
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
        }
        if (contestants > 0) parent.nextButton("Continue to review", 4)
    }

    /** Eligible youth not on a team (e.g. their team was deleted) — assign each, or a registrar will. */
    private fun renderUnassignedCard(parent: Element) {
        val unassigned = registration?.unassigned.orEmpty()
        if (unassigned.isEmpty()) return
        parent.child("div", "card section-card border-warning mb-3") {
            child("div", "card-body") {
                child("h5", "card-title") {
                    append("Unassigned contestants ")
                    child("span", "badge text-bg-warning", unassigned.size.toString())
                }
                child("p", "text-muted small",
                    "These contestants are eligible but not on a team yet (their team was deleted). " +
                        "Pick a team for each below. You can still submit with some here — a registrar " +
                        "will place them on a team for you.")
                if (registration?.teams.isNullOrEmpty()) {
                    child("p", "text-warning small mb-2",
                        "Add a team on the Teams step first, then assign these contestants to it.")
                }
                unassigned.forEach { member -> renderContestantRow(this, member, currentTeamId = null) }
            }
        }
    }

    /**
     * Contestants who competed here before but aren't on this season's roster yet. Team assignments
     * are per-year, so a new season starts with none — these are offered for one-click enrollment
     * (which is what starts counting/billing them). Only shown when the coach can edit.
     */
    private fun renderReturningCard(parent: Element) {
        val candidates = loaded?.returningCandidates.orEmpty()
        if (candidates.isEmpty() || !canEdit) return
        val cong = congregation ?: return
        parent.child("div", "card section-card border-info mb-3") {
            child("div", "card-body") {
                child("h5", "card-title") {
                    append("Returning contestants ")
                    child("span", "badge text-bg-info", candidates.size.toString())
                }
                child("p", "text-muted small",
                    "These competed for your congregation before but aren't on this year's roster yet. " +
                        "Add each to a team (or the roster) — they're only counted once you do.")
                candidates.forEach { candidate -> renderReturningRow(this, candidate, cong.id) }
            }
        }
    }

    private fun renderReturningRow(parent: Element, candidate: ReturningContestantDto, congregationId: String) {
        parent.child("div", "d-flex flex-wrap align-items-center gap-2 mb-2") {
            child("span", "flex-grow-1") {
                append("${candidate.name} ")
                val division = candidate.birthdate?.let { Session.season.divisionForBirthdate(it) }
                child(
                    "span", "badge " + (if (division == null) "text-bg-secondary" else "text-bg-primary"),
                    division?.displayName ?: "—",
                )
                candidate.lastSeasonYear?.let { child("span", "text-muted small ms-2", "last competed $it") }
            }
            val shirt = shirtSelect(this, candidate.lastShirtSize ?: ShirtSize.YM)
            val teamSel = child("select", "form-select form-select-sm w-auto") as HTMLSelectElement
            teamSel.setAttribute("title", "Team")
            (teamSel.child("option", text = "— Unassigned —") as HTMLOptionElement).value = ""
            registration?.teams.orEmpty().forEach { team ->
                (teamSel.child("option", text = team.name) as HTMLOptionElement).value = team.id
            }
            child("button", "btn btn-sm btn-primary", "Add") {
                setAttribute("type", "button")
                onClick {
                    enroll {
                        Session.api.enrollContestant(
                            congregationId, candidate.contestantId,
                            ShirtSize.valueOf(shirt.value), teamSel.value.ifEmpty { null },
                        )
                    }
                }
            }
        }
    }

    /** Adult contestants aren't on any team — they're registered here, straight on the registration. */
    private fun renderIndividualsCard(parent: Element) {
        val cong = congregation ?: return
        parent.child("div", "card section-card mb-3") {
            child("div", "card-body") {
                child("h5", "card-title") {
                    append("Individual contestants ")
                    child("span", "badge text-bg-primary", "Adult")
                }
                child("p", "text-muted small",
                    "Adults aren't placed on a team — each competes individually in the Adult division.")
                registration?.individuals?.forEach { member ->
                    child("div", "d-flex flex-wrap align-items-center gap-2 mb-2") {
                        val name = child("input", "form-control w-auto flex-grow-1") as HTMLInputElement
                        name.value = member.name
                        name.disabled = !canEdit
                        val shirt = shirtSelect(this, member.shirtSize)
                        shirt.disabled = !canEdit
                        val gender = genderSelect(this, member.gender)
                        gender.disabled = !canEdit
                        val save = {
                            val chosenGender = genderOf(gender)
                            if (name.value.isNotBlank() && chosenGender != null) {
                                mutate {
                                    Session.api.updateIndividual(
                                        member.id,
                                        UpsertIndividualRequest(name.value, ShirtSize.valueOf(shirt.value), chosenGender),
                                    )
                                }
                            }
                        }
                        listOf<HTMLElement>(name, shirt, gender).forEach { it.addEventListener("change", { save() }) }
                        claimCodeChip(this, member.claimCode)
                        if (canEdit) {
                            child("button", "btn btn-outline-danger btn-sm", "Remove") {
                                setAttribute("type", "button")
                                onClick { mutate { Session.api.deleteIndividual(member.id) } }
                            }
                        }
                    }
                }
                if (canEdit) {
                    child("form", "d-flex flex-wrap gap-2 mt-2") {
                        val name = child("input", "form-control w-auto flex-grow-1") as HTMLInputElement
                        name.setAttribute("placeholder", "Adult contestant name")
                        val shirt = shirtSelect(this, ShirtSize.AM)
                        val gender = genderSelect(this, null)
                        child("button", "btn btn-primary", "Add") { setAttribute("type", "submit") }
                        addEventListener("submit", { event ->
                            event.preventDefault()
                            val chosenGender = genderOf(gender)
                            if (name.value.isBlank() || chosenGender == null) return@addEventListener
                            mutate {
                                Session.api.addIndividual(
                                    cong.id,
                                    UpsertIndividualRequest(name.value, ShirtSize.valueOf(shirt.value), chosenGender),
                                )
                            }
                        })
                    }
                }
            }
        }
    }

    private fun renderTeamRoster(parent: Element, team: TeamDto) {
        parent.child("div", "card section-card mb-3") {
            child("div", "card-body") {
                child("h5", "card-title") {
                    append("${team.name} ")
                    divisionBadge(this, team)
                }
                team.members.forEach { member -> renderContestantRow(this, member, currentTeamId = team.id) }
                when {
                    !canEdit -> {}
                    team.members.size >= 4 ->
                        child("p", "text-muted small mb-0", "This team is full (4 contestants max).")
                    else -> renderAddMemberForm(this, team)
                }
            }
        }
    }

    /**
     * One editable youth contestant row (name/birthdate/shirt/gender/1st-year), used both under a
     * team and in the Unassigned card. [currentTeamId] is the team the row belongs to (null when
     * unassigned); the team dropdown moves the contestant between teams or off to the pool.
     */
    private fun renderContestantRow(parent: Element, member: RosterEntryDto, currentTeamId: String?) {
        parent.child("div", "d-flex flex-wrap align-items-center gap-2 mb-2") {
            val name = child("input", "form-control w-auto flex-grow-1") as HTMLInputElement
            name.value = member.name
            name.disabled = !canEdit
            val birthdate = birthdateInput(this, member.birthdate)
            val shirt = shirtSelect(this, member.shirtSize)
            val gender = genderSelect(this, member.gender)
            val firstYear = firstYearCheck(this, member.isInexperienced(seasonYear))
            firstYear.disabled = !canEdit
            val save = {
                val chosenGender = genderOf(gender)
                if (name.value.isNotBlank() && isValidBirthdate(birthdate.value) && chosenGender != null) {
                    mutate {
                        Session.api.updateRosterEntry(
                            member.id,
                            UpsertRosterEntryRequest(
                                name.value, birthdate.value, ShirtSize.valueOf(shirt.value),
                                chosenGender, firstYear.checked,
                            ),
                        )
                    }
                }
            }
            listOf<HTMLElement>(name, birthdate, shirt, gender, firstYear).forEach { el ->
                el.addEventListener("change", { save() })
                (el as? HTMLSelectElement)?.disabled = !canEdit
                (el as? HTMLInputElement)?.disabled = !canEdit
            }
            teamAssignSelect(this, member, currentTeamId)
            claimCodeChip(this, member.claimCode)
            if (canEdit) {
                child("button", "btn btn-outline-danger btn-sm", "Remove") {
                    setAttribute("type", "button")
                    onClick { mutate { Session.api.deleteRosterEntry(member.id) } }
                }
            }
        }
    }

    /**
     * The per-contestant team picker: each team plus an "Unassigned" option. Changing it moves the
     * contestant (server enforces the 4-cap). Rendered only when there's somewhere to move to — at
     * least one team, or the contestant is currently on one.
     */
    private fun teamAssignSelect(parent: Element, member: RosterEntryDto, currentTeamId: String?) {
        val teams = registration?.teams.orEmpty()
        if (teams.isEmpty() && currentTeamId == null) return
        val select = parent.child("select", "form-select form-select-sm w-auto") as HTMLSelectElement
        select.setAttribute("title", "Team")
        select.disabled = !canEdit
        (select.child("option", text = "— Unassigned —") as HTMLOptionElement).value = ""
        teams.forEach { team ->
            val option = select.child("option", text = team.name) as HTMLOptionElement
            option.value = team.id
            if (team.id == currentTeamId) option.selected = true
        }
        select.addEventListener("change", {
            mutate { Session.api.assignMemberTeam(member.id, select.value.ifEmpty { null }) }
        })
    }

    private fun renderAddMemberForm(parent: Element, team: TeamDto) {
        parent.child("form", "d-flex flex-wrap gap-2 mt-2") {
            val name = child("input", "form-control w-auto flex-grow-1") as HTMLInputElement
            name.setAttribute("placeholder", "Contestant name")
            val birthdate = birthdateInput(this, initial = null)
            val shirt = shirtSelect(this, ShirtSize.YM)
            val gender = genderSelect(this, null)
            val firstYear = firstYearCheck(this, false)
            child("button", "btn btn-primary", "Add") { setAttribute("type", "submit") }
            addEventListener("submit", { event ->
                event.preventDefault()
                val chosenGender = genderOf(gender)
                if (name.value.isBlank() || !isValidBirthdate(birthdate.value) || chosenGender == null) return@addEventListener
                mutate {
                    Session.api.addRosterEntry(
                        team.id,
                        UpsertRosterEntryRequest(
                            name.value, birthdate.value, ShirtSize.valueOf(shirt.value),
                            chosenGender, firstYear.checked,
                        ),

                    )
                }
            })
        }
        parent.child("p", "text-muted small mt-2 mb-0",
            "The birthdate places each contestant in the right division (adults go under " +
                "Individual contestants instead). Each contestant gets a claim code — share it so " +
                "they (or a parent) can link their own account later.")
    }

    /** A team member's birthdate (drives their division; adults can't be on teams). */
    private fun birthdateInput(parent: Element, initial: String?): HTMLInputElement {
        val input = parent.child("input", "form-control w-auto") as HTMLInputElement
        input.type = "date"
        input.value = initial ?: ""
        input.setAttribute("title", "Birthdate")
        return input
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

    /** Gender select with a blank placeholder — [genderOf] returns null until one is chosen. */
    private fun genderSelect(parent: Element, selected: Gender?): HTMLSelectElement {
        val select = parent.child("select", "form-select w-auto") as HTMLSelectElement
        select.setAttribute("required", "")
        val placeholder = select.child("option", text = "Gender…") as HTMLOptionElement
        placeholder.value = ""
        placeholder.disabled = true
        placeholder.selected = selected == null
        Gender.entries.forEach { gender ->
            val option = select.child("option", text = gender.displayName) as HTMLOptionElement
            option.value = gender.name
            if (gender == selected) option.selected = true
        }
        return select
    }

    private fun genderOf(select: HTMLSelectElement): Gender? =
        select.value.takeIf { it.isNotEmpty() }?.let { Gender.valueOf(it) }

    private var firstYearSeq = 0

    /** "1st year" checkbox — marks a contestant inexperienced (first year competing). */
    private fun firstYearCheck(parent: Element, checked: Boolean): HTMLInputElement {
        lateinit var box: HTMLInputElement
        parent.child("div", "form-check align-self-center text-nowrap") {
            setAttribute("title", "First year competing — counts toward the inexperienced division")
            box = child("input", "form-check-input") as HTMLInputElement
            box.type = "checkbox"
            box.checked = checked
            box.id = "first-year-${++firstYearSeq}"
            child("label", "form-check-label", "1st year") { setAttribute("for", box.id) }
        }
        return box
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
                val price = Session.season.priceContestantCents
                fun feeMath(count: Int): String =
                    if (price == null) "TBD" else "$count × ${formatCents(price)} = ${formatCents(price * count)}"
                child("table", "table mb-2") {
                    child("thead") {
                        child("tr") {
                            listOf("Team", "Division", "Contestants", "Fees").forEach { child("th", text = it) }
                        }
                    }
                    child("tbody") {
                        reg.teams.forEach { team ->
                            child("tr") {
                                child("td", text = team.name)
                                child("td", text = team.division(Session.season)
                                    ?.let { divisionLabel(it, team.isInexperienced(seasonYear)) } ?: "—")
                                child("td", text = team.members.joinToString {
                                    it.name + if (it.isInexperienced(seasonYear)) " (1st year)" else ""
                                })
                                child("td", "text-nowrap", feeMath(team.members.size))
                            }
                        }
                        if (reg.unassigned.isNotEmpty()) {
                            child("tr", "table-warning") {
                                child("td", text = "Unassigned")
                                child("td", text = "—")
                                child("td", text = reg.unassigned.joinToString { it.name })
                                child("td", "text-nowrap", feeMath(reg.unassigned.size))
                            }
                        }
                        if (reg.individuals.isNotEmpty()) {
                            child("tr") {
                                child("td", text = "Individuals")
                                child("td", text = "Adult")
                                child("td", text = reg.individuals.joinToString { it.name })
                                child("td", "text-nowrap", feeMath(reg.individuals.size))
                            }
                        }
                    }
                    child("tfoot") {
                        child("tr", "fw-semibold") {
                            child("td", text = "Total due ($contestants contestant${if (contestants == 1) "" else "s"})") {
                                setAttribute("colspan", "3")
                            }
                            child("td", "text-nowrap", formatCents(reg.totalCents))
                        }
                    }
                }
                child(
                    "p", "text-muted small mb-1",
                    "Fee per contestant: ${formatCents(price)} (one t-shirt included). Volunteers, guests, " +
                        "and extra shirts are paid at the door.",
                )
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
        if (canEdit && reg.unassigned.isNotEmpty()) {
            val n = reg.unassigned.size
            parent.child(
                "div", "alert alert-warning",
                "$n contestant${if (n == 1) "" else "s"} ${if (n == 1) "isn't" else "aren't"} on a team yet. " +
                    "You can go back to the roster step to place ${if (n == 1) "them" else "them"}, or submit now " +
                    "and a registrar will assign ${if (n == 1) "them" else "them"} to a team for you.",
            )
        }
        if (canEdit) {
            val label = if (reg.status == RegistrationStatus.SUBMITTED) "Update registration" else "Submit registration"
            parent.child("button", "btn btn-primary btn-lg", label) {
                setAttribute("type", "button")
                onClick {
                    val n = reg.unassigned.size
                    if (n > 0 && !window.confirm(
                            "$n contestant${if (n == 1) "" else "s"} ${if (n == 1) "isn't" else "aren't"} on a team " +
                                "yet. Submit anyway and let a registrar place them, or cancel to go back and assign " +
                                "them to a team yourself?",
                        )
                    ) {
                        step = 3
                        error = null
                        renderContent()
                        return@onClick
                    }
                    mutate { Session.api.submitRegistration(cong.id) }
                }
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

    /**
     * Like [mutate] but re-fetches the whole registration afterward — enrolling a returning
     * contestant both updates the roster and removes them from the candidate list, so a plain
     * registration swap isn't enough.
     */
    private fun enroll(call: suspend () -> RegistrationDto) {
        Shell.scope.launch {
            try {
                call()
                loaded = Session.api.myRegistration()
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
