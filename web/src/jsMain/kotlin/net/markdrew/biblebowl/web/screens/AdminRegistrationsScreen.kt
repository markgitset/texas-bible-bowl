package net.markdrew.biblebowl.web.screens

import kotlinx.browser.document
import kotlinx.coroutines.launch
import net.markdrew.biblebowl.api.RegistrationDeskResponse
import net.markdrew.biblebowl.api.RegistrationDeskRowDto
import net.markdrew.biblebowl.api.RegistrationStatus
import net.markdrew.biblebowl.api.TeamDto
import net.markdrew.biblebowl.api.contestantCount
import net.markdrew.biblebowl.api.division
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
                            "Congregation", "Status", "Teams", "Contestants", "Individuals",
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
            child("td") { statusBadge(this, reg?.status) }
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
                    setAttribute("colspan", "9")
                    renderDetail(this, row)
                }
            }
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
        if (reg == null) {
            parent.child("p", "text-muted mb-0", "No registration started this season.")
            return
        }
        reg.teams.forEach { team -> renderTeamDetail(parent, team) }
        if (reg.individuals.isNotEmpty()) {
            parent.child("h6", "mt-2", "Individual contestants (adults)")
            reg.individuals.forEach { entry ->
                parent.child("div", "small", "${entry.name} — shirt ${entry.shirtSize.name}")
            }
        }
        if (reg.teams.isEmpty() && reg.individuals.isEmpty()) {
            parent.child("p", "text-muted mb-0", "Nothing on the roster yet.")
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
            "Congregation", "City", "State", "Status", "Teams", "Contestants", "Individuals",
            "Total Due", "Submitted", "Paid", "Coach Names", "Coach Emails",
        )
        val rows = desk.rows.map { row ->
            val reg = row.registration
            listOf(
                row.congregation.name,
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
