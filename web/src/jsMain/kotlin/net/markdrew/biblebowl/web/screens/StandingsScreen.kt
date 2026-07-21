package net.markdrew.biblebowl.web.screens

import kotlinx.coroutines.launch
import net.markdrew.biblebowl.api.DivisionStandingsDto
import net.markdrew.biblebowl.api.StandingRowDto
import net.markdrew.biblebowl.api.StandingsResponse
import net.markdrew.biblebowl.api.divisionLabel
import net.markdrew.biblebowl.api.ordinal
import net.markdrew.biblebowl.web.Routes
import net.markdrew.biblebowl.web.Session
import net.markdrew.biblebowl.web.Shell
import net.markdrew.biblebowl.web.child
import net.markdrew.biblebowl.web.clear
import net.markdrew.biblebowl.web.errorLine
import net.markdrew.biblebowl.web.spinner
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement

/**
 * Standings — the division tally (docs/gui-redesign.md §5F): every division bracket ranked as
 * grading progresses (ungraded rounds count 0). Route-gated (and server-enforced) on event-wide
 * SCORE_VIEW_ALL, the same graders/admins who run the grading desk; this is a desk tool, not a
 * public results page — the public only ever sees the curated champions page.
 */
object StandingsScreen {

    fun render(container: HTMLElement) {
        container.child("h1", "page-title", "Standings")
        val content = container.child("div")
        content.spinner()
        Shell.scope.launch {
            try {
                val data = Session.api.standings()
                content.clear()
                renderContent(content, data)
            } catch (e: Throwable) {
                content.clear()
                content.errorLine("Could not load standings: ${e.message}")
            }
        }
    }

    private fun renderContent(content: HTMLElement, data: StandingsResponse) {
        content.child("div", "d-flex flex-wrap align-items-center gap-3 mb-3") {
            child("span", "text-muted", "Season ${data.seasonYear} · updates as grading progresses")
            if (data.releasedAt != null) {
                child("span", "badge text-bg-success", "Released ${data.releasedAt!!.take(10)}")
            } else {
                child("span", "badge text-bg-secondary", "Not released")
            }
            child("a", "btn btn-outline-primary btn-sm", "Grading desk") {
                setAttribute("href", "#${Routes.GRADING}")
            }
        }

        if (data.divisions.isEmpty()) {
            content.child("p", "text-muted", "No contestants registered yet — nothing to rank.")
            return
        }

        data.divisions.forEach { bracket -> renderBracket(content, bracket) }
    }

    private fun renderBracket(content: HTMLElement, bracket: DivisionStandingsDto) {
        content.child("h3", "mt-4", divisionLabel(bracket.division, bracket.inexperienced))
        content.child("div", "row g-4") {
            child("div", "col-lg-7") {
                // A bracket can hold only teams (members individually bracketed lower) — skip then.
                if (bracket.individuals.isEmpty()) return@child
                standingsTable(
                    this, "Individuals",
                    headers = listOf("Place", "Contestant", "Congregation", "Team", "Points"),
                    rows = bracket.individuals,
                ) { row ->
                    listOf(
                        ordinal(row.rank), row.name, row.congregationName,
                        row.teamName ?: "—", "${row.points} / ${row.maxPoints}",
                    )
                }
            }
            child("div", "col-lg-5") {
                if (bracket.teams.isEmpty()) return@child
                standingsTable(
                    this, "Teams",
                    headers = listOf("Place", "Team", "Congregation", "Points"),
                    rows = bracket.teams,
                ) { row ->
                    listOf(ordinal(row.rank), row.name, row.congregationName, "${row.points} / ${row.maxPoints}")
                }
            }
        }
    }

    private fun standingsTable(
        parent: Element,
        title: String,
        headers: List<String>,
        rows: List<StandingRowDto>,
        cells: (StandingRowDto) -> List<String>,
    ) {
        parent.child("h6", text = title)
        parent.child("div", "table-responsive") {
            child("table", "table table-sm table-hover align-middle") {
                child("thead") {
                    child("tr") { headers.forEach { child("th", text = it) } }
                }
                child("tbody") {
                    rows.forEach { row ->
                        child("tr") {
                            cells(row).forEachIndexed { i, cell ->
                                child("td", if (i == 0) "fw-semibold" else "", cell)
                            }
                        }
                    }
                }
            }
        }
    }
}
