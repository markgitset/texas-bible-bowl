package net.markdrew.biblebowl.web.screens

import kotlinx.coroutines.launch
import net.markdrew.biblebowl.api.AgeTier
import net.markdrew.biblebowl.api.AttendeeRow
import net.markdrew.biblebowl.api.Division
import net.markdrew.biblebowl.api.Gender
import net.markdrew.biblebowl.api.RegistrationDeskResponse
import net.markdrew.biblebowl.api.RegistrationDeskRowDto
import net.markdrew.biblebowl.api.deskAttendees
import net.markdrew.biblebowl.api.division
import net.markdrew.biblebowl.api.graduatingSeniors
import net.markdrew.biblebowl.api.isInexperienced
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
import org.w3c.dom.HTMLOptionElement
import org.w3c.dom.HTMLSelectElement

/**
 * Registration counts dashboard (replaces the 2026 workbook's `Counts` tab): totals and
 * per-site / per-congregation breakdowns by attendee type, gender, grade and age group, and
 * division + experience category, plus the graduating-seniors list (derived from grade 12 — no
 * stored flag). Read-only, computed client-side from the same desk payload the registration desk
 * shows, and gated the same way (event-wide REGISTRATION_MANAGE). Attendee types overlap per
 * person — a coach may also be a tester or a guest — so coach accounts are reported beside the
 * attendee total, never added to it.
 */
object AdminCountsScreen {

    private var data: RegistrationDeskResponse? = null
    private var siteFilter: String? = null // EventSiteDto id, or null = all sites (multi-site seasons)
    private lateinit var content: HTMLElement

    private val multiSite: Boolean get() = Session.season.multiSite

    /** The youth grades a tester can be in, for the by-grade columns. */
    private val GRADES = 3..12

    fun render(container: HTMLElement) {
        container.child("h1", "page-title", "Registration counts")
        content = container.child("div")
        content.spinner()
        data = null
        siteFilter = null
        Shell.scope.launch {
            try {
                data = Session.api.registrationDesk()
                renderContent()
            } catch (e: Throwable) {
                content.clear()
                content.errorLine("Could not load registration counts: ${e.message}")
            }
        }
    }

