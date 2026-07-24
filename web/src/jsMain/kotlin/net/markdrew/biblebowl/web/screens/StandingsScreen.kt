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
import net.markdrew.biblebowl.web.downloadBlob
import net.markdrew.biblebowl.web.errorLine
import net.markdrew.biblebowl.web.onClick
import net.markdrew.biblebowl.web.spinner
import org.w3c.dom.Element
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.files.Blob
import org.w3c.files.BlobPropertyBag

/**
 * Standings — the division tally (docs/gui-redesign.md §5F): every division bracket ranked as
 * grading progresses (ungraded rounds count 0). Route-gated (and server-enforced) on event-wide
 * SCORE_VIEW_ALL, the same graders/admins who run the grading desk; this is a desk tool, not a
 * public results page — the public only ever sees the curated champions page. Doubles as the
 * ceremony view (G5): a top-N knob, a reverse-announcement-order toggle, team member lists, and a
 * printable awards booklet.
 */
object StandingsScreen {

    private var data: StandingsResponse? = null
    private var message: String? = null
    /** Ceremony controls: show only the top N of each bracket, and read them Nth-first. */
    private var topN: Int = 10
    private var reverseOrder: Boolean = false
    private lateinit var content: HTMLElement

    fun render(container: HTMLElement) {
        container.child("h1", "page-title", "Standings")
        content = container.child("div")
        content.spinner()
        Shell.scope.launch {
            try {
                data = Session.api.standings()
                renderContent()
            } catch (e: Throwable) {
                content.clear()
                content.errorLine("Could not load standings: ${e.message}")
            }
        }
    }

    private fun renderContent() {
        val data = data ?: return
        content.clear()
        content.child("div", "d-flex flex-wrap align-items-center gap-3 mb-3") {
            child("span", "text-muted", "Season ${data.seasonYear} · updates as grading progresses")
            // One release badge per site (each releases on its own clock); site-less season shows one.
            data.releases.forEach { site ->
                val prefix = site.siteName.takeIf { it.isNotBlank() }?.let { "$it: " } ?: ""
                if (site.releasedAt != null) {
                    child("span", "badge text-bg-success", "${prefix}Released ${site.releasedAt!!.take(10)}")
                } else {
                    child("span", "badge text-bg-secondary", "${prefix}Not released")
                }
            }
            child("a", "btn btn-outline-primary btn-sm", "Grading desk") {
                setAttribute("href", "#${Routes.GRADING}")
            }
        }

        if (data.divisions.isEmpty()) {
            content.child("p", "text-muted", "No contestants registered yet — nothing to rank.")
            return
        }

        renderCeremonyControls()
        message?.let { content.child("p", "text-danger small", it) }
        data.divisions.forEach { bracket -> renderBracket(content, bracket) }
    }

    /** Ceremony knobs: top-N, reverse order (Nth read first), and the awards-booklet download. */
    private fun renderCeremonyControls() {
        content.child("div", "d-flex flex-wrap align-items-end gap-3 mb-3") {
            child("div") {
                child("label", "form-label mb-1", "Show top")
                val input = child("input", "form-control form-control-sm") as HTMLInputElement
                input.type = "number"
                input.setAttribute("min", "1")
                input.value = topN.toString()
                input.style.maxWidth = "5rem"
                input.addEventListener("change", {
                    topN = input.value.toIntOrNull()?.coerceIn(1, 100) ?: 10
                    renderContent()
                })
            }
            child("div", "form-check") {
                val check = child("input", "form-check-input") as HTMLInputElement
                check.type = "checkbox"
                check.id = "reverse-order"
                check.checked = reverseOrder
                check.addEventListener("change", { reverseOrder = check.checked; renderContent() })
                child("label", "form-check-label small") {
                    setAttribute("for", "reverse-order")
                    append("Ceremony order (10th → 1st)")
                }
            }
            val download = child("button", "btn btn-outline-primary btn-sm", "Download awards PDF") {
                setAttribute("type", "button")
            } as HTMLButtonElement
            download.onClick { downloadAwards(download) }
        }
    }

    private fun downloadAwards(button: HTMLButtonElement) {
        button.disabled = true
        message = null
        Shell.scope.launch {
            try {
                val bytes = Session.api.awardsPdf(topN = topN)
                downloadBlob(
                    Blob(arrayOf<dynamic>(bytes), BlobPropertyBag(type = "application/pdf")),
                    "tbb-awards-${data?.seasonYear}.pdf",
                )
            } catch (e: Throwable) {
                message = "Could not generate the awards PDF: ${e.message}"
            }
            renderContent()
        }
    }

    /** The top-N of [rows], in ceremony (reverse) order when toggled. */
    private fun ceremonyRows(rows: List<StandingRowDto>): List<StandingRowDto> =
        rows.filter { it.rank <= topN }.let { if (reverseOrder) it.sortedByDescending { r -> r.rank } else it }

    private fun renderBracket(content: HTMLElement, bracket: DivisionStandingsDto) {
        // Brackets are per-site: label with the site when there is one ("Bandina · Junior").
        val label = listOfNotNull(
            bracket.siteName.takeIf { it.isNotBlank() },
            divisionLabel(bracket.division, bracket.inexperienced),
        ).joinToString(" · ")
        content.child("h3", "mt-4", label)
        content.child("div", "row g-4") {
            child("div", "col-lg-7") {
                val individuals = ceremonyRows(bracket.individuals)
                // A bracket can hold only teams (members individually bracketed lower) — skip then.
                if (individuals.isEmpty()) return@child
                standingsTable(
                    this, "Individuals",
                    headers = listOf("Place", "Contestant", "Congregation", "Team", "Points"),
                    rows = individuals,
                ) { row ->
                    listOf(
                        ordinal(row.rank), row.name, row.congregationName,
                        row.teamName ?: "—", "${row.points} / ${row.maxPoints}",
                    )
                }
            }
            child("div", "col-lg-5") {
                val teams = ceremonyRows(bracket.teams)
                if (teams.isEmpty()) return@child
                standingsTable(
                    this, "Teams",
                    headers = listOf("Place", "Team", "Members", "Points"),
                    rows = teams,
                ) { row ->
                    listOf(
                        ordinal(row.rank), row.name,
                        row.members.joinToString(", ").ifBlank { row.congregationName },
                        "${row.points} / ${row.maxPoints}",
                    )
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
