package net.markdrew.biblebowl.web.screens

import kotlinx.browser.document
import kotlinx.coroutines.launch
import net.markdrew.biblebowl.api.RegistrationDeskResponse
import net.markdrew.biblebowl.api.RegistrationDeskRowDto
import net.markdrew.biblebowl.api.RegistrationDto
import net.markdrew.biblebowl.api.RegistrationStatus
import net.markdrew.biblebowl.api.ReturningContestantDto
import net.markdrew.biblebowl.api.Role
import net.markdrew.biblebowl.api.ScopeType
import net.markdrew.biblebowl.api.ShirtSize
import net.markdrew.biblebowl.api.TeamDto
import net.markdrew.biblebowl.api.UpdateCongregationRequest
import net.markdrew.biblebowl.api.contestantCount
import net.markdrew.biblebowl.api.division
import net.markdrew.biblebowl.api.divisionForBirthdate
import net.markdrew.biblebowl.api.formatCents
import net.markdrew.biblebowl.web.Session
import net.markdrew.biblebowl.web.Shell
import net.markdrew.biblebowl.web.child
import net.markdrew.biblebowl.web.clear
import net.markdrew.biblebowl.web.errorLine
import net.markdrew.biblebowl.web.onClick
import net.markdrew.biblebowl.web.spinner
import org.w3c.dom.Element
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLOptionElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.url.URL
import org.w3c.files.Blob
import org.w3c.files.BlobPropertyBag

/**
 * Registration desk (docs/gui-redesign.md §5E): every congregation's current-season registration —
 * status, counts, totals, coach contacts, roster detail on click, a payment-received toggle, and a
 * CSV export. Route-gated (and server-enforced) on an *event-wide* REGISTRATION_MANAGE grant;
 * check-in and contestant codes arrive with the event-ops slice. Claim codes are deliberately not
 * shown — they're the secret a contestant uses to claim their entry.
 */
object AdminRegistrationsScreen {

    private var data: RegistrationDeskResponse? = null
    private val expanded = mutableSetOf<String>() // congregation ids with the roster detail open
    private var message: String? = null
    private lateinit var content: HTMLElement

    /** Only a globally-scoped admin may change a congregation's two-letter code once it's set. */
    private val isAdmin: Boolean
        get() = Session.user?.roles?.any { it.role == Role.ADMIN && it.scopeType == ScopeType.GLOBAL } == true

    fun render(container: HTMLElement) {
        container.child("h1", "page-title", "Registration desk")
        content = container.child("div")
        content.spinner()
        data = null
        message = null
        expanded.clear()
        Shell.scope.launch {
            try {
                data = Session.api.registrationDesk()
                renderContent()
            } catch (e: Throwable) {
                content.clear()
                content.errorLine("Could not load the registration desk: ${e.message}")
            }
        }
    }

    private fun renderContent() {
        val desk = data ?: return
        content.clear()
        content.child("div", "d-flex flex-wrap align-items-center gap-3 mb-3") {
            child("span", "text-muted", "Season ${desk.seasonYear} — click a row for its roster.")
            child("button", "btn btn-outline-primary btn-sm", "Download CSV") {
                setAttribute("type", "button")
                onClick { downloadCsv("tbb-registrations-${desk.seasonYear}.csv", deskCsv(desk)) }
            }
        }
        message?.let { content.errorLine(it) }

        if (desk.rows.isEmpty()) {
            content.child("p", "text-muted", "No congregations yet.")
            return
        }

        content.child("div", "table-responsive") {
            child("table", "table table-hover align-middle") {
                child("thead") {
                    child("tr") {
                        listOf(
                            "Congregation", "Code", "Status", "Teams", "Contestants", "Individuals",
                            "Total due", "Submitted", "Paid", "Coaches",
                        ).forEach { child("th", text = it) }
                    }
                }
                val tbody = child("tbody")
                desk.rows.forEach { row -> renderRow(tbody, row) }
            }
        }

        renderSummary(content, desk)
    }

