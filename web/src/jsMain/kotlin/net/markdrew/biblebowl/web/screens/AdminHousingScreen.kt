package net.markdrew.biblebowl.web.screens

import kotlinx.coroutines.launch
import net.markdrew.biblebowl.api.AddCabinAssignmentRequest
import net.markdrew.biblebowl.api.AttendeeRow
import net.markdrew.biblebowl.api.CabinAssignmentDto
import net.markdrew.biblebowl.api.CabinDto
import net.markdrew.biblebowl.api.Gender
import net.markdrew.biblebowl.api.HousingResponse
import net.markdrew.biblebowl.api.RegistrationDeskResponse
import net.markdrew.biblebowl.api.UpsertCabinRequest
import net.markdrew.biblebowl.api.deskAttendees
import net.markdrew.biblebowl.api.multiSite
import net.markdrew.biblebowl.api.siteFor
import net.markdrew.biblebowl.web.Routes
import net.markdrew.biblebowl.web.Session
import net.markdrew.biblebowl.web.Shell
import net.markdrew.biblebowl.web.child
import net.markdrew.biblebowl.web.clear
import net.markdrew.biblebowl.web.csvText
import net.markdrew.biblebowl.web.downloadCsv
import net.markdrew.biblebowl.web.errorLine
import net.markdrew.biblebowl.web.onClick
import net.markdrew.biblebowl.web.spinner
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLOptionElement
import org.w3c.dom.HTMLSelectElement

/**
 * Housing / cabin assignments (item 15, F9; replaces the workbook's `Housing Assignments` and
 * `Check out assignments` tabs): a thin, free-form assignment grid — cabins per site, each with
 * congregation × gender group rows (the 2026 pattern) and ad-hoc label rows for families/staff —
 * plus the per-congregation check-out duty roster. No optimizer: occupant counts are derived
 * from the registration desk payload purely as an eyeball check. Gated like the desk (event-wide
 * REGISTRATION_MANAGE).
 */
object AdminHousingScreen {

    private var housing: HousingResponse? = null
    private var desk: RegistrationDeskResponse? = null
    private var message: String? = null
    private var editingCabinId: String? = null
    private lateinit var content: HTMLElement

    private val multiSite: Boolean get() = Session.season.multiSite

    fun render(container: HTMLElement) {
        container.child("h1", "page-title", "Housing")
        content = container.child("div")
        content.spinner()
        housing = null
        desk = null
        message = null
        editingCabinId = null
        Shell.scope.launch {
            try {
                housing = Session.api.housing()
                desk = Session.api.registrationDesk()
                renderContent()
            } catch (e: Throwable) {
                content.clear()
                content.errorLine("Could not load housing: ${e.message}")
            }
        }
    }

    /** Runs a housing mutation, refreshes from its [HousingResponse], and re-renders. */
    private fun mutate(block: suspend () -> HousingResponse) {
        Shell.scope.launch {
            try {
                housing = block()
                message = null
            } catch (e: Throwable) {
                message = e.message ?: "Something went wrong"
            }
            renderContent()
        }
    }

    private fun renderContent() {
        val h = housing ?: return
        val d = desk ?: return
        content.clear()
        val attendees = deskAttendees(Session.season, d.rows)
        content.child("div", "d-flex flex-wrap align-items-center gap-3 mb-3") {
            child("span", "text-muted", "Season ${h.seasonYear}")
            child("a", "btn btn-outline-secondary btn-sm", "Registration desk") {
                setAttribute("href", "#${Routes.ADMIN_REGISTRATIONS}")
            }
            if (h.cabins.isNotEmpty()) {
                child("button", "btn btn-outline-primary btn-sm", "Download CSV") {
                    setAttribute("type", "button")
                    onClick { downloadCsv("tbb-housing-${h.seasonYear}.csv", housingCsv(h, attendees)) }
                }
            }
        }
        message?.let { content.errorLine(it) }
        content.child(
            "p", "text-muted small",
            "Assign each congregation's boys and girls (or the whole congregation) to cabins, and " +
                "add ad-hoc rows for families and staff. Counts come from the registration desk — " +
                "they're a sanity check, not a rule.",
        )

        // One section per event site (multi-site seasons); a lone null-site section otherwise.
        val siteIds: List<String?> =
            if (multiSite) Session.season.sites.map { it.id } else listOf(null)
        siteIds.forEach { siteId -> renderSiteSection(siteId, h, attendees) }

        renderUnhoused(h, d, attendees)
        renderDuties(h, d)
    }

