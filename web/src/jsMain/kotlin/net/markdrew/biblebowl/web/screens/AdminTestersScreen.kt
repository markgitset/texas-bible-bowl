package net.markdrew.biblebowl.web.screens

import kotlinx.coroutines.launch
import net.markdrew.biblebowl.api.TesterListResponse
import net.markdrew.biblebowl.api.TesterRowDto
import net.markdrew.biblebowl.api.divisionLabel
import net.markdrew.biblebowl.api.multiSite
import net.markdrew.biblebowl.api.zipGradeFirstName
import net.markdrew.biblebowl.api.zipGradeLastName
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
 * Tester IDs + ZipGrade export (registration backlog item 13, F7): every contestant this season
 * with their stable season-wide tester ID, workbook-style external ID, and class (congregation code),
 * plus the per-site ZipGrade import CSV — ZipGrade is still the primary scan-grading path for
 * 2027. Loading the screen is what assigns numbers to new testers (append-only; existing numbers
 * never change, so nametags can print early). Route-gated (and server-enforced) on event-wide
 * REGISTRATION_MANAGE or SCORE_ENTER.
 */
object AdminTestersScreen {

    private var data: TesterListResponse? = null
    private var siteFilter: String? = null // EventSiteDto id, or null = all sites
    private lateinit var content: HTMLElement

    private val multiSite: Boolean get() = Session.season.multiSite

    fun render(container: HTMLElement) {
        container.child("h1", "page-title", "Tester IDs")
        content = container.child("div")
        content.spinner()
        data = null
        siteFilter = null
        Shell.scope.launch {
            try {
                data = Session.api.adminTesters()
                renderContent()
            } catch (e: Throwable) {
                content.clear()
                content.errorLine("Could not load the tester list: ${e.message}")
            }
        }
    }

    private fun renderContent() {
        val testers = data ?: return
        content.clear()

        if (testers.rows.isEmpty()) {
            content.child("p", "text-muted", "No contestants registered yet this season.")
            return
        }

        val rows = testers.rows.filter { siteFilter == null || it.siteId == siteFilter }
        content.child("div", "d-flex flex-wrap align-items-center gap-3 mb-3") {
            child("span", "text-muted", "Season ${testers.seasonYear} — ${rows.size} testers.")
            if (multiSite) siteFilterSelect(this)
            // ZipGrade imports one roster per site, so the export follows the site filter.
            val exportSite = siteFilter?.let { id -> Session.season.sites.firstOrNull { it.id == id } }
            val siteSlug = exportSite?.name?.lowercase()?.replace(Regex("\\W+"), "-") ?: "all-sites"
            child("button", "btn btn-outline-primary btn-sm", "Download ZipGrade CSV") {
                setAttribute("type", "button")
                onClick { downloadCsv("tbb-zipgrade-${testers.seasonYear}-$siteSlug.csv", zipGradeCsv(rows)) }
            }
        }
        warnings(rows)

        content.child("div", "table-responsive") {
            child("table", "table table-hover table-sm align-middle") {
                child("thead") {
                    child("tr") {
                        listOfNotNull(
                            "Tester ID", "Name", "Congregation", "Class", "Category", "Team",
                            "External ID", "Site".takeIf { multiSite },
                        ).forEach { child("th", text = it) }
                    }
                }
                child("tbody") {
                    rows.forEach { row -> renderRow(this, row) }
                }
            }
        }
    }

    private fun renderRow(tbody: Element, row: TesterRowDto) {
        tbody.child("tr") {
            child("td") {
                if (row.testerId != null) child("span", "fw-semibold", row.testerId.toString())
                else child("span", "text-muted", "—")
            }
            child("td", text = row.name)
            child("td", text = row.congregationName)
            child("td", text = row.congregationCode.ifBlank { "—" })
            child("td", text = row.division?.let { divisionLabel(it, row.inexperienced) } ?: "—")
            child("td", text = row.teamName ?: "—")
            child("td") {
                row.externalId?.let { child("code", text = it) } ?: child("span", "text-muted", "—")
            }
            if (multiSite) child("td", text = row.siteName.ifBlank { "—" })
        }
    }

    /** Data problems a registrar should fix before exporting: missing site pins or class codes. */
    private fun warnings(rows: List<TesterRowDto>) {
        val unpinned = if (multiSite) rows.count { it.siteId == null } else 0
        if (unpinned > 0) {
            content.errorLine(
                "$unpinned tester(s) have no event site — ZipGrade rosters and nametag sheets " +
                    "are per site. Pin the site on the registration desk.",
            )
        }
        val codeless = rows.filter { it.congregationCode.isBlank() }
            .map { it.congregationName }.distinct()
        if (codeless.isNotEmpty()) {
            content.errorLine(
                "No two-letter code yet for ${codeless.joinToString()} — external IDs show ?? " +
                    "until one is set on the registration desk.",
            )
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

    /**
     * The ZipGrade student-import sheet, exactly the 2026 workbook's ZipGrade-tab columns. Rows
     * without a tester ID yet (no site pinned) are left out — they aren't importable.
     */
    private fun zipGradeCsv(rows: List<TesterRowDto>): String {
        val header = listOf("Zip Grade ID", "external id", "First Name", "Last Name", "Class")
        val body = rows.filter { it.testerId != null }.map { row ->
            listOf(
                row.testerId.toString(),
                row.externalId ?: "",
                zipGradeFirstName(row.name),
                zipGradeLastName(row.name),
                row.congregationCode,
            )
        }
        return csvText(listOf(header) + body)
    }
}
