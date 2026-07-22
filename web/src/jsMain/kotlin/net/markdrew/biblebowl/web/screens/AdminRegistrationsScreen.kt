package net.markdrew.biblebowl.web.screens

import kotlinx.coroutines.launch
import net.markdrew.biblebowl.api.ContactInfoDto
import net.markdrew.biblebowl.api.RegistrationDeskResponse
import net.markdrew.biblebowl.api.RegistrationDeskRowDto
import net.markdrew.biblebowl.api.RegistrationDto
import net.markdrew.biblebowl.api.PersonWithParticipationsDto
import net.markdrew.biblebowl.api.RegistrationStatus
import net.markdrew.biblebowl.api.RegistrationUpdateResponse
import net.markdrew.biblebowl.api.ReturningContestantDto
import net.markdrew.biblebowl.api.Role
import net.markdrew.biblebowl.api.ScopeType
import net.markdrew.biblebowl.api.ShirtSize
import net.markdrew.biblebowl.api.TeamDto
import net.markdrew.biblebowl.api.UpdateCongregationRequest
import net.markdrew.biblebowl.api.contestantCount
import net.markdrew.biblebowl.api.division
import net.markdrew.biblebowl.api.Division
import net.markdrew.biblebowl.api.divisionForBirthdate
import net.markdrew.biblebowl.api.gradeForGraduationYear
import net.markdrew.biblebowl.api.isSeededYouth
import net.markdrew.biblebowl.api.formatCents
import net.markdrew.biblebowl.api.ageTierFor
import net.markdrew.biblebowl.api.multiSite
import net.markdrew.biblebowl.api.shirtSizes
import net.markdrew.biblebowl.api.siteFor
import net.markdrew.biblebowl.web.Routes
import net.markdrew.biblebowl.web.Session
import net.markdrew.biblebowl.web.Shell
import net.markdrew.biblebowl.web.child
import net.markdrew.biblebowl.web.clear
import net.markdrew.biblebowl.web.csvText
import net.markdrew.biblebowl.web.downloadBlob
import net.markdrew.biblebowl.web.downloadCsv
import net.markdrew.biblebowl.web.errorLine
import net.markdrew.biblebowl.web.onClick
import net.markdrew.biblebowl.web.spinner
import org.w3c.dom.Element
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLOptionElement
import org.w3c.dom.HTMLSelectElement
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
    private var siteFilter: String? = null // EventSiteDto id, or null = all sites (multi-site seasons)
    private var year: String? = null // season year under review, or null = the current season
    private lateinit var content: HTMLElement

    /** True when reviewing a past season's data — everything renders read-only. */
    private val viewingPast: Boolean
        get() = data?.let { it.seasonYear != Session.season.eventYear.toString() } == true

    /**
     * Multi-site seasons add a Site column, a site filter, and per-row site editing. Suppressed for
     * a past year: site ids only resolve against the *current* season's site list.
     */
    private val multiSite: Boolean get() = Session.season.multiSite && !viewingPast

    /** Only a globally-scoped admin may change a congregation's two-letter code once it's set. */
    private val isAdmin: Boolean
        get() = Session.user?.roles?.any { it.role == Role.ADMIN && it.scopeType == ScopeType.GLOBAL } == true

    fun render(container: HTMLElement) {
        container.child("h1", "page-title", "Registration desk")
        content = container.child("div")
        data = null
        year = null
        reload()
    }

    /** (Re)fetches the desk for [year] (null = current season) and re-renders. */
    private fun reload() {
        content.clear()
        content.spinner()
        message = null
        siteFilter = null
        expanded.clear()
        Shell.scope.launch {
            try {
                data = Session.api.registrationDesk(year)
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
            yearSelect(this, desk)
            child("span", "text-muted", "— click a row for its roster.")
            if (viewingPast) child("span", "badge text-bg-secondary", "past season — read-only")
            if (multiSite) siteFilterSelect(this)
            child("a", "btn btn-outline-secondary btn-sm", "Counts dashboard") {
                setAttribute("href", "#${Routes.ADMIN_COUNTS}")
            }
            child("a", "btn btn-outline-secondary btn-sm", "Housing") {
                setAttribute("href", "#${Routes.ADMIN_HOUSING}")
            }
            child("button", "btn btn-outline-primary btn-sm", "Download CSV") {
                setAttribute("type", "button")
                onClick { downloadCsv("tbb-registrations-${desk.seasonYear}.csv", deskCsv(desk)) }
            }
            // Nametags are a current-event artifact — the endpoint always generates for the
            // current season, so the button hides in a past-year review.
            if (!viewingPast) child("button", "btn btn-outline-primary btn-sm", "Nametags PDF") {
                setAttribute("type", "button")
                setAttribute("title", "Printable per-site nametags; assigns tester IDs on first run")
                onClick { downloadNametags(this, desk.seasonYear) }
            }
        }
        message?.let { content.errorLine(it) }

        if (desk.rows.isEmpty()) {
            content.child("p", "text-muted", "No congregations yet.")
            return
        }

        // Everything below (the table and the summary) respects the site filter, so a desk worker
        // gets per-site counts and totals just by picking a site.
        val rows = desk.rows.filter { row ->
            siteFilter == null || Session.season.siteFor(row.registration?.siteId)?.id == siteFilter
        }
        if (rows.isEmpty()) {
            content.child("p", "text-muted", "No congregations at this site yet.")
            return
        }

        content.child("div", "table-responsive") {
            child("table", "table table-hover align-middle") {
                child("thead") {
                    child("tr") {
                        columnHeaders().forEach { child("th", text = it) }
                    }
                }
                val tbody = child("tbody")
                rows.forEach { row -> renderRow(tbody, row) }
            }
        }

        renderSummary(content, desk.copy(rows = rows))
        renderVolunteers(content, rows)
        renderShirtOrder(content, rows)
    }

    /** A volunteer or willing tribe leader, labeled with their congregation. */
    private data class Volunteer(val name: String, val congregation: String)

    /**
     * The volunteers roll-up (replaces the workbook's Volunteers tab): every adult guest's
     * positions across the listed congregations grouped by position, plus the willing tribe
     * leaders — adult guests and individual (adult) contestants alike. [rows] is already
     * site-filtered, so picking a site gives the per-site grouping event ops works from.
     */
    private fun renderVolunteers(parent: Element, rows: List<RegistrationDeskRowDto>) {
        val byPosition = linkedMapOf<String, MutableList<Volunteer>>()
        Session.season.volunteerPositions.forEach { byPosition[it] = mutableListOf() }
        val tribeLeaders = mutableListOf<Volunteer>()
        rows.forEach { row ->
            val reg = row.registration ?: return@forEach
            reg.guests.forEach { guest ->
                guest.positions.forEach { position ->
                    // getOrPut keeps volunteers whose position was later removed from the season list.
                    byPosition.getOrPut(position) { mutableListOf() } += Volunteer(guest.name, row.congregation.name)
                }
                if (guest.tribeLeaderWilling) tribeLeaders += Volunteer(guest.name, row.congregation.name)
            }
            reg.individuals.filter { it.tribeLeaderWilling }
                .forEach { tribeLeaders += Volunteer(it.name, row.congregation.name) }
        }
        if (byPosition.values.all { it.isEmpty() } && tribeLeaders.isEmpty()) return

        parent.child("h4", "mt-4", "Volunteers")
        byPosition.filterValues { it.isNotEmpty() }.forEach { (position, volunteers) ->
            parent.child("h6", "mt-2") {
                append("$position ")
                child("span", "badge text-bg-secondary", volunteers.size.toString())
            }
            volunteers.sortedBy { it.name.lowercase() }.forEach {
                parent.child("div", "small", "${it.name} — ${it.congregation}")
            }
        }
        if (tribeLeaders.isNotEmpty()) {
            parent.child("h6", "mt-2") {
                append("Willing tribe leaders ")
                child("span", "badge text-bg-success", tribeLeaders.size.toString())
            }
            tribeLeaders.sortedBy { it.name.lowercase() }.forEach {
                parent.child("div", "small", "${it.name} — ${it.congregation}")
            }
        }
    }

    /**
     * The shirt-order matrix (replaces the workbook's `Congregations` shirt-order tab): size counts
     * per congregation with grand totals by size, over every attendee whose fee includes a shirt
     * (see [shirtSizes] — under-3 guests get none, and a combo-team member counts under their own
     * congregation). [rows] is already site-filtered, so picking a site yields the per-site order
     * that actually goes to the vendor; the CSV downloads the same matrix.
     */
    private fun renderShirtOrder(parent: Element, rows: List<RegistrationDeskRowDto>) {
        val counts: List<Pair<String, Map<ShirtSize, Int>>> = rows.mapNotNull { row ->
            val sizes = row.registration?.shirtSizes.orEmpty()
            if (sizes.isEmpty()) null else row.congregation.name to sizes.groupingBy { it }.eachCount()
        }
        if (counts.isEmpty()) return
        val totals: Map<ShirtSize, Int> =
            ShirtSize.entries.associateWith { size -> counts.sumOf { (_, bySize) -> bySize[size] ?: 0 } }
        val noShirt = rows.sumOf { row -> row.registration?.guests?.count { it.shirtSize == null } ?: 0 }

        parent.child("div", "d-flex flex-wrap align-items-center gap-3 mt-4 mb-2") {
            child("h4", "mb-0") {
                append("Shirt order ")
                child("span", "badge text-bg-secondary", totals.values.sum().toString())
            }
            child("button", "btn btn-outline-primary btn-sm", "Download CSV") {
                setAttribute("type", "button")
                val site = siteFilter?.let { Session.season.siteFor(it) }
                val suffix = site?.let { "-" + it.name.lowercase().replace(Regex("[^a-z0-9]+"), "-") } ?: ""
                onClick { downloadCsv("tbb-shirt-order-${data?.seasonYear}$suffix.csv", shirtCsv(counts, totals)) }
            }
        }
        parent.child("div", "table-responsive") {
            child("table", "table table-sm table-hover align-middle w-auto") {
                child("thead") {
                    child("tr") {
                        child("th", text = "Congregation")
                        ShirtSize.entries.forEach { child("th", "text-end", it.displayName) }
                        child("th", "text-end", "Total")
                    }
                }
                child("tbody") {
                    counts.forEach { (congregation, bySize) ->
                        child("tr") {
                            child("td", text = congregation)
                            ShirtSize.entries.forEach { size ->
                                child("td", "text-end", bySize[size]?.toString() ?: "")
                            }
                            child("td", "text-end fw-semibold", bySize.values.sum().toString())
                        }
                    }
                }
                child("tfoot") {
                    child("tr", "fw-semibold") {
                        child("td", text = "Total")
                        ShirtSize.entries.forEach { child("td", "text-end", totals.getValue(it).toString()) }
                        child("td", "text-end", totals.values.sum().toString())
                    }
                }
            }
        }
        if (noShirt > 0) {
            parent.child("p", "text-muted small", "Plus $noShirt under-3 guest(s) with no included shirt.")
        }
    }

    /** The shirt-order matrix as CSV — same rows and totals the table shows. */
    private fun shirtCsv(counts: List<Pair<String, Map<ShirtSize, Int>>>, totals: Map<ShirtSize, Int>): String {
        val header = listOf("Congregation") + ShirtSize.entries.map { it.displayName } + "Total"
        val rows = counts.map { (congregation, bySize) ->
            listOf(congregation) + ShirtSize.entries.map { (bySize[it] ?: 0).toString() } +
                bySize.values.sum().toString()
        }
        val totalRow = listOf("Total") + ShirtSize.entries.map { totals.getValue(it).toString() } +
            totals.values.sum().toString()
        return csvText(listOf(header) + rows + listOf(totalRow))
    }

    private fun columnHeaders(): List<String> = buildList {
        add("Congregation"); add("Code")
        if (multiSite) add("Site")
        addAll(listOf("Status", "Teams", "Contestants", "Individuals", "Guests", "Total due", "Submitted", "Paid", "Coaches"))
    }

    /**
     * The season under review: a plain label when only one year has data, otherwise a picker over
     * every year with registrations — the current event year is the default, past ones are
     * read-only reviews.
     */
    private fun yearSelect(parent: Element, desk: RegistrationDeskResponse) {
        if (desk.availableYears.size < 2) {
            parent.child("span", "text-muted", "Season ${desk.seasonYear}")
            return
        }
        parent.child("div", "d-flex align-items-center gap-2") {
            child("span", "text-muted", "Season")
            val select = child("select", "form-select form-select-sm w-auto") as HTMLSelectElement
            desk.availableYears.forEach { y ->
                val label = if (y == Session.season.eventYear.toString()) "$y (current)" else y
                val option = select.child("option", text = label) as HTMLOptionElement
                option.value = y
                if (y == desk.seasonYear) option.selected = true
            }
            select.addEventListener("change", {
                year = select.value.takeIf { it != Session.season.eventYear.toString() }
                reload()
            })
        }
    }

    private fun siteFilterSelect(parent: Element) {
        val select = parent.child("select", "form-select form-select-sm w-auto") as HTMLSelectElement
        (select.child("option", text = "All sites") as HTMLOptionElement).value = ""
        Session.season.sites.forEach { site ->
            val option = select.child("option", text = site.name) as HTMLOptionElement
            option.value = site.id
            if (site.id == siteFilter) option.selected = true
        }
        select.addEventListener("change", {
            siteFilter = select.value.ifEmpty { null }
            renderContent()
        })
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
                if (row.congregation.phone.isNotBlank()) {
                    child("div", "text-muted small", row.congregation.phone)
                }
            }
            child("td") { renderCodeCell(this, row) }
            if (multiSite) {
                child("td", text = Session.season.siteFor(reg?.siteId)?.name ?: "—")
            }
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
            child("td", text = reg?.guests?.size?.toString() ?: "—")
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
                    setAttribute("colspan", columnHeaders().size.toString())
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
        if (!isAdmin || viewingPast) {
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
                                mailingAddress = cong.mailingAddress, zip = cong.zip, phone = cong.phone, code = next,
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
            input.setAttribute("title", if (viewingPast) "Past season — read-only" else "Payment received")
            input.disabled = viewingPast
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
            if (multiSite) renderSiteSelectAdmin(parent, row.congregation.id, reg)
            reg.teams.forEach { team -> renderTeamDetail(parent, team) }
            renderUnassignedAdmin(parent, row.congregation.id, reg)
            if (reg.awayMembers.isNotEmpty()) {
                parent.child("h6", "mt-2", "On combo teams")
                reg.awayMembers.forEach { away ->
                    parent.child("div", "small") {
                        append("${away.entry.name} — on ${away.teamName} (${away.congregationName})")
                        // Pulling a member back home is the desk-side undo for a combo placement.
                        if (!viewingPast) child("button", "btn btn-link btn-sm py-0", "Unassign") {
                            setAttribute("type", "button")
                            onClick { deskAssign { Session.api.assignMemberTeam(away.entry.id, null) } }
                        }
                    }
                }
            }
            if (reg.individuals.isNotEmpty()) {
                parent.child("h6", "mt-2", "Individual contestants (adults)")
                reg.individuals.forEach { entry ->
                    parent.child("div", "small") {
                        append("${entry.name} — shirt ${entry.shirtSize.name}")
                        if (entry.tribeLeaderWilling) child("span", "badge text-bg-success ms-1", "tribe leader")
                    }
                }
            }
            if (reg.guests.isNotEmpty()) {
                parent.child("h6", "mt-2", "Guests & volunteers")
                reg.guests.forEach { guest ->
                    val details = listOfNotNull(
                        Session.season.ageTierFor(guest.birthdate).displayName.lowercase(),
                        guest.gender?.displayName?.lowercase(),
                        guest.shirtSize?.let { "shirt ${it.name}" } ?: "no shirt",
                    )
                    parent.child("div", "small") {
                        append("${guest.name} — ${details.joinToString(", ")}")
                        guest.positions.forEach { child("span", "badge text-bg-secondary ms-1", it) }
                        if (guest.tribeLeaderWilling) child("span", "badge text-bg-success ms-1", "tribe leader")
                        guest.contact?.let { child("div", "text-muted ms-3", contactSummary(it)) }
                    }
                }
            }
        }
        // Coach contact info (item 9, F3) — collected on their accounts; shown where a registrar acts on it.
        row.coaches.filter { it.contact != null }.takeIf { it.isNotEmpty() }?.let { withContact ->
            parent.child("h6", "mt-2", "Coach contact info")
            withContact.forEach { coach ->
                parent.child("div", "small", "${coach.displayName} — ${contactSummary(coach.contact!!)}")
            }
        }
        renderReturningCandidatesAdmin(parent, row)
        renderAttachPersonAdmin(parent, row)
        val rosterEmpty = reg == null ||
            (reg.teams.isEmpty() && reg.individuals.isEmpty() && reg.unassigned.isEmpty() && reg.awayMembers.isEmpty())
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
                val isAdult = candidate.birthdate == null && !candidate.isSeededYouth
                val div = when {
                    isAdult -> "Adult"
                    candidate.birthdate != null ->
                        Session.season.divisionForBirthdate(candidate.birthdate!!)?.displayName ?: "—"
                    // Workbook-seeded youth: division from the seeded grade until a birthdate exists.
                    else -> candidate.graduationYear
                        ?.let { Division.forGrade(Session.season.gradeForGraduationYear(it))?.displayName }
                        ?.plus(" (seeded)") ?: "—"
                }
                child("span", text = "${candidate.name} — $div" + (candidate.lastSeasonYear?.let { " · last $it" } ?: ""))
                // A seeded youth's first enrollment records their real birthdate.
                val birthdate = if (candidate.isSeededYouth) {
                    val input = child("input", "form-control form-control-sm w-auto") as HTMLInputElement
                    input.type = "date"
                    input.setAttribute("title", "Birthdate (first enrollment records it)")
                    input
                } else null
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
                        if (birthdate != null && birthdate.value.isBlank()) {
                            message = "${candidate.name} needs a birthdate — the workbook only had a school grade"
                            renderContent()
                            return@onClick
                        }
                        deskEnroll(row.congregation.id, candidate.contestantId) {
                            Session.api.enrollContestant(
                                row.congregation.id, candidate.contestantId,
                                ShirtSize.valueOf(shirt.value), teamSel?.value?.ifEmpty { null },
                                birthdate?.value,
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Registrar-only cross-congregation attach: find an existing person (any congregation) and add
     * them to this congregation's current-season roster — for someone who moved congregations.
     * Coaches never reach this; the desk is registrar-gated. Hidden while reviewing a past year, and
     * people already registered this season anywhere are filtered out (they can't be attached).
     */
    private fun renderAttachPersonAdmin(parent: Element, row: RegistrationDeskRowDto) {
        if (viewingPast) return
        val congregationId = row.congregation.id
        val currentYear = Session.season.eventYear.toString()
        parent.child("h6", "mt-3", "Attach an existing person")
        parent.child("p", "text-muted small mb-1",
            "Search anyone already in the system (e.g. moved from another congregation) and add them here.")
        val search = parent.child("input", "form-control form-control-sm mb-1") as HTMLInputElement
        search.setAttribute("placeholder", "Search people by name…")
        val results = parent.child("div")
        var seq = 0
        search.addEventListener("input", {
            val query = search.value.trim()
            val mySeq = ++seq
            results.clear()
            if (query.length < 2) return@addEventListener
            Shell.scope.launch {
                runCatching { Session.api.searchPeople(query) }.onSuccess { found ->
                    if (mySeq != seq) return@onSuccess
                    results.clear()
                    val attachable = found.filter { pwp -> pwp.participations.none { it.seasonYear == currentYear } }
                    if (attachable.isEmpty()) {
                        results.child("p", "text-muted small mb-0",
                            "No attachable matches — anyone already registered this season is hidden.")
                        return@onSuccess
                    }
                    val teams = data?.rows?.firstOrNull { it.congregation.id == congregationId }
                        ?.registration?.teams.orEmpty()
                    attachable.forEach { pwp -> renderAttachRow(results, congregationId, teams, pwp) }
                }
            }
        })
    }

    private fun renderAttachRow(
        parent: Element,
        congregationId: String,
        teams: List<TeamDto>,
        pwp: PersonWithParticipationsDto,
    ) {
        val person = pwp.person
        parent.child("div", "d-flex flex-wrap align-items-center gap-2 small mb-1") {
            val div = person.division(Session.season)?.displayName ?: "—"
            val last = pwp.participations.firstOrNull()?.let { " · last ${it.seasonYear} ${it.congregationName}" } ?: ""
            child("span", text = "${person.name} — $div$last")
            // A workbook-seeded youth (grade only) needs a real birthdate at first enrollment.
            val needsBirthdate = person.birthdate == null && person.graduationYear != null
            val birthdate = if (needsBirthdate) {
                (child("input", "form-control form-control-sm w-auto") as HTMLInputElement).apply {
                    type = "date"; setAttribute("title", "Birthdate (first enrollment records it)")
                }
            } else null
            val shirt = adminShirtSelect(this, pwp.participations.firstOrNull()?.shirtSize)
            // Youth may go straight onto a team (or the unassigned pool); adults attach as individuals.
            val teamSel = if (!person.isAdult && teams.isNotEmpty()) {
                val sel = child("select", "form-select form-select-sm w-auto") as HTMLSelectElement
                (sel.child("option", text = "— Unassigned —") as HTMLOptionElement).value = ""
                teams.forEach { (sel.child("option", text = it.name) as HTMLOptionElement).value = it.id }
                sel
            } else null
            child("button", "btn btn-sm btn-primary", "Attach") {
                setAttribute("type", "button")
                onClick {
                    if (needsBirthdate && birthdate!!.value.isBlank()) {
                        message = "${person.name} needs a birthdate — the workbook only had a school grade"
                        renderContent()
                        return@onClick
                    }
                    deskMutate(congregationId) {
                        Session.api.attachPerson(
                            congregationId, person.id, ShirtSize.valueOf(shirt.value),
                            teamSel?.value?.ifEmpty { null }, birthdate?.value,
                        )
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

    /** Enrolls a returning candidate — the response carries the row's pared candidate list. */
    private fun deskEnroll(congregationId: String, contestantId: String, call: suspend () -> RegistrationUpdateResponse) {
        message = null
        Shell.scope.launch {
            try {
                val updated = call()
                data = data?.let { desk ->
                    desk.copy(rows = desk.rows.map { row ->
                        if (row.congregation.id == congregationId) row.copy(
                            registration = updated.registration,
                            returningCandidates = updated.returningCandidates,
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
     * first when none exist), including another congregation's team (a combo team — that placement
     * is registrar-only, which is exactly who's on this screen). Uses the same endpoints as the
     * coach flow; an event-wide grant is accepted server-side and isn't window-gated.
     */
    private fun renderUnassignedAdmin(parent: Element, congregationId: String, reg: RegistrationDto) {
        if (reg.unassigned.isEmpty()) return
        if (viewingPast) {
            // Review only: who was never placed that year, without the placement controls.
            parent.child("h6", "mt-3") {
                append("Unassigned contestants ")
                child("span", "badge text-bg-warning", reg.unassigned.size.toString())
            }
            reg.unassigned.forEach { member ->
                val div = member.division(Session.season)?.displayName ?: "division unknown"
                parent.child("div", "small", "${member.name} — $div, shirt ${member.shirtSize.name}")
            }
            return
        }
        // Other congregations' teams, for combo placements ("Team — Congregation").
        val comboTargets = data?.rows.orEmpty()
            .filter { it.congregation.id != congregationId }
            .flatMap { row -> row.registration?.teams.orEmpty().map { it to row.congregation.name } }
        parent.child("h6", "mt-3") {
            append("Unassigned contestants ")
            child("span", "badge text-bg-warning", reg.unassigned.size.toString())
        }
        reg.unassigned.forEach { member ->
            parent.child("div", "d-flex flex-wrap align-items-center gap-2 small mb-1") {
                val div = member.division(Session.season)?.displayName ?: "division unknown"
                child("span", text = "${member.name} — $div, shirt ${member.shirtSize.name}")
                if (reg.teams.isEmpty() && comboTargets.isEmpty()) {
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
                    comboTargets.forEach { (team, congName) ->
                        (select.child("option", text = "${team.name} — $congName") as HTMLOptionElement).value = team.id
                    }
                    select.addEventListener("change", {
                        val teamId = select.value
                        if (teamId.isNotEmpty()) {
                            // A combo placement changes the hosting row too — reload the whole desk.
                            deskAssign { Session.api.assignMemberTeam(member.id, teamId) }
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

    /**
     * Runs a team assignment and reloads the whole desk: a combo placement touches two
     * congregations' rows (the member's and the hosting team's), so a single-row swap isn't enough.
     */
    private fun deskAssign(call: suspend () -> RegistrationUpdateResponse) {
        message = null
        Shell.scope.launch {
            try {
                call()
                data = Session.api.registrationDesk()
            } catch (e: Throwable) {
                message = "Could not update the roster: ${e.message}"
            }
            renderContent()
        }
    }

    /**
     * Desk-side site pin (multi-site seasons): a registrar sets or fixes which event site the
     * congregation attends — the same endpoint the coach uses, accepted for an event-wide grant.
     */
    private fun renderSiteSelectAdmin(parent: Element, congregationId: String, reg: RegistrationDto) {
        parent.child("div", "d-flex align-items-center gap-2 mb-2 small") {
            child("span", "fw-semibold", "Event site:")
            val select = child("select", "form-select form-select-sm w-auto") as HTMLSelectElement
            val chosen = Session.season.siteFor(reg.siteId)
            val placeholder = select.child("option", text = "— not chosen —") as HTMLOptionElement
            placeholder.value = ""
            placeholder.disabled = true
            placeholder.selected = chosen == null
            Session.season.sites.forEach { site ->
                val option = select.child("option", text = site.name) as HTMLOptionElement
                option.value = site.id
                if (site.id == chosen?.id) option.selected = true
            }
            select.addEventListener("change", {
                if (select.value.isNotEmpty()) {
                    deskMutate(congregationId) { Session.api.setRegistrationSite(congregationId, select.value) }
                }
            })
        }
    }

    /** Runs a desk-side registration mutation and swaps the refreshed registration into its row. */
    private fun deskMutate(congregationId: String, call: suspend () -> RegistrationUpdateResponse) {
        message = null
        Shell.scope.launch {
            try {
                val updated = call()
                data = data?.let { desk ->
                    desk.copy(rows = desk.rows.map { row ->
                        if (row.congregation.id == congregationId) row.copy(
                            registration = updated.registration,
                            returningCandidates = updated.returningCandidates,
                        ) else row
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
            parent.child("div", "small") {
                append("${member.name} — $division, shirt ${member.shirtSize.name}")
                // A visiting (combo-team) member — registered and paid for by their own congregation.
                member.congregationName?.let { child("span", "badge text-bg-info ms-1", "from $it") }
            }
        }
    }

    private fun renderSummary(parent: Element, desk: RegistrationDeskResponse) {
        val regs = desk.rows.mapNotNull { it.registration }
        val dueCents = regs.mapNotNull { it.totalCents }.sum()
        parent.child("p", "text-muted") {
            append(
                "${regs.size} of ${desk.rows.size} congregations registered · " +
                    "${regs.sumOf { it.contestantCount }} contestants · " +
                    "${regs.sumOf { it.guests.size }} guests · " +
                    "${formatCents(dueCents)} total due · " +
                    "${regs.count { it.paidAt != null }} paid",
            )
        }
    }

    // --- CSV export -------------------------------------------------------------------------

    private fun deskCsv(desk: RegistrationDeskResponse): String {
        val header = listOfNotNull(
            "Congregation", "Code", "City", "State", "Site".takeIf { multiSite }, "Status", "Teams",
            "Contestants", "Individuals", "Guests", "Total Due", "Submitted", "Paid", "Coach Names", "Coach Emails",
        )
        val rows = desk.rows.map { row ->
            val reg = row.registration
            listOfNotNull(
                row.congregation.name,
                row.congregation.code,
                row.congregation.city,
                row.congregation.state,
                (Session.season.siteFor(reg?.siteId)?.name ?: "").takeIf { multiSite },
                reg?.status?.name ?: "NONE",
                reg?.teams?.size?.toString() ?: "",
                reg?.contestantCount?.toString() ?: "",
                reg?.individuals?.size?.toString() ?: "",
                reg?.guests?.size?.toString() ?: "",
                reg?.totalCents?.let { formatCents(it) } ?: "",
                reg?.submittedAt?.take(10) ?: "",
                reg?.paidAt?.take(10) ?: "",
                row.coaches.joinToString("; ") { it.displayName },
                row.coaches.joinToString("; ") { it.email },
            )
        }
        return csvText(listOf(header) + rows)
    }

    /** "1 Main St, Waco, TX 76701 · 555-1234 · a@b.org · prefers text" — only the parts provided. */
    private fun contactSummary(contact: ContactInfoDto): String {
        val stateZip = listOf(contact.state, contact.zip).filter { it.isNotBlank() }.joinToString(" ")
        val postal = listOf(contact.address, contact.city, stateZip).filter { it.isNotBlank() }.joinToString(", ")
        return listOfNotNull(
            postal.takeIf { it.isNotBlank() },
            contact.phone.takeIf { it.isNotBlank() },
            contact.email.takeIf { it.isNotBlank() },
            contact.preference?.let { "prefers ${it.displayName.lowercase()}" },
        ).joinToString(" · ")
    }

    /**
     * Fetches the nametags PDF (item 14, F8) honoring the site filter — an authenticated fetch,
     * not a public /generate link, because attendee names include minors'. Generating assigns any
     * missing tester IDs via the same append-only scheme as #admin/testers.
     */
    private fun downloadNametags(button: Element, seasonYear: String) {
        (button as? HTMLButtonElement)?.disabled = true
        message = null
        Shell.scope.launch {
            try {
                val bytes = Session.api.nametagsPdf(siteFilter)
                val site = siteFilter?.let { Session.season.siteFor(it) }
                val suffix = site?.let { "-" + it.name.lowercase().replace(Regex("[^a-z0-9]+"), "-") } ?: ""
                downloadBlob(
                    Blob(arrayOf<dynamic>(bytes), BlobPropertyBag(type = "application/pdf")),
                    "tbb-nametags-$seasonYear$suffix.pdf",
                )
            } catch (e: Throwable) {
                message = "Could not generate nametags: ${e.message}"
            }
            renderContent()
        }
    }
}
