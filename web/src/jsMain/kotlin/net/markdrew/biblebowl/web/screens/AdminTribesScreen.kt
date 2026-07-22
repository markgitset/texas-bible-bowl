package net.markdrew.biblebowl.web.screens

import kotlinx.coroutines.launch
import net.markdrew.biblebowl.api.RegistrationDeskResponse
import net.markdrew.biblebowl.api.TribeDto
import net.markdrew.biblebowl.api.TribesResponse
import net.markdrew.biblebowl.api.UpsertTribeRequest
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

/**
 * Tribes & tribe leaders (item 16, F10; replaces the workbook's `Tribe leader assignment` tab):
 * a thin admin tool — tribes per site (2026: color names, two leaders each) with free-form leader
 * names. The leader input suggests adults who flagged willingness (item 8: adult guests and
 * individual contestants with `tribeLeaderWilling`, from the desk payload), and a roll-up lists
 * the willing not yet assigned anywhere. Gated like housing (event-wide REGISTRATION_MANAGE).
 */
object AdminTribesScreen {

    private var tribes: TribesResponse? = null
    private var desk: RegistrationDeskResponse? = null
    private var message: String? = null
    private var editingTribeId: String? = null
    private lateinit var content: HTMLElement

    private val multiSite: Boolean get() = Session.season.multiSite

    fun render(container: HTMLElement) {
        container.child("h1", "page-title", "Tribes")
        content = container.child("div")
        content.spinner()
        tribes = null
        desk = null
        message = null
        editingTribeId = null
        Shell.scope.launch {
            try {
                tribes = Session.api.tribes()
                desk = Session.api.registrationDesk()
                renderContent()
            } catch (e: Throwable) {
                content.clear()
                content.errorLine("Could not load tribes: ${e.message}")
            }
        }
    }

    /** Runs a tribe mutation, refreshes from its [TribesResponse], and re-renders. */
    private fun mutate(block: suspend () -> TribesResponse) {
        Shell.scope.launch {
            try {
                tribes = block()
                message = null
            } catch (e: Throwable) {
                message = e.message ?: "Something went wrong"
            }
            renderContent()
        }
    }

    /** A willing adult (item 8's flags), labeled with their congregation and site. */
    private data class WillingLeader(val name: String, val congregation: String, val siteId: String?)

    /** Adults who flagged tribe-leader willingness: adult guests and individual contestants. */
    private fun willingLeaders(): List<WillingLeader> =
        desk?.rows.orEmpty().flatMap { row ->
            val reg = row.registration ?: return@flatMap emptyList<WillingLeader>()
            val siteId = Session.season.siteFor(reg.siteId)?.id
            reg.guests.filter { it.participation.tribeLeaderWilling }
                .map { WillingLeader(it.person.name, row.congregation.name, siteId) } +
                reg.individuals.filter { it.participation.tribeLeaderWilling }
                    .map { WillingLeader(it.person.name, row.congregation.name, siteId) }
        }.sortedBy { it.name.lowercase() }

    private fun renderContent() {
        val t = tribes ?: return
        if (desk == null) return
        content.clear()
        content.child("div", "d-flex flex-wrap align-items-center gap-3 mb-3") {
            child("span", "text-muted", "Season ${t.seasonYear}")
            child("a", "btn btn-outline-secondary btn-sm", "Registration desk") {
                setAttribute("href", "#${Routes.ADMIN_REGISTRATIONS}")
            }
            if (t.tribes.isNotEmpty()) {
                child("button", "btn btn-outline-primary btn-sm", "Download CSV") {
                    setAttribute("type", "button")
                    onClick { downloadCsv("tbb-tribes-${t.seasonYear}.csv", tribesCsv(t)) }
                }
            }
        }
        message?.let { content.errorLine(it) }
        content.child(
            "p", "text-muted small",
            "Define the event's tribes (2026 used color names, two leaders each) and assign " +
                "leaders — the name field suggests adults who volunteered on registration, but " +
                "any adult can be typed in.",
        )

        // One section per event site (multi-site seasons); a lone null-site section otherwise.
        val siteIds: List<String?> =
            if (multiSite) Session.season.sites.map { it.id } else listOf(null)
        siteIds.forEach { siteId -> renderSiteSection(siteId, t) }

        renderWillingPool(t)
    }

    private fun renderSiteSection(siteId: String?, t: TribesResponse) {
        if (multiSite) {
            content.child("h4", "mt-4", Session.season.siteFor(siteId)?.name ?: "Unknown site")
        }
        val siteTribes = t.tribes.filter { it.siteId == siteId }
        if (siteTribes.isEmpty()) content.child("p", "text-muted", "No tribes yet.")
        siteTribes.forEach { renderTribe(content, it) }
        renderAddTribe(content, siteId)
    }