    private fun renderRow(tbody: Element, row: RegistrationDeskRowDto) {
        val reg = row.registration
        val tr = tbody.child("tr") {
            child("td") {
                child("div", "fw-semibold", row.congregation.name)
                val cityState =
                    if (row.congregation.state.isBlank()) row.congregation.city
                    else "${row.congregation.city}, ${row.congregation.state}"
                child("div", "text-muted small", cityState)
            }
            child("td") { renderCodeCell(this, row) }
            child("td") {
                statusBadge(this, reg?.status)
                val unassigned = reg?.unassigned?.size ?: 0
                if (unassigned > 0) child("span", "badge text-bg-warning ms-1", "$unassigned unassigned")
                if (row.returningCandidates.isNotEmpty())
                    child("span", "badge text-bg-info ms-1", "${row.returningCandidates.size} returning")
            }
            child("td", text = reg?.teams?.size?.toString() ?: "—")
            child("td", text = reg?.contestantCount?.toString() ?: "—")
            child("td", text = reg?.individuals?.size?.toString() ?: "—")
            child("td", text = reg?.let { formatCents(it.totalCents) } ?: "—")
            child("td", text = reg?.submittedAt?.take(10) ?: "—")
            val paidCell = child("td")
            if (reg != null) renderPaidSwitch(paidCell, reg.id, reg.paidAt)
            child("td") {
                if (row.coaches.isEmpty()) child("span", "text-muted", "—")
                row.coaches.forEach { coach ->
                    child("div", "small") {
                        child("a", text = coach.displayName) {
                            setAttribute("href", "mailto:${coach.email}")
                            setAttribute("title", coach.email)
                        }
                    }
                }
            }
        }
        tr.style.cursor = "pointer"
        tr.addEventListener("click", { event ->
            // The paid switch and mailto links handle their own clicks.
            val target = event.target as? Element
            if (target?.closest("input,a,button") != null) return@addEventListener
            if (!expanded.remove(row.congregation.id)) expanded.add(row.congregation.id)
            renderContent()
        })
        if (row.congregation.id in expanded) {
            tbody.child("tr") {
                child("td", "bg-light") {
                    setAttribute("colspan", "10")
                    renderDetail(this, row)
                }
            }
        }
    }

    /**
     * The two-letter code cell: an inline editor for admins (the only ones allowed to change a set
     * code), plain text otherwise. Saving reuses `PUT /congregations/{id}`, resending the row's other
     * fields unchanged.
     */
    private fun renderCodeCell(cell: Element, row: RegistrationDeskRowDto) {
        val cong = row.congregation
        if (!isAdmin) {
            if (cong.code.isBlank()) cell.child("span", "text-muted", "—")
            else cell.child("span", "badge text-bg-secondary", cong.code)
            return
        }
        cell.child("div", "d-flex align-items-center gap-1") {
            val input = child("input", "form-control form-control-sm w-auto font-monospace") as HTMLInputElement
            input.value = cong.code
            input.setAttribute("maxlength", "2")
            input.setAttribute("size", "3")
            input.setAttribute("title", "Two-letter congregation code")
            input.style.textTransform = "uppercase"
            input.addEventListener("change", {
                val next = input.value.trim().uppercase()
                if (next == cong.code) return@addEventListener
                input.disabled = true
                message = null
                Shell.scope.launch {
                    try {
                        val updated = Session.api.updateCongregation(
                            cong.id,
                            UpdateCongregationRequest(
                                name = cong.name, city = cong.city, state = cong.state,
                                mailingAddress = cong.mailingAddress, zip = cong.zip, code = next,
                            ),
                        )
                        data = data?.let { desk ->
                            desk.copy(rows = desk.rows.map { r ->
                                if (r.congregation.id == cong.id) r.copy(congregation = updated) else r
                            })
                        }
                    } catch (e: Throwable) {
                        message = "Could not update code: ${e.message}"
                    }
                    renderContent()
                }
            })
        }
    }