    private fun renderContent() {
        val desk = data ?: return
        content.clear()
        content.child("div", "d-flex flex-wrap align-items-center gap-3 mb-3") {
            child("span", "text-muted", "Season ${desk.seasonYear}")
            if (multiSite) siteFilterSelect(this)
            child("a", "btn btn-outline-secondary btn-sm", "Registration desk") {
                setAttribute("href", "#${Routes.ADMIN_REGISTRATIONS}")
            }
            child("button", "btn btn-outline-primary btn-sm", "Download CSV") {
                setAttribute("type", "button")
                onClick { downloadCsv("tbb-counts-${desk.seasonYear}.csv", countsCsv(desk)) }
            }
        }

        // Everything below respects the site filter, so picking a site gives the per-site counts.
        val rows = desk.rows.filter { row ->
            siteFilter == null || Session.season.siteFor(row.registration?.siteId)?.id == siteFilter
        }
        val attendees = deskAttendees(Session.season, rows)
        if (attendees.isEmpty()) {
            content.child("p", "text-muted", "No attendees registered yet.")
            return
        }

        renderHeadline(rows, attendees)
        renderCongregationTable(content, rows, attendees)
        renderGender(content, attendees)
        renderGradesAndAges(content, attendees)
        renderDivisions(content, rows, attendees)
        renderGraduatingSeniors(content, attendees)
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

    private fun renderHeadline(rows: List<RegistrationDeskRowDto>, attendees: List<AttendeeRow>) {
        val registered = rows.count { it.registration != null }
        val testers = attendees.count { it.tester }
        val coachAccounts = rows.filter { it.registration != null }
            .flatMap { it.coaches }.distinctBy { it.email }.size
        content.child("p", "text-muted mb-1") {
            append(
                "$registered of ${rows.size} congregations registered · " +
                    "${attendees.size} attendees — $testers testers, ${attendees.size - testers} guests · " +
                    "$coachAccounts coach accounts",
            )
        }
        content.child(
            "p", "text-muted small",
            "Attendee types overlap: a coach may also be a tester or a guest, so coach accounts " +
                "are shown beside the attendee total, not added to it.",
        )
    }

    /** A small counts table: [headers], one or more [rows] of (label, cell values), optional total row. */
    private fun countsTable(parent: Element, headers: List<String>, rows: List<Pair<String, List<Int>>>) {
        parent.child("div", "table-responsive") {
            child("table", "table table-sm w-auto align-middle") {
                child("thead") {
                    child("tr") { headers.forEach { child("th", "text-end", it) } }
                }
                child("tbody") {
                    rows.forEach { (label, cells) ->
                        child("tr") {
                            child("th", text = label)
                            cells.forEach { child("td", "text-end", it.toString()) }
                        }
                    }
                }
            }
        }
    }

    /** The workbook-style counts matrix: one row per registered congregation, totals at the bottom. */
    private fun renderCongregationTable(
        parent: Element,
        rows: List<RegistrationDeskRowDto>,
        attendees: List<AttendeeRow>,
    ) {
        val byCongregation = attendees.groupBy { it.congregationId }
        parent.child("h4", "mt-4", "By congregation")
        parent.child("div", "table-responsive") {
            child("table", "table table-sm align-middle w-auto") {
                child("thead") {
                    child("tr") {
                        child("th", text = "Congregation")
                        if (multiSite) child("th", text = "Site")
                        Division.entries.forEach { child("th", "text-end", it.displayName) }
                        child("th", "text-end", "Testers")
                        child("th", "text-end", "Guests")
                        child("th", "text-end", "Attendees")
                    }
                }
                child("tbody") {
                    rows.filter { it.registration != null }.forEach { row ->
                        val a = byCongregation[row.congregation.id].orEmpty()
                        child("tr") {
                            child("td", text = row.congregation.name)
                            if (multiSite) {
                                child("td", text = Session.season.siteFor(row.registration?.siteId)?.name ?: "—")
                            }
                            countCells(this, a)
                        }
                    }
                }
                child("tfoot") {
                    child("tr", "fw-semibold") {
                        child("td", text = "All congregations")
                        if (multiSite) child("td", text = "")
                        countCells(this, attendees)
                    }
                }
            }
        }
    }

    /** The division/tester/guest count cells shared by the congregation rows and the totals row. */
    private fun countCells(tr: Element, attendees: List<AttendeeRow>) {
        Division.entries.forEach { d ->
            tr.child("td", "text-end", attendees.count { it.division == d }.toString())
        }
        tr.child("td", "text-end", attendees.count { it.tester }.toString())
        tr.child("td", "text-end", attendees.count { !it.tester }.toString())
        tr.child("td", "text-end", attendees.size.toString())
    }

    private fun renderGender(parent: Element, attendees: List<AttendeeRow>) {
        parent.child("h4", "mt-4", "By gender")
        val unspecified = attendees.any { it.gender == null }
        fun cells(group: List<AttendeeRow>) = buildList {
            add(group.count { it.gender == Gender.MALE })
            add(group.count { it.gender == Gender.FEMALE })
            if (unspecified) add(group.count { it.gender == null })
            add(group.size)
        }
        countsTable(
            parent,
            headers = listOfNotNull("", "Male", "Female", "Unspecified".takeIf { unspecified }, "Total"),
            rows = listOf(
                "Testers" to cells(attendees.filter { it.tester }),
                "Guests" to cells(attendees.filter { !it.tester }),
                "All attendees" to cells(attendees),
            ),
        )
    }

    private fun renderGradesAndAges(parent: Element, attendees: List<AttendeeRow>) {
        parent.child("h4", "mt-4", "By grade & age group")
        val testers = attendees.filter { it.tester }
        countsTable(
            parent,
            headers = listOf("") + GRADES.map { "Grade $it" } + "Adult",
            rows = listOf(
                "Testers" to GRADES.map { g -> testers.count { it.grade == g } } +
                    testers.count { it.division == Division.ADULT },
            ),
        )
        val guests = attendees.filter { !it.tester }
        countsTable(
            parent,
            headers = listOf("") + AgeTier.entries.map { it.displayName },
            rows = listOf("Guests" to AgeTier.entries.map { tier -> guests.count { it.ageTier == tier } }),
        )
    }

    /**
     * Testers per division split by experience bracket (item 4: individuals rank in their own
     * bracket, so each tester counts at their own division and experience), plus the season's
     * non-empty teams per bracket — the Adult division has neither teams nor an experience split.
     */
    private fun renderDivisions(
        parent: Element,
        rows: List<RegistrationDeskRowDto>,
        attendees: List<AttendeeRow>,
    ) {
        parent.child("h4", "mt-4", "By division & experience")
        val testers = attendees.filter { it.tester }
        countsTable(
            parent,
            headers = listOf("", "Experienced", "Inexperienced", "Testers"),
            rows = Division.entries.filter { it != Division.ADULT }.map { d ->
                val inDivision = testers.filter { it.division == d }
                d.displayName to listOf(
                    inDivision.count { !it.inexperienced },
                    inDivision.count { it.inexperienced },
                    inDivision.size,
                )
            } + ("Adult" to listOf(0, 0, testers.count { it.division == Division.ADULT })),
        )

        val seasonYear = data?.seasonYear ?: return
        val teams = rows.mapNotNull { it.registration }.flatMap { it.teams }.filter { it.members.isNotEmpty() }
        if (teams.isEmpty()) return
        parent.child("h6", "mt-2", "Teams")
        countsTable(
            parent,
            headers = listOf("", "Experienced", "Inexperienced", "Teams"),
            rows = listOf(Division.JUNIOR, Division.SENIOR).map { d ->
                val inDivision = teams.filter { it.division(Session.season) == d }
                d.displayName to listOf(
                    inDivision.count { !it.isInexperienced(seasonYear) },
                    inDivision.count { it.isInexperienced(seasonYear) },
                    inDivision.size,
                )
            },
        )
    }

    private fun renderGraduatingSeniors(parent: Element, attendees: List<AttendeeRow>) {
        val seniors = attendees.graduatingSeniors().sortedBy { it.name.lowercase() }
        parent.child("h4", "mt-4") {
            append("Graduating seniors ")
            child("span", "badge text-bg-info", seniors.size.toString())
        }
        parent.child("p", "text-muted small mb-1", "Grade-12 contestants this season, derived from birthdates.")
        if (seniors.isEmpty()) {
            parent.child("p", "text-muted", "None yet.")
            return
        }
        seniors.forEach { parent.child("div", "small", "${it.name} — ${it.congregationName}") }
    }

    // --- CSV export -------------------------------------------------------------------------

    /** The by-congregation matrix (respecting the site filter), totals row last. */
    private fun countsCsv(desk: RegistrationDeskResponse): String {
        val rows = desk.rows.filter { row ->
            siteFilter == null || Session.season.siteFor(row.registration?.siteId)?.id == siteFilter
        }
        val attendees = deskAttendees(Session.season, rows)
        val byCongregation = attendees.groupBy { it.congregationId }
        val header = listOfNotNull("Congregation", "Site".takeIf { multiSite }) +
            Division.entries.map { it.displayName } + listOf("Testers", "Guests", "Attendees")
        fun counts(a: List<AttendeeRow>): List<String> =
            (Division.entries.map { d -> a.count { it.division == d } } +
                listOf(a.count { it.tester }, a.count { !it.tester }, a.size)).map { it.toString() }
        val body = rows.filter { it.registration != null }.map { row ->
            listOfNotNull(
                row.congregation.name,
                (Session.season.siteFor(row.registration?.siteId)?.name ?: "").takeIf { multiSite },
            ) + counts(byCongregation[row.congregation.id].orEmpty())
        }
        val total = listOfNotNull("TOTAL", "".takeIf { multiSite }) + counts(attendees)
        return csvText(listOf(header) + body + listOf(total))
    }
}