    private fun renderTribe(parent: Element, tribe: TribeDto) {
        parent.child("div", "card mb-3") {
            child("div", "card-header d-flex flex-wrap align-items-center gap-2") {
                if (editingTribeId == tribe.id) {
                    renderTribeEditor(this, tribe)
                } else {
                    child("span", "fw-semibold", tribe.name)
                    // The 2026 convention was two leaders per tribe — a hint, not a rule.
                    if (tribe.leaders.size < 2) child("span", "badge text-bg-warning", "needs leaders")
                    child("button", "btn btn-outline-secondary btn-sm ms-auto", "Rename") {
                        setAttribute("type", "button")
                        onClick { editingTribeId = tribe.id; renderContent() }
                    }
                    child("button", "btn btn-outline-danger btn-sm", "Delete") {
                        setAttribute("type", "button")
                        onClick { mutate { Session.api.deleteTribe(tribe.id) } }
                    }
                }
            }
            child("ul", "list-group list-group-flush") {
                tribe.leaders.forEach { leader ->
                    child("li", "list-group-item d-flex align-items-center gap-2") {
                        child("span", text = leader.name)
                        child("button", "btn btn-outline-danger btn-sm ms-auto", "Remove") {
                            setAttribute("type", "button")
                            onClick { mutate { Session.api.deleteTribeLeader(leader.id) } }
                        }
                    }
                }
                child("li", "list-group-item") { renderAddLeader(this, tribe) }
            }
        }
    }

    /** Inline rename editor shown in the tribe header while editing. */
    private fun renderTribeEditor(parent: Element, tribe: TribeDto) {
        val name = parent.child("input", "form-control form-control-sm w-auto") as HTMLInputElement
        name.value = tribe.name
        parent.child("button", "btn btn-primary btn-sm", "Save") {
            setAttribute("type", "button")
            onClick {
                editingTribeId = null
                mutate { Session.api.updateTribe(tribe.id, UpsertTribeRequest(name.value, tribe.siteId)) }
            }
        }
        parent.child("button", "btn btn-outline-secondary btn-sm", "Cancel") {
            setAttribute("type", "button")
            onClick { editingTribeId = null; renderContent() }
        }
    }

    private fun renderAddTribe(parent: Element, siteId: String?) {
        parent.child("div", "d-flex flex-wrap align-items-center gap-2 mb-4") {
            val name = child("input", "form-control form-control-sm w-auto") as HTMLInputElement
            name.placeholder = "New tribe (e.g. Red, Turquoise…)"
            child("button", "btn btn-outline-primary btn-sm", "Add tribe") {
                setAttribute("type", "button")
                onClick {
                    if (name.value.isBlank()) return@onClick
                    mutate { Session.api.addTribe(UpsertTribeRequest(name.value, siteId)) }
                }
            }
        }
    }

    /** The add-leader row: a name input with a datalist of the site's willing adults. */
    private fun renderAddLeader(parent: Element, tribe: TribeDto) {
        val listId = "willing-leaders-${tribe.id}"
        parent.child("div", "d-flex flex-wrap align-items-center gap-2") {
            val name = child("input", "form-control form-control-sm w-auto") as HTMLInputElement
            name.placeholder = "Leader's name"
            name.setAttribute("list", listId)
            child("datalist") {
                setAttribute("id", listId)
                // A tribe draws from its own site's willing adults (or all, single-site).
                willingLeaders()
                    .filter { tribe.siteId == null || it.siteId == tribe.siteId }
                    .forEach { willing ->
                        child("option", text = "${willing.name} — ${willing.congregation}") {
                            setAttribute("value", willing.name)
                        }
                    }
            }
            child("button", "btn btn-outline-primary btn-sm", "Add leader") {
                setAttribute("type", "button")
                onClick {
                    if (name.value.isBlank()) return@onClick
                    mutate { Session.api.addTribeLeader(tribe.id, name.value) }
                }
            }
        }
    }

    /** Willing adults not yet assigned to any tribe (matched by name) — the recruiting pool. */
    private fun renderWillingPool(t: TribesResponse) {
        val assigned = t.tribes.flatMap { it.leaders }.map { it.name.trim().lowercase() }.toSet()
        val unassigned = willingLeaders().filter { it.name.trim().lowercase() !in assigned }
        if (unassigned.isEmpty()) return
        content.child("h4", "mt-4") {
            append("Willing, not yet assigned ")
            child("span", "badge text-bg-success", unassigned.size.toString())
        }
        unassigned.forEach { willing ->
            val site = willing.siteId?.takeIf { multiSite }
                ?.let { id -> Session.season.siteFor(id)?.name }?.let { " ($it)" } ?: ""
            content.child("div", "small", "${willing.name} — ${willing.congregation}$site")
        }
    }

    // --- CSV export -------------------------------------------------------------------------

    /** One CSV row per leader (leaderless tribes still get a row), like the workbook tab. */
    private fun tribesCsv(t: TribesResponse): String {
        val header = listOfNotNull("Site".takeIf { multiSite }, "Tribe", "Leader")
        val body = t.tribes.flatMap { tribe ->
            val site = Session.season.siteFor(tribe.siteId)?.name ?: ""
            val leaders = tribe.leaders.ifEmpty { listOf(null) }
            leaders.map { leader ->
                listOfNotNull(site.takeIf { multiSite }, tribe.name, leader?.name ?: "")
            }
        }
        return csvText(listOf(header) + body)
    }
}