    private fun statusBadge(parent: Element, status: RegistrationStatus?) {
        when (status) {
            RegistrationStatus.SUBMITTED -> parent.child("span", "badge text-bg-success", "Submitted")
            RegistrationStatus.DRAFT -> parent.child("span", "badge text-bg-secondary", "Draft")
            null -> parent.child("span", "text-muted", "—")
        }
    }

    private fun renderPaidSwitch(cell: Element, registrationId: String, paidAt: String?) {
        cell.child("div", "form-check form-switch mb-0") {
            val input = child("input", "form-check-input") as HTMLInputElement
            input.type = "checkbox"
            input.checked = paidAt != null
            input.setAttribute("title", "Payment received")
            input.addEventListener("change", {
                input.disabled = true
                message = null
                Shell.scope.launch {
                    try {
                        val updated = Session.api.setRegistrationPaid(registrationId, input.checked)
                        data = data?.let { desk ->
                            desk.copy(rows = desk.rows.map { row ->
                                if (row.registration?.id == registrationId) row.copy(registration = updated) else row
                            })
                        }
                    } catch (e: Throwable) {
                        message = "Could not update payment: ${e.message}"
                    }
                    renderContent()
                }
            })
            paidAt?.let { child("span", "text-muted small ms-1", it.take(10)) }
        }
    }

    private fun renderDetail(parent: Element, row: RegistrationDeskRowDto) {
        val reg = row.registration
        if (reg != null) {
            reg.teams.forEach { team -> renderTeamDetail(parent, team) }
            renderUnassignedAdmin(parent, row.congregation.id, reg)
            if (reg.individuals.isNotEmpty()) {
                parent.child("h6", "mt-2", "Individual contestants (adults)")
                reg.individuals.forEach { entry ->
                    parent.child("div", "small", "${entry.name} — shirt ${entry.shirtSize.name}")
                }
            }
        }
        renderReturningCandidatesAdmin(parent, row)
        val rosterEmpty = reg == null || (reg.teams.isEmpty() && reg.individuals.isEmpty() && reg.unassigned.isEmpty())
        if (rosterEmpty && row.returningCandidates.isEmpty()) {
            parent.child("p", "text-muted mb-0",
                if (reg == null) "No registration started this season." else "Nothing on the roster yet.")
        }
    }

    /**
     * Prior-year contestants still eligible but not on this season's roster. A registrar may enroll
     * each here (youth onto a team or the unassigned pool, adults as individuals) — the same
     * enroll endpoint the coach uses, accepted for an event-wide grant. Enrolling creates the
     * registration if it doesn't exist yet.
     */
    private fun renderReturningCandidatesAdmin(parent: Element, row: RegistrationDeskRowDto) {
        val candidates = row.returningCandidates
        if (candidates.isEmpty()) return
        val teams = row.registration?.teams.orEmpty()
        parent.child("h6", "mt-3") {
            append("Returning contestants ")
            child("span", "badge text-bg-info", candidates.size.toString())
        }
        candidates.forEach { candidate ->
            parent.child("div", "d-flex flex-wrap align-items-center gap-2 small mb-1") {
                val isAdult = candidate.birthdate == null
                val div = if (isAdult) "Adult"
                    else candidate.birthdate?.let { Session.season.divisionForBirthdate(it)?.displayName } ?: "—"
                child("span", text = "${candidate.name} — $div" + (candidate.lastSeasonYear?.let { " · last $it" } ?: ""))
                val shirt = adminShirtSelect(this, candidate.lastShirtSize)
                // Youth pick a team (or the unassigned pool); adults enroll as individuals (no team).
                val teamSel = if (!isAdult && teams.isNotEmpty()) {
                    val sel = child("select", "form-select form-select-sm w-auto") as HTMLSelectElement
                    (sel.child("option", text = "— Unassigned —") as HTMLOptionElement).value = ""
                    teams.forEach { (sel.child("option", text = it.name) as HTMLOptionElement).value = it.id }
                    sel
                } else null
                child("button", "btn btn-sm btn-primary", "Add") {
                    setAttribute("type", "button")
                    onClick {
                        deskEnroll(row.congregation.id, candidate.contestantId) {
                            Session.api.enrollContestant(
                                row.congregation.id, candidate.contestantId,
                                ShirtSize.valueOf(shirt.value), teamSel?.value?.ifEmpty { null },
                            )
                        }
                    }
                }
            }
        }
    }

