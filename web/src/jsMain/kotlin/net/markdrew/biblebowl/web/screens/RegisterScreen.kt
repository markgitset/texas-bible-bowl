package net.markdrew.biblebowl.web.screens

import kotlinx.browser.window
import kotlinx.coroutines.launch
import net.markdrew.biblebowl.api.AwayMemberDto
import net.markdrew.biblebowl.api.CongregationDto
import net.markdrew.biblebowl.api.CreateCongregationRequest
import net.markdrew.biblebowl.api.ContactInfoDto
import net.markdrew.biblebowl.api.ContactPreference
import net.markdrew.biblebowl.api.Gender
import net.markdrew.biblebowl.api.AgeTier
import net.markdrew.biblebowl.api.ageTierFor
import net.markdrew.biblebowl.api.feeCentsFor
import net.markdrew.biblebowl.api.registrationFeeLines
import net.markdrew.biblebowl.api.GuestDto
import net.markdrew.biblebowl.api.MyRegistrationResponse
import net.markdrew.biblebowl.api.RegistrationDto
import net.markdrew.biblebowl.api.RegistrationStatus
import net.markdrew.biblebowl.api.RegistrationUpdateResponse
import net.markdrew.biblebowl.api.ReturningContestantDto
import net.markdrew.biblebowl.api.Role
import net.markdrew.biblebowl.api.RosterEntryDto
import net.markdrew.biblebowl.api.ScopeType
import net.markdrew.biblebowl.api.ShirtSize
import net.markdrew.biblebowl.api.TeamDto
import net.markdrew.biblebowl.api.UpdateCongregationRequest
import net.markdrew.biblebowl.api.UpsertGuestRequest
import net.markdrew.biblebowl.api.UpsertIndividualRequest
import net.markdrew.biblebowl.api.UpsertRosterEntryRequest
import net.markdrew.biblebowl.api.contestantCount
import net.markdrew.biblebowl.api.division
import net.markdrew.biblebowl.api.Division
import net.markdrew.biblebowl.api.divisionForBirthdate
import net.markdrew.biblebowl.api.gradeForGraduationYear
import net.markdrew.biblebowl.api.isSeededYouth
import net.markdrew.biblebowl.api.divisionLabel
import net.markdrew.biblebowl.api.feesNote
import net.markdrew.biblebowl.api.isInexperienced
import net.markdrew.biblebowl.api.formatCents
import net.markdrew.biblebowl.api.multiSite
import net.markdrew.biblebowl.api.siteFor
import net.markdrew.biblebowl.api.formatClaimCode
import net.markdrew.biblebowl.api.formatIsoDate
import net.markdrew.biblebowl.client.ApiException
import net.markdrew.biblebowl.web.Session
import net.markdrew.biblebowl.web.Shell
import net.markdrew.biblebowl.web.child
import net.markdrew.biblebowl.web.clear
import net.markdrew.biblebowl.web.errorLine
import net.markdrew.biblebowl.web.onClick
import net.markdrew.biblebowl.web.showBusy
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

    /** Registered guests (mostly volunteers) — they pay too, but aren't contestants. */
    private val guests: List<GuestDto> get() = registration?.guests ?: emptyList()

    private fun firstIncompleteStep(): Int = when {
        congregation == null -> 1
        // A multi-site season needs the event site chosen (part of the congregation step).
        Session.season.multiSite && Session.season.siteFor(registration?.siteId) == null -> 1
        registration?.teams.isNullOrEmpty() && registration?.individuals.isNullOrEmpty() &&
            registration?.unassigned.isNullOrEmpty() && loaded?.returningCandidates.isNullOrEmpty() -> 2
        contestants == 0 && guests.isEmpty() -> 3
        registration?.unassigned.isNullOrEmpty() == false -> 3 // land on the roster to place them
        else -> 4
    }

    /**
     * The furthest step the current data supports (completed steps stay clickable). The roster step
     * is reachable without any teams: adults are added there as individuals, so an adults-only
     * congregation never touches the teams step. Guests count as registered people here too — a
     * guest-only registration (e.g. all volunteers) can still be reviewed and submitted.
     */
    private fun maxReachableStep(): Int = when {
        congregation == null -> 1
        contestants == 0 && guests.isEmpty() -> 3
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
            renderSiteCard(parent, existing)
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
                lateinit var phone: HTMLInputElement
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
                    child("div") {
                        child("label", "form-label", "Phone (optional)")
                        phone = child("input", "form-control") as HTMLInputElement
                        phone.setAttribute("type", "tel")
                        phone.setAttribute("maxlength", "30")
                        phone.setAttribute("size", "14")
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
                                    phone = phone.value,
                                    code = code.value,
                                )
                            )
                            Session.api.refreshUser() // pick up the new scoped COACH grant
                            loaded = Session.api.myRegistration()
                            // On a multi-site season, stay on step 1: the site picker is here.
                            step = if (Session.season.multiSite) 1 else 2
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
                if (existing.phone.isNotBlank()) {
                    child("p", "text-muted small mb-0", existing.phone)
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
                lateinit var phone: HTMLInputElement
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
                    child("div") {
                        child("label", "form-label", "Phone (optional)")
                        phone = child("input", "form-control") as HTMLInputElement
                        phone.setAttribute("type", "tel")
                        phone.setAttribute("maxlength", "30")
                        phone.setAttribute("size", "14")
                        phone.value = existing.phone
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
                                    phone = phone.value,
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
    /**
     * Event-site picker (item F6), shown only when the season runs at two or more sites. Picking
     * saves immediately (creating the draft registration if this is the first thing the coach
     * does); a multi-site registration can't be submitted until a site is chosen.
     */
    private fun renderSiteCard(parent: Element, cong: CongregationDto) {
        val season = Session.season
        if (!season.multiSite) return
        val chosen = season.siteFor(registration?.siteId)
        parent.child("div", "card section-card mb-3") {
            child("div", "card-body") {
                child("h5", "card-title", "Event site")
                child(
                    "p", "text-muted",
                    "This season runs at ${season.sites.size} locations — choose the one your congregation attends.",
                )
                season.sites.forEach { site ->
                    child("div", "form-check") {
                        val input = child("input", "form-check-input") as HTMLInputElement
                        input.type = "radio"
                        input.name = "event-site"
                        input.id = "site-${site.id}"
                        input.checked = site.id == chosen?.id
                        input.disabled = !canEdit
                        child("label", "form-check-label") {
                            setAttribute("for", input.id)
                            append(site.name)
                            if (site.address.isNotBlank()) child("span", "text-muted", " — ${site.address}")
                        }
                        input.addEventListener("change", {
                            if (input.checked) mutate(input) { Session.api.setRegistrationSite(cong.id, site.id) }
                        })
                    }
                }
                if (chosen == null) child("div", "form-text text-warning", "Required before you can submit.")
            }
        }
    }

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
                        val btn = this
                        onClick {
                            if (team.members.isEmpty() ||
                                window.confirm(
                                    "Delete ${team.name}? Its ${team.members.size} contestant" +
                                        "${if (team.members.size == 1) "" else "s"} won't be deleted — " +
                                        "they'll move to Unassigned so you can place them on another team.",
                                )
                            ) {
                                mutate(btn) { Session.api.deleteTeam(team.id) }
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
                val addBtn = child("button", "btn btn-primary", "Add team") { setAttribute("type", "submit") }
                addEventListener("submit", { event ->
                    event.preventDefault()
                    if (name.value.isBlank()) return@addEventListener
                    mutate(addBtn) { Session.api.addTeam(cong.id, name.value) }
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
        renderAwayMembersCard(parent)
        renderReturningCard(parent)
        renderIndividualsCard(parent)
        renderGuestsCard(parent)

        registration?.let { reg ->
            parent.child("div", "card section-card mb-3") {
                child("div", "card-body py-2") {
                    child(
                        "p", "mb-0 fw-semibold",
                        "Total: ${formatCents(reg.totalCents)} (${totalBreakdown()}, t-shirts included)",
                    )
                    Session.season.feesNote.takeIf { it.isNotEmpty() }?.let { child("p", "text-muted small mb-0", it) }
                }
            }
        }
        if (contestants > 0 || guests.isNotEmpty()) parent.nextButton("Continue to review", 4)
    }

    /** "3 × $85 + 1 × $65 = $320" — one review row's fee column, tiered by age (under-3s are free). */
    private fun tierFeeMath(birthdates: List<String?>, contestant: Boolean): String {
        val season = Session.season
        val tiers = birthdates.groupingBy { season.ageTierFor(it) }.eachCount()
        val parts = AgeTier.entries.mapNotNull { tier ->
            val n = tiers[tier] ?: return@mapNotNull null
            if (tier == AgeTier.UNDER_3) "$n free" else "$n × ${formatCents(season.feeCentsFor(tier, contestant))}"
        }
        val total = tiers.entries.fold(0 as Int?) { sum, (tier, n) ->
            sum?.let { s -> season.feeCentsFor(tier, contestant)?.times(n)?.plus(s) }
        }
        return "${parts.joinToString(" + ")} = ${formatCents(total)}"
    }

    /** "2 contestants (9+) × $85 + 1 guest (3–8) × $65 + 1 guest (under 3) free" — tiers in use. */
    private fun totalBreakdown(): String {
        val reg = registration ?: return ""
        return registrationFeeLines(Session.season, reg).joinToString(" + ") { line ->
            val noun = (if (line.contestant) "contestant" else "guest") + (if (line.count == 1) "" else "s")
            val tierNote = when (line.tier) {
                AgeTier.AGE_9_PLUS -> "(9+)"
                AgeTier.AGE_3_TO_8 -> "(3\u20138)"
                AgeTier.UNDER_3 -> "(under 3)"
            }
            if (line.eachCents == 0) "${line.count} $noun $tierNote free"
            else "${line.count} $noun $tierNote \u00d7 ${formatCents(line.eachCents)}"
        }
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

    /** Members a registrar placed on another congregation's (combo) team — still edited and billed here. */
    private fun renderAwayMembersCard(parent: Element) {
        val away = registration?.awayMembers.orEmpty()
        if (away.isEmpty()) return
        parent.child("div", "card section-card border-info mb-3") {
            child("div", "card-body") {
                child("h5", "card-title") {
                    append("On combo teams ")
                    child("span", "badge text-bg-info", away.size.toString())
                }
                child("p", "text-muted small",
                    "A registrar placed these contestants on another congregation's team — they're " +
                        "still registered and paid for by your congregation. You can pull one back " +
                        "(pick Unassigned or one of your teams); only a registrar can place them on " +
                        "another congregation's team.")
                away.forEach { renderContestantRow(this, it.entry, currentTeamId = it.teamId, away = it) }
            }
        }
    }

    /**
     * Contestants who competed here before but aren't on this season's roster yet. Team assignments
     * are per-year, so a new season starts with none — these are offered for one-click enrollment
     * (which is what starts counting/billing them). Only shown when the coach can edit.
     */
    private fun renderReturningCard(parent: Element) {
        // Youth candidates go on a team here; returning adults are offered in the individuals card.
        // Workbook-seeded youth (grade but no birthdate yet) count as youth: enrolling collects it.
        val candidates = loaded?.returningCandidates.orEmpty().filter { it.birthdate != null || it.isSeededYouth }
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
                    ?: candidate.graduationYear
                        ?.let { Division.forGrade(Session.season.gradeForGraduationYear(it)) }
                child(
                    "span", "badge " + (if (division == null) "text-bg-secondary" else "text-bg-primary"),
                    division?.displayName ?: "—",
                )
                candidate.graduationYear?.takeIf { candidate.isSeededYouth }?.let {
                    child("span", "text-muted small ms-2", "~grade ${Session.season.gradeForGraduationYear(it)}")
                }
                candidate.lastSeasonYear?.let { child("span", "text-muted small ms-2", "last competed $it") }
            }
            // A workbook-seeded youth has no birthdate on file yet — collect it at first enrollment.
            val birthdate = if (candidate.isSeededYouth) {
                val input = child("input", "form-control form-control-sm w-auto") as HTMLInputElement
                input.type = "date"
                input.setAttribute("title", "Birthdate (first enrollment records it)")
                input
            } else null
            val shirt = shirtSelect(this, candidate.lastShirtSize ?: ShirtSize.YM)
            val teamSel = child("select", "form-select form-select-sm w-auto") as HTMLSelectElement
            teamSel.setAttribute("title", "Team")
            (teamSel.child("option", text = "— Unassigned —") as HTMLOptionElement).value = ""
            registration?.teams.orEmpty().forEach { team ->
                (teamSel.child("option", text = team.name) as HTMLOptionElement).value = team.id
            }
            child("button", "btn btn-sm btn-primary", "Add") {
                setAttribute("type", "button")
                val btn = this
                onClick {
                    if (birthdate != null && birthdate.value.isBlank()) {
                        error = "${candidate.name} needs a birthdate — the workbook only had a school grade"
                        renderContent()
                        return@onClick
                    }
                    mutate(btn) {
                        Session.api.enrollContestant(
                            congregationId, candidate.contestantId,
                            ShirtSize.valueOf(shirt.value), teamSel.value.ifEmpty { null },
                            birthdate?.value,
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
                        val tribe = tribeLeaderCheck(this, member.tribeLeaderWilling)
                        tribe.disabled = !canEdit
                        val save = {
                            val chosenGender = genderOf(gender)
                            if (name.value.isNotBlank() && chosenGender != null) {
                                mutate {
                                    Session.api.updateIndividual(
                                        member.id,
                                        UpsertIndividualRequest(
                                            name.value, ShirtSize.valueOf(shirt.value), chosenGender,
                                            tribeLeaderWilling = tribe.checked,
                                        ),
                                    )
                                }
                            }
                        }
                        listOf<HTMLElement>(name, shirt, gender, tribe).forEach { it.addEventListener("change", { save() }) }
                        claimCodeChip(this, member.claimCode)
                        if (canEdit) {
                            child("button", "btn btn-outline-danger btn-sm", "Remove") {
                                setAttribute("type", "button")
                                val btn = this
                                onClick { mutate(btn) { Session.api.deleteIndividual(member.id) } }
                            }
                        }
                    }
                }
                // Returning adults: competed here before but not on this year's roster — one-click add.
                val returningAdults = loaded?.returningCandidates.orEmpty()
                    .filter { it.birthdate == null && !it.isSeededYouth }
                if (canEdit && returningAdults.isNotEmpty()) {
                    child("p", "text-info small mb-1 mt-2", "Returning adults — add each to this year's roster:")
                    returningAdults.forEach { candidate ->
                        child("div", "d-flex flex-wrap align-items-center gap-2 mb-2") {
                            child("span", "flex-grow-1") {
                                append("${candidate.name} ")
                                child("span", "badge text-bg-primary", "Adult")
                                candidate.lastSeasonYear?.let { child("span", "text-muted small ms-2", "last competed $it") }
                            }
                            val shirt = shirtSelect(this, candidate.lastShirtSize ?: ShirtSize.AM)
                            child("button", "btn btn-sm btn-primary", "Add") {
                                setAttribute("type", "button")
                                val btn = this
                                onClick {
                                    mutate(btn) {
                                        Session.api.enrollContestant(cong.id, candidate.contestantId, ShirtSize.valueOf(shirt.value), null)
                                    }
                                }
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
                        val tribe = tribeLeaderCheck(this, false)
                        val addBtn = child("button", "btn btn-primary", "Add") { setAttribute("type", "submit") }
                        addEventListener("submit", { event ->
                            event.preventDefault()
                            val chosenGender = genderOf(gender)
                            if (name.value.isBlank() || chosenGender == null) return@addEventListener
                            mutate(addBtn) {
                                Session.api.addIndividual(
                                    cong.id,
                                    UpsertIndividualRequest(
                                        name.value, ShirtSize.valueOf(shirt.value), chosenGender,
                                        tribeLeaderWilling = tribe.checked,
                                    ),
                                )
                            }
                        })
                    }
                }
            }
        }
    }

    /** Guests (mostly volunteers) register and pay too, but aren't contestants and join no team. */
    private fun renderGuestsCard(parent: Element) {
        val cong = congregation ?: return
        val season = Session.season
        parent.child("div", "card section-card mb-3") {
            child("div", "card-body") {
                child("h5", "card-title", "Guests & volunteers")
                child("p", "text-muted small",
                    "Everyone attending must register and pay, including guests and volunteers — " +
                        "age 9+ ${formatCents(season.priceVolunteerCents)}, ages 3–8 " +
                        "${formatCents(season.priceChildCents)}, under 3 free. T-shirt included " +
                        "(except under-3s). Guests aren't contestants and aren't placed on a team.")
                guests.forEach { guest ->
                    child("div", "mb-2") {
                        val row = child("div", "d-flex flex-wrap align-items-center gap-2")
                        val name = row.child("input", "form-control w-auto flex-grow-1") as HTMLInputElement
                        name.value = guest.name
                        name.disabled = !canEdit
                        val gender = genderSelect(row, guest.gender)
                        val birthdate = guestBirthdateInput(row, guest.birthdate)
                        val shirt = shirtSelect(row, guest.shirtSize ?: ShirtSize.AM)
                        val hint = row.child("span", "text-muted small align-self-center")

                        // Optional contact details (item 9, F3) for adult (9+) guests, collapsed
                        // behind a toggle so the row stays tight.
                        val contactToggle = row.child(
                            "button", "btn btn-outline-secondary btn-sm",
                            if (guest.contact != null) "Contact \u2713" else "Contact",
                        ) as HTMLButtonElement
                        contactToggle.setAttribute("type", "button")
                        contactToggle.setAttribute("title", "Contact info for event organizers (optional)")
                        // Volunteer positions + tribe leading (item 8, F2): adult (age-9+ tier) guests only.
                        val (positionBoxes, tribeBox, volunteerLine) =
                            volunteerFields(this, guest.positions, guest.tribeLeaderWilling)
                        val contactPanel = child("div", "border rounded p-2 mt-1 ms-3 d-none")
                        contactToggle.onClick { contactPanel.classList.toggle("d-none") }

                        val sync = {
                            val tier = Session.season.ageTierFor(birthdate.value.ifBlank { null })
                            shirt.classList.toggle("d-none", tier == AgeTier.UNDER_3)
                            hint.textContent = tierHint(tier)
                            val nineUp = tier == AgeTier.AGE_9_PLUS
                            volunteerLine.classList.toggle("d-none", !nineUp)
                            contactToggle.classList.toggle("d-none", !nineUp)
                            if (!nineUp) contactPanel.classList.add("d-none")
                        }
                        lateinit var contactFields: GuestContactFields
                        val save = {
                            sync()
                            val chosenGender = genderOf(gender)
                            if (name.value.isNotBlank() && chosenGender != null) {
                                mutate {
                                    Session.api.updateGuest(
                                        guest.id,
                                        UpsertGuestRequest(
                                            name.value, guestShirtOf(shirt, birthdate),
                                            birthdate.value.ifBlank { null }, chosenGender,
                                            positions = positionBoxes.filter { it.second.checked }.map { it.first },
                                            tribeLeaderWilling = tribeBox.checked,
                                            contact = contactFields.value(),
                                        ),
                                    )
                                }
                            }
                        }
                        contactFields = guestContactFields(contactPanel, guest.contact, save)

                        sync()
                        val fields: List<HTMLElement> =
                            listOf(name, gender, birthdate, shirt, tribeBox) + positionBoxes.map { it.second }
                        fields.forEach { el ->
                            el.addEventListener("change", { save() })
                            (el as? HTMLSelectElement)?.disabled = !canEdit
                            (el as? HTMLInputElement)?.disabled = !canEdit
                        }
                        if (canEdit) {
                            row.child("button", "btn btn-outline-danger btn-sm", "Remove") {
                                setAttribute("type", "button")
                                val btn = this
                                onClick { mutate(btn) { Session.api.deleteGuest(guest.id) } }
                            }
                        }
                    }
                }
                if (canEdit) {
                    child("form", "d-flex flex-wrap gap-2 mt-2") {
                        val name = child("input", "form-control w-auto flex-grow-1") as HTMLInputElement
                        name.setAttribute("placeholder", "Guest or volunteer name")
                        val gender = genderSelect(this, null)
                        val birthdate = guestBirthdateInput(this, null)
                        val shirt = shirtSelect(this, ShirtSize.AM)
                        val addSlot = child("span")
                        val hint = child("span", "text-muted small align-self-center")
                        val (positionBoxes, tribeBox, volunteerLine) =
                            volunteerFields(this, emptyList(), tribeLeaderWilling = false)
                        birthdate.addEventListener("input", {
                            val tier = Session.season.ageTierFor(birthdate.value.ifBlank { null })
                            shirt.classList.toggle("d-none", tier == AgeTier.UNDER_3)
                            volunteerLine.classList.toggle("d-none", tier != AgeTier.AGE_9_PLUS)
                            hint.textContent = tierHint(tier)
                        })
                        val addBtn = addSlot.child("button", "btn btn-primary", "Add") { setAttribute("type", "submit") }
                        addEventListener("submit", { event ->
                            event.preventDefault()
                            val chosenGender = genderOf(gender)
                            if (name.value.isBlank() || chosenGender == null) return@addEventListener
                            mutate(addBtn) {
                                Session.api.addGuest(
                                    cong.id,
                                    UpsertGuestRequest(
                                        name.value, guestShirtOf(shirt, birthdate),
                                        birthdate.value.ifBlank { null }, chosenGender,
                                        positions = positionBoxes.filter { it.second.checked }.map { it.first },
                                        tribeLeaderWilling = tribeBox.checked,
                                    ),
                                )
                            }
                        })
                    }
                }
            }
        }
    }

    /** Optional birthdate for child guests — their fee tier derives from age; blank = adult (9+). */
    private fun guestBirthdateInput(parent: Element, value: String?): HTMLInputElement {
        val input = parent.child("input", "form-control w-auto") as HTMLInputElement
        input.type = "date"
        input.value = value ?: ""
        input.setAttribute(
            "title",
            "Birthdate \u2014 needed for children under 9, whose fee goes by age; leave blank for adults",
        )
        return input
    }

    /** "Age 3\u20138 \u2014 $65" / "Under 3 \u2014 free" — the live fee hint beside a guest's birthdate. */
    private fun tierHint(tier: AgeTier): String = when (tier) {
        AgeTier.AGE_9_PLUS -> "" // the default; no callout needed
        AgeTier.UNDER_3 -> "${tier.displayName} \u2014 free"
        else -> "${tier.displayName} \u2014 ${formatCents(Session.season.feeCentsFor(tier, contestant = false))}"
    }

    /** The shirt selection for a guest — always null for under-3s, whose free entry includes no shirt. */
    private fun guestShirtOf(shirt: HTMLSelectElement, birthdate: HTMLInputElement): ShirtSize? =
        if (Session.season.ageTierFor(birthdate.value.ifBlank { null }) == AgeTier.UNDER_3) null
        else ShirtSize.valueOf(shirt.value)

    /** The contact inputs of one guest's collapsible panel; [value] snapshots them as a DTO. */
    private class GuestContactFields(
        val address: HTMLInputElement,
        val city: HTMLInputElement,
        val state: HTMLInputElement,
        val zip: HTMLInputElement,
        val phone: HTMLInputElement,
        val email: HTMLInputElement,
        val preference: HTMLSelectElement,
    ) {
        /** The server collapses an all-blank DTO to "no contact info". */
        fun value(): ContactInfoDto = ContactInfoDto(
            address = address.value.trim(),
            city = city.value.trim(),
            state = state.value.trim(),
            zip = zip.value.trim(),
            phone = phone.value.trim(),
            email = email.value.trim(),
            preference = preference.value.takeIf { it.isNotEmpty() }?.let { ContactPreference.valueOf(it) },
        )
    }

    /**
     * Fills a guest's contact panel: optional address/phone/email/preference inputs (guests have no
     * account, hence the email) plus a save button invoking [save] — contact isn't autosaved per
     * keystroke because the re-render would collapse the panel between fields.
     */
    private fun guestContactFields(panel: Element, initial: ContactInfoDto?, save: () -> Unit): GuestContactFields {
        val stored = initial ?: ContactInfoDto()
        fun input(parent: Element, placeholder: String, value: String, cls: String = "") =
            (parent.child("input", "form-control form-control-sm $cls") as HTMLInputElement).apply {
                this.value = value
                this.disabled = !canEdit
                setAttribute("placeholder", placeholder)
            }
        panel.child("div", "small text-muted mb-1", "Contact info for event organizers (optional)")
        lateinit var address: HTMLInputElement
        lateinit var city: HTMLInputElement
        lateinit var state: HTMLInputElement
        lateinit var zip: HTMLInputElement
        lateinit var phone: HTMLInputElement
        lateinit var email: HTMLInputElement
        lateinit var preference: HTMLSelectElement
        panel.child("div", "d-flex flex-wrap gap-2 mb-2") {
            address = input(this, "Street address", stored.address, "w-auto flex-grow-1")
            city = input(this, "City", stored.city, "w-auto")
            state = input(this, "State", stored.state, "w-auto") .also { it.setAttribute("size", "6") }
            zip = input(this, "Zip", stored.zip, "w-auto").also { it.setAttribute("size", "8") }
        }
        panel.child("div", "d-flex flex-wrap gap-2") {
            phone = input(this, "Phone", stored.phone, "w-auto")
            email = input(this, "Email", stored.email, "w-auto flex-grow-1")
            preference = child("select", "form-select form-select-sm w-auto") as HTMLSelectElement
            preference.disabled = !canEdit
            (preference.child("option", text = "No preference") as HTMLOptionElement).value = ""
            ContactPreference.entries.forEach { pref ->
                val option = preference.child("option", text = pref.displayName) as HTMLOptionElement
                option.value = pref.name
                if (pref == stored.preference) option.selected = true
            }
            if (canEdit) {
                child("button", "btn btn-primary btn-sm", "Save contact") {
                    setAttribute("type", "button")
                    onClick { save() }
                }
            }
        }
        return GuestContactFields(address, city, state, zip, phone, email, preference)
    }

    private fun renderTeamRoster(parent: Element, team: TeamDto) {
        parent.child("div", "card section-card mb-3") {
            child("div", "card-body") {
                child("h5", "card-title") {
                    append("${team.name} ")
                    divisionBadge(this, team)
                }
                team.members.forEach { member ->
                    // Visiting (combo-team) members are edited by their own congregation's coach.
                    if (member.congregationId != null) renderVisitingMemberRow(this, member)
                    else renderContestantRow(this, member, currentTeamId = team.id)
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

    /**
     * A visiting member on one of this congregation's (combo) teams: read-only here — their own
     * congregation registers, edits, and pays for them; a registrar moves them.
     */
    private fun renderVisitingMemberRow(parent: Element, member: RosterEntryDto) {
        parent.child("div", "d-flex flex-wrap align-items-center gap-2 mb-2") {
            child("span", text = member.name)
            child("span", "badge text-bg-info", "from ${member.congregationName ?: "another congregation"}")
            val division = member.division(Session.season)?.displayName ?: "division unknown"
            child("span", "text-muted small", "$division — registered by their own congregation")
        }
    }

    /**
     * One editable youth contestant row (name/birthdate/shirt/gender/1st-year), used under a team,
     * in the Unassigned card, and in the On-combo-teams card. [currentTeamId] is the team the row
     * belongs to (null when unassigned); the team dropdown moves the contestant between teams or
     * off to the pool. [away] labels the current (other-congregation) team when the member is on a
     * combo team.
     */
    private fun renderContestantRow(
        parent: Element,
        member: RosterEntryDto,
        currentTeamId: String?,
        away: AwayMemberDto? = null,
    ) {
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
            teamAssignSelect(this, member, currentTeamId, away)
            claimCodeChip(this, member.claimCode)
            if (canEdit) {
                child("button", "btn btn-outline-danger btn-sm", "Remove") {
                    setAttribute("type", "button")
                    val btn = this
                    onClick { mutate(btn) { Session.api.deleteRosterEntry(member.id) } }
                }
            }
        }
    }

    /**
     * The per-contestant team picker: each of this congregation's teams plus an "Unassigned"
     * option. Changing it moves the contestant (server enforces the 4-cap). Rendered only when
     * there's somewhere to move to — at least one team, or the contestant is currently on one.
     * For a member on a combo team, [away] adds that (other-congregation) team as the selected
     * option, so the coach can pull the member back but not push anyone across (registrar-only).
     */
    private fun teamAssignSelect(
        parent: Element,
        member: RosterEntryDto,
        currentTeamId: String?,
        away: AwayMemberDto? = null,
    ) {
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
        if (away != null) {
            val option = select.child(
                "option", text = "${away.teamName} (${away.congregationName})",
            ) as HTMLOptionElement
            option.value = away.teamId
            option.selected = true
        }
        select.addEventListener("change", {
            mutate(select) { Session.api.assignMemberTeam(member.id, select.value.ifEmpty { null }) }
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
            val addBtn = child("button", "btn btn-primary", "Add") { setAttribute("type", "submit") }
            addEventListener("submit", { event ->
                event.preventDefault()
                val chosenGender = genderOf(gender)
                if (name.value.isBlank() || !isValidBirthdate(birthdate.value) || chosenGender == null) return@addEventListener
                mutate(addBtn) {
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

    private var volunteerSeq = 0

    /** "Tribe leader?" checkbox — willingness to serve as a tribe leader (any adult can). */
    private fun tribeLeaderCheck(parent: Element, checked: Boolean): HTMLInputElement {
        lateinit var box: HTMLInputElement
        parent.child("div", "form-check align-self-center text-nowrap") {
            setAttribute("title", "Willing to serve as a tribe leader")
            box = child("input", "form-check-input") as HTMLInputElement
            box.type = "checkbox"
            box.checked = checked
            box.id = "tribe-leader-${++volunteerSeq}"
            child("label", "form-check-label", "Tribe leader?") { setAttribute("for", box.id) }
        }
        return box
    }

    /**
     * The volunteer questions for an adult (age-9+) guest: one checkbox per season-configured
     * position plus the tribe-leader checkbox, on a full-width line under the guest's fields.
     * Returns the position boxes (paired with their position label), the tribe box, and the
     * container — callers toggle the container with the age tier (children don't volunteer).
     */
    private fun volunteerFields(
        parent: Element,
        positions: List<String>,
        tribeLeaderWilling: Boolean,
    ): Triple<List<Pair<String, HTMLInputElement>>, HTMLInputElement, HTMLElement> {
        lateinit var boxes: List<Pair<String, HTMLInputElement>>
        lateinit var tribe: HTMLInputElement
        val container = parent.child("div", "w-100 d-flex flex-wrap align-items-center gap-3 small") {
            child("span", "text-muted", "Volunteer:")
            boxes = Session.season.volunteerPositions.map { position ->
                lateinit var box: HTMLInputElement
                child("div", "form-check form-check-inline m-0 text-nowrap") {
                    box = child("input", "form-check-input") as HTMLInputElement
                    box.type = "checkbox"
                    box.checked = position in positions
                    box.id = "volunteer-${++volunteerSeq}"
                    child("label", "form-check-label", position) { setAttribute("for", box.id) }
                }
                position to box
            }
            tribe = tribeLeaderCheck(this, tribeLeaderWilling)
        } as HTMLElement
        return Triple(boxes, tribe, container)
    }

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
                Session.season.siteFor(reg.siteId)?.let { site ->
                    child("p", "text-muted mb-2", "Event site: ${site.name}")
                }
                child("table", "table mb-2") {
                    child("thead") {
                        child("tr") {
                            listOf("Team", "Division", "Contestants", "Fees").forEach { child("th", text = it) }
                        }
                    }
                    child("tbody") {
                        reg.teams.forEach { team ->
                            // Visiting (combo-team) members are named but their own congregation
                            // pays for them, so only home members enter this row's fee math.
                            val homeMembers = team.members.count { it.congregationId == null }
                            child("tr") {
                                child("td", text = team.name)
                                child("td", text = team.division(Session.season)
                                    ?.let { divisionLabel(it, team.isInexperienced(seasonYear)) } ?: "—")
                                child("td", text = team.members.joinToString {
                                    it.name + (if (it.isInexperienced(seasonYear)) " (1st year)" else "") +
                                        (it.congregationName?.let { c -> " (from $c)" } ?: "")
                                })
                                child("td", "text-nowrap",
                                    tierFeeMath(team.members.filter { it.congregationId == null }.map { it.birthdate },
                                        contestant = true))
                            }
                        }
                        if (reg.unassigned.isNotEmpty()) {
                            child("tr", "table-warning") {
                                child("td", text = "Unassigned")
                                child("td", text = "—")
                                child("td", text = reg.unassigned.joinToString { it.name })
                                child("td", "text-nowrap", tierFeeMath(reg.unassigned.map { it.birthdate }, contestant = true))
                            }
                        }
                        if (reg.awayMembers.isNotEmpty()) {
                            child("tr") {
                                child("td", text = "On combo teams")
                                child("td", text = "—")
                                child("td", text = reg.awayMembers.joinToString {
                                    "${it.entry.name} (${it.teamName}, ${it.congregationName})"
                                })
                                child("td", "text-nowrap",
                                    tierFeeMath(reg.awayMembers.map { it.entry.birthdate }, contestant = true))
                            }
                        }
                        if (reg.individuals.isNotEmpty()) {
                            child("tr") {
                                child("td", text = "Individuals")
                                child("td", text = "Adult")
                                child("td", text = reg.individuals.joinToString { it.name })
                                child("td", "text-nowrap", tierFeeMath(reg.individuals.map { it.birthdate }, contestant = true))
                            }
                        }
                        if (reg.guests.isNotEmpty()) {
                            child("tr") {
                                child("td", text = "Guests")
                                child("td", text = "—")
                                child("td", text = reg.guests.joinToString {
                                    it.name + when (Session.season.ageTierFor(it.birthdate)) {
                                        AgeTier.AGE_9_PLUS -> ""
                                        AgeTier.AGE_3_TO_8 -> " (3\u20138)"
                                        AgeTier.UNDER_3 -> " (under 3)"
                                    }
                                })
                                child("td", "text-nowrap", tierFeeMath(reg.guests.map { it.birthdate }, contestant = false))
                            }
                        }
                    }
                    child("tfoot") {
                        child("tr", "fw-semibold") {
                            val guestPart = reg.guests.size
                                .takeIf { it > 0 }?.let { " + $it guest${if (it == 1) "" else "s"}" } ?: ""
                            child("td", text = "Total due ($contestants contestant${if (contestants == 1) "" else "s"}$guestPart)") {
                                setAttribute("colspan", "3")
                            }
                            child("td", "text-nowrap", formatCents(reg.totalCents))
                        }
                    }
                }
                child(
                    "p", "text-muted small mb-1",
                    "Everyone pays by age: 9+ ${formatCents(Session.season.priceContestantCents)} " +
                        "(volunteers ${formatCents(Session.season.priceVolunteerCents)}), ages 3\u20138 " +
                        "${formatCents(Session.season.priceChildCents)}, under 3 free. One t-shirt " +
                        "included for everyone but under-3s; extra shirts are paid at the door.",
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
        val siteMissing = Session.season.multiSite && Session.season.siteFor(reg.siteId) == null
        if (canEdit && siteMissing) {
            parent.child(
                "div", "alert alert-warning",
                "Choose your event site on the congregation step before submitting.",
            )
        }
        if (canEdit) {
            val label = if (reg.status == RegistrationStatus.SUBMITTED) "Update registration" else "Submit registration"
            parent.child("button", "btn btn-primary btn-lg", label) {
                setAttribute("type", "button")
                val btn = this
                onClick {
                    if (siteMissing) {
                        step = 1
                        error = null
                        renderContent()
                        return@onClick
                    }
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
                    mutate(btn) { Session.api.submitRegistration(cong.id) }
                }
            }
        }
    }

    // --- shared -----------------------------------------------------------------------------

    /**
     * Runs a mutation, adopts the returned registration + returning candidates, and re-renders
     * (API errors show inline). Every mutation response carries the recomputed candidate list, so
     * one round trip keeps the whole roster step in sync — enrolling consumes a candidate, and
     * removing a prior-season contestant re-offers them. [trigger] (the clicked button or changed
     * control) is disabled immediately — with a spinner on buttons — until the re-render.
     */
    private fun mutate(trigger: HTMLElement? = null, call: suspend () -> RegistrationUpdateResponse) {
        trigger?.showBusy()
        Shell.scope.launch {
            try {
                val updated = call()
                loaded = loaded?.copy(
                    registration = updated.registration,
                    returningCandidates = updated.returningCandidates,
                )
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