    private fun renderSiteSection(siteId: String?, h: HousingResponse, attendees: List<AttendeeRow>) {
        if (multiSite) {
            content.child("h4", "mt-4", Session.season.siteFor(siteId)?.name ?: "Unknown site")
        }
        val cabins = h.cabins.filter { it.siteId == siteId }
        if (cabins.isEmpty()) content.child("p", "text-muted", "No cabins yet.")
        cabins.forEach { renderCabin(content, it, attendees) }
        renderAddCabin(content, siteId)
    }

    private fun renderCabin(parent: Element, cabin: CabinDto, attendees: List<AttendeeRow>) {
        parent.child("div", "card mb-3") {
            child("div", "card-header d-flex flex-wrap align-items-center gap-2") {
                if (editingCabinId == cabin.id) {
                    renderCabinEditor(this, cabin)
                } else {
                    child("span", "fw-semibold", cabin.name)
                    val occupants = cabin.assignments.sumOf { occupantCount(it, attendees) ?: 0 }
                    val capacity = cabin.capacity?.let { " of $it beds" } ?: ""
                    child("span", "text-muted small", "$occupants assigned$capacity")
                    if (cabin.capacity != null && cabin.assignments.sumOf { occupantCount(it, attendees) ?: 0 } > cabin.capacity!!) {
                        child("span", "badge text-bg-warning", "over capacity")
                    }
                    child("button", "btn btn-outline-secondary btn-sm ms-auto", "Edit") {
                        setAttribute("type", "button")
                        onClick { editingCabinId = cabin.id; renderContent() }
                    }
                    child("button", "btn btn-outline-danger btn-sm", "Delete") {
                        setAttribute("type", "button")
                        onClick { mutate { Session.api.deleteCabin(cabin.id) } }
                    }
                }
            }
            child("ul", "list-group list-group-flush") {
                cabin.assignments.forEach { a ->
                    child("li", "list-group-item d-flex align-items-center gap-2") {
                        child("span", text = assignmentLabel(a))
                        occupantCount(a, attendees)?.let { child("span", "badge text-bg-secondary", it.toString()) }
                        child("button", "btn btn-outline-danger btn-sm ms-auto", "Remove") {
                            setAttribute("type", "button")
                            onClick { mutate { Session.api.deleteCabinAssignment(a.id) } }
                        }
                    }
                }
                child("li", "list-group-item") { renderAddAssignment(this, cabin) }
            }
        }
    }

    /** Inline name/capacity editor shown in the cabin header while editing. */
    private fun renderCabinEditor(parent: Element, cabin: CabinDto) {
        val name = parent.child("input", "form-control form-control-sm w-auto") as HTMLInputElement
        name.value = cabin.name
        val capacity = parent.child("input", "form-control form-control-sm") as HTMLInputElement
        capacity.type = "number"
        capacity.placeholder = "Beds"
        capacity.style.width = "6em"
        capacity.value = cabin.capacity?.toString() ?: ""
        parent.child("button", "btn btn-primary btn-sm", "Save") {
            setAttribute("type", "button")
            onClick {
                editingCabinId = null
                mutate {
                    Session.api.updateCabin(cabin.id, UpsertCabinRequest(name.value, cabin.siteId, capacity.value.toIntOrNull()))
                }
            }
        }
        parent.child("button", "btn btn-outline-secondary btn-sm", "Cancel") {
            setAttribute("type", "button")
            onClick { editingCabinId = null; renderContent() }
        }
    }