    private fun adminShirtSelect(parent: Element, selected: ShirtSize?): HTMLSelectElement {
        val select = parent.child("select", "form-select form-select-sm w-auto") as HTMLSelectElement
        ShirtSize.entries.forEach { size ->
            val option = select.child("option", text = size.displayName) as HTMLOptionElement
            option.value = size.name
            if (size == selected) option.selected = true
        }
        return select
    }

    /** Enrolls a returning candidate and drops it from the row's candidate list (roster refreshes too). */
    private fun deskEnroll(congregationId: String, contestantId: String, call: suspend () -> RegistrationDto) {
        message = null
        Shell.scope.launch {
            try {
                val updated = call()
                data = data?.let { desk ->
                    desk.copy(rows = desk.rows.map { row ->
                        if (row.congregation.id == congregationId) row.copy(
                            registration = updated,
                            returningCandidates = row.returningCandidates.filterNot { it.contestantId == contestantId },
                        ) else row
                    })
                }
            } catch (e: Throwable) {
                message = "Could not enroll: ${e.message}"
            }
            renderContent()
        }
    }

    /**
     * Contestants a coach left unassigned — the registrar places each on a team here (or adds a team
     * first when none exist). Uses the same endpoints as the coach flow; an event-wide grant is
     * accepted server-side and isn't window-gated.
     */
    private fun renderUnassignedAdmin(parent: Element, congregationId: String, reg: RegistrationDto) {
        if (reg.unassigned.isEmpty()) return
        parent.child("h6", "mt-3") {
            append("Unassigned contestants ")
            child("span", "badge text-bg-warning", reg.unassigned.size.toString())
        }
        reg.unassigned.forEach { member ->
            parent.child("div", "d-flex flex-wrap align-items-center gap-2 small mb-1") {
                val div = member.division(Session.season)?.displayName ?: "division unknown"
                child("span", text = "${member.name} — $div, shirt ${member.shirtSize.name}")
                if (reg.teams.isEmpty()) {
                    child("span", "text-muted", "add a team below to place them")
                } else {
                    val select = child("select", "form-select form-select-sm w-auto") as HTMLSelectElement
                    val placeholder = select.child("option", text = "Assign to team…") as HTMLOptionElement
                    placeholder.value = ""
                    placeholder.disabled = true
                    placeholder.selected = true
                    reg.teams.forEach { team ->
                        (select.child("option", text = team.name) as HTMLOptionElement).value = team.id
                    }
                    select.addEventListener("change", {
                        val teamId = select.value
                        if (teamId.isNotEmpty()) {
                            deskMutate(congregationId) { Session.api.assignMemberTeam(member.id, teamId) }
                        }
                    })
                }
            }
        }
        // A registrar can create a team to place these on (e.g. the coach deleted all their teams).
        parent.child("form", "d-flex gap-2 mt-2 mb-1") {
            val name = child("input", "form-control form-control-sm w-auto") as HTMLInputElement
            name.setAttribute("placeholder", "New team name")
            child("button", "btn btn-sm btn-outline-primary", "Add team") { setAttribute("type", "submit") }
            addEventListener("submit", { event ->
                event.preventDefault()
                if (name.value.isNotBlank()) {
                    deskMutate(congregationId) { Session.api.addTeam(congregationId, name.value) }
                }
            })
        }
    }