    private fun renderAddCabin(parent: Element, siteId: String?) {
        parent.child("div", "d-flex flex-wrap align-items-center gap-2 mb-4") {
            val name = child("input", "form-control form-control-sm w-auto") as HTMLInputElement
            name.placeholder = "New cabin (or RV site, duplex…)"
            val capacity = child("input", "form-control form-control-sm") as HTMLInputElement
            capacity.type = "number"
            capacity.placeholder = "Beds"
            capacity.style.width = "6em"
            child("button", "btn btn-outline-primary btn-sm", "Add cabin") {
                setAttribute("type", "button")
                onClick {
                    if (name.value.isBlank()) return@onClick
                    mutate { Session.api.addCabin(UpsertCabinRequest(name.value, siteId, capacity.value.toIntOrNull())) }
                }
            }
        }
    }

    /** The add-assignment row: congregation and gender pickers plus a free-text label. */
    private fun renderAddAssignment(parent: Element, cabin: CabinDto) {
        parent.child("div", "d-flex flex-wrap align-items-center gap-2") {
            val congregation = child("select", "form-select form-select-sm w-auto") as HTMLSelectElement
            (congregation.child("option", text = "No congregation (ad-hoc row)") as HTMLOptionElement).value = ""
            registeredCongregations(cabin.siteId).forEach { (id, name) ->
                val option = congregation.child("option", text = name) as HTMLOptionElement
                option.value = id
            }
            val gender = child("select", "form-select form-select-sm w-auto") as HTMLSelectElement
            (gender.child("option", text = "Everyone") as HTMLOptionElement).value = ""
            Gender.entries.forEach { g ->
                val option = gender.child("option", text = g.displayName) as HTMLOptionElement
                option.value = g.name
            }
            val label = child("input", "form-control form-control-sm w-auto") as HTMLInputElement
            label.placeholder = "Label / note (e.g. Smith family — RV 3)"
            child("button", "btn btn-outline-primary btn-sm", "Add") {
                setAttribute("type", "button")
                onClick {
                    val congregationId = congregation.value.ifEmpty { null }
                    if (congregationId == null && label.value.isBlank()) {
                        message = "Pick a congregation or enter a label"
                        renderContent()
                        return@onClick
                    }
                    mutate {
                        Session.api.addCabinAssignment(
                            cabin.id,
                            AddCabinAssignmentRequest(
                                congregationId = congregationId,
                                gender = gender.value.ifEmpty { null }?.let { Gender.valueOf(it) },
                                label = label.value,
                            ),
                        )
                    }
                }
            }
        }
    }

    /**
     * Registered congregations for the picker, [siteId]-scoped on multi-site seasons (a cabin
     * only ever houses congregations attending its site); (id, name) pairs sorted by name.
     */
    private fun registeredCongregations(siteId: String?): List<Pair<String, String>> =
        desk?.rows.orEmpty()
            .filter { it.registration != null }
            .filter { siteId == null || Session.season.siteFor(it.registration?.siteId)?.id == siteId }
            .map { it.congregation.id to it.congregation.name }
            .sortedBy { it.second.lowercase() }

    private fun assignmentLabel(a: CabinAssignmentDto): String {
        val group = when {
            a.congregationId == null -> null
            a.gender == null -> a.congregationName ?: "?"
            else -> "${a.congregationName ?: "?"} — ${a.gender!!.displayName.lowercase()}"
        }
        return listOfNotNull(group, a.label.ifBlank { null }).joinToString(" · ").ifEmpty { "—" }
    }

    /** Derived head count for a congregation × gender row; null for an ad-hoc (label-only) row. */
    private fun occupantCount(a: CabinAssignmentDto, attendees: List<AttendeeRow>): Int? {
        val congregationId = a.congregationId ?: return null
        return attendees.count {
            it.congregationId == congregationId && (a.gender == null || it.gender == a.gender)
        }
    }