    /** Runs a desk-side registration mutation and swaps the refreshed registration into its row. */
    private fun deskMutate(congregationId: String, call: suspend () -> RegistrationDto) {
        message = null
        Shell.scope.launch {
            try {
                val updated = call()
                data = data?.let { desk ->
                    desk.copy(rows = desk.rows.map { row ->
                        if (row.congregation.id == congregationId) row.copy(registration = updated) else row
                    })
                }
            } catch (e: Throwable) {
                message = "Could not update the roster: ${e.message}"
            }
            renderContent()
        }
    }

    private fun renderTeamDetail(parent: Element, team: TeamDto) {
        parent.child("h6", "mt-2") {
            append("${team.name} ")
            val division = team.division(Session.season)
            child(
                "span",
                "badge " + (if (division == null) "text-bg-secondary" else "text-bg-primary"),
                division?.displayName ?: "Empty",
            )
        }
        if (team.members.isEmpty()) {
            parent.child("p", "text-muted small mb-1", "No members yet.")
            return
        }
        team.members.forEach { member ->
            val division = member.division(Session.season)?.displayName ?: "division unknown"
            parent.child("div", "small", "${member.name} — $division, shirt ${member.shirtSize.name}")
        }
    }

    private fun renderSummary(parent: Element, desk: RegistrationDeskResponse) {
        val regs = desk.rows.mapNotNull { it.registration }
        val dueCents = regs.mapNotNull { it.totalCents }.sum()
        parent.child("p", "text-muted") {
            append(
                "${regs.size} of ${desk.rows.size} congregations registered · " +
                    "${regs.sumOf { it.contestantCount }} contestants · " +
                    "${formatCents(dueCents)} total due · " +
                    "${regs.count { it.paidAt != null }} paid",
            )
        }
    }

    // --- CSV export -------------------------------------------------------------------------

    private fun deskCsv(desk: RegistrationDeskResponse): String {
        val header = listOf(
            "Congregation", "Code", "City", "State", "Status", "Teams", "Contestants", "Individuals",
            "Total Due", "Submitted", "Paid", "Coach Names", "Coach Emails",
        )
        val rows = desk.rows.map { row ->
            val reg = row.registration
            listOf(
                row.congregation.name,
                row.congregation.code,
                row.congregation.city,
                row.congregation.state,
                reg?.status?.name ?: "NONE",
                reg?.teams?.size?.toString() ?: "",
                reg?.contestantCount?.toString() ?: "",
                reg?.individuals?.size?.toString() ?: "",
                reg?.totalCents?.let { formatCents(it) } ?: "",
                reg?.submittedAt?.take(10) ?: "",
                reg?.paidAt?.take(10) ?: "",
                row.coaches.joinToString("; ") { it.displayName },
                row.coaches.joinToString("; ") { it.email },
            )
        }
        return (listOf(header) + rows).joinToString("\r\n") { line -> line.joinToString(",") { csvField(it) } }
    }

    /** Quote-escapes a CSV field, guarding user-entered text against spreadsheet formula injection. */
    private fun csvField(raw: String): String {
        val guarded = if (raw.firstOrNull() in listOf('=', '+', '-', '@')) "'$raw" else raw
        return "\"" + guarded.replace("\"", "\"\"") + "\""
    }

    private fun downloadCsv(fileName: String, csv: String) {
        // BOM so Excel detects UTF-8.
        val blob = Blob(arrayOf<dynamic>("\uFEFF$csv"), BlobPropertyBag(type = "text/csv;charset=utf-8"))
        val url = URL.createObjectURL(blob)
        val a = document.createElement("a") as HTMLAnchorElement
        a.href = url
        a.download = fileName
        document.body?.appendChild(a)
        a.click()
        a.remove()
        URL.revokeObjectURL(url)
    }
}