    /** Registered congregations with attendees who appear in no assignment row yet — a to-do hint. */
    private fun renderUnhoused(h: HousingResponse, d: RegistrationDeskResponse, attendees: List<AttendeeRow>) {
        val housed = h.cabins.flatMap { it.assignments }.mapNotNull { it.congregationId }.toSet()
        val unhoused = d.rows
            .filter { it.registration != null && it.congregation.id !in housed }
            .map { row -> row.congregation.name to attendees.count { it.congregationId == row.congregation.id } }
            .filter { it.second > 0 }
            .sortedBy { it.first.lowercase() }
        if (unhoused.isEmpty()) return
        content.child("h4", "mt-4", "Not yet housed")
        unhoused.forEach { (name, count) ->
            content.child("div", "small", "$name — $count attendee${if (count == 1) "" else "s"}")
        }
    }

    /**
     * The check-out duty roster (one adult per congregation walks their cabin at departure):
     * a row per registered congregation, free-form name, saved per row. Congregations with a
     * duty set but no registration this season still show, so stale rows stay clearable.
     */
    private fun renderDuties(h: HousingResponse, d: RegistrationDeskResponse) {
        content.child("h4", "mt-4", "Cabin check-out duty")
        content.child("p", "text-muted small mb-2", "The one adult per congregation responsible for their cabin walk-through at departure.")
        val byId = h.duties.associateBy { it.congregationId }
        val rows = d.rows.filter { it.registration != null }.map { it.congregation.id to it.congregation.name } +
            h.duties.filter { duty -> d.rows.none { it.registration != null && it.congregation.id == duty.congregationId } }
                .map { it.congregationId to it.congregationName }
        content.child("div", "table-responsive") {
            child("table", "table table-sm align-middle w-auto") {
                child("thead") {
                    child("tr") {
                        child("th", text = "Congregation")
                        child("th", text = "Check-out adult")
                        child("th")
                    }
                }
                child("tbody") {
                    rows.sortedBy { it.second.lowercase() }.forEach { (congregationId, name) ->
                        child("tr") {
                            child("td", text = name)
                            val cell = child("td")
                            val input = cell.child("input", "form-control form-control-sm") as HTMLInputElement
                            input.value = byId[congregationId]?.adultName ?: ""
                            input.placeholder = "Adult's name"
                            child("td") {
                                child("button", "btn btn-outline-primary btn-sm", "Save") {
                                    setAttribute("type", "button")
                                    onClick { mutate { Session.api.setCheckoutDuty(congregationId, input.value) } }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // --- CSV export -------------------------------------------------------------------------

    /** One CSV row per assignment (site, cabin, capacity, group, count), then the duty roster. */
    private fun housingCsv(h: HousingResponse, attendees: List<AttendeeRow>): String {
        val header = listOfNotNull(
            "Site".takeIf { multiSite }, "Cabin", "Capacity", "Assignment", "Count",
        )
        val body = h.cabins.flatMap { cabin ->
            val site = Session.season.siteFor(cabin.siteId)?.name ?: ""
            val rows = cabin.assignments.ifEmpty { listOf(null) }
            rows.map { a ->
                listOfNotNull(
                    site.takeIf { multiSite },
                    cabin.name,
                    cabin.capacity?.toString() ?: "",
                    a?.let { assignmentLabel(it) } ?: "",
                    a?.let { occupantCount(it, attendees)?.toString() } ?: "",
                )
            }
        }
        val duties = listOf(emptyList(), listOfNotNull("".takeIf { multiSite }, "Check-out duty", "", "", "")) +
            h.duties.map {
                listOfNotNull("".takeIf { multiSite }, it.congregationName, "", it.adultName, "")
            }
        return csvText(listOf(header) + body + duties)
    }
}
