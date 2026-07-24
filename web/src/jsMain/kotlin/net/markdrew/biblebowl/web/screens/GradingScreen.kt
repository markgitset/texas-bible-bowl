package net.markdrew.biblebowl.web.screens

import kotlinx.coroutines.launch
import net.markdrew.biblebowl.api.Division
import net.markdrew.biblebowl.api.GradingSheetResponse
import net.markdrew.biblebowl.api.ImportIssueDto
import net.markdrew.biblebowl.api.ImportScoresReport
import net.markdrew.biblebowl.api.Permission
import net.markdrew.biblebowl.api.ScoreEntryDto
import net.markdrew.biblebowl.api.ScoreRowDto
import net.markdrew.biblebowl.api.divisionLabel
import net.markdrew.biblebowl.api.hasEventWidePermission
import net.markdrew.biblebowl.api.rounds
import net.markdrew.biblebowl.api.totalPoints
import net.markdrew.biblebowl.model.Round
import net.markdrew.biblebowl.web.Routes
import net.markdrew.biblebowl.web.Session
import net.markdrew.biblebowl.web.Shell
import net.markdrew.biblebowl.web.child
import net.markdrew.biblebowl.web.clear
import net.markdrew.biblebowl.web.errorLine
import net.markdrew.biblebowl.web.onClick
import net.markdrew.biblebowl.web.spinner
import org.w3c.dom.Element
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.HTMLTextAreaElement

/**
 * Grading desk (docs/gui-redesign.md §5F): pick a round, enter every contestant's points in a
 * tab-through grid, save the batch, and — for SCORE_RELEASE holders — flip the season's release
 * switch. Route-gated (and server-enforced) on an event-wide SCORE_ENTER grant. Ineligible cells
 * (Power Round for Elementary contestants) render as dashes, mirroring the server's validation.
 */
object GradingScreen {

    /** All six rounds in test-day order (rounds 1–5, then Power). */
    private val allRounds: List<Round> = Division.ADULT.rounds

    private var sheet: GradingSheetResponse? = null
    private var round: Round = allRounds.first()
    private var message: String? = null
    private var messageIsError: Boolean = false
    /** Sort the grid by tester ID (the order the hand-graded paper stack is in) rather than desk order. */
    private var sortByTesterId: Boolean = false
    /** Narrows the grid to one event site (a multi-site season); null = all sites. */
    private var siteFilter: String? = null
    /** Whether the ZipGrade import panel is open, and the last import's reconciliation report. */
    private var showImport: Boolean = false
    private var importReport: ImportScoresReport? = null
    private val inputs = mutableListOf<Pair<String, HTMLInputElement>>() // rosterEntryId → cell input
    private val rowByTesterId = mutableMapOf<Int, HTMLElement>() // tester ID → its <tr>, for jump-to-ID
    private val inputByTesterId = mutableMapOf<Int, HTMLInputElement>() // tester ID → its score cell
    private lateinit var content: HTMLElement

    fun render(container: HTMLElement) {
        container.child("h1", "page-title", "Grading")
        content = container.child("div")
        message = null
        siteFilter = null
        reload()
    }

    /** (Re)fetches the sheet for the current [siteFilter] and re-renders. */
    private fun reload() {
        content.clear()
        content.spinner()
        sheet = null
        Shell.scope.launch {
            try {
                sheet = Session.api.gradingSheet(siteFilter)
                renderContent()
            } catch (e: Throwable) {
                content.clear()
                content.errorLine("Could not load the grading sheet: ${e.message}")
            }
        }
    }

    private fun renderContent() {
        val data = sheet ?: return
        content.clear()
        inputs.clear()
        rowByTesterId.clear()
        inputByTesterId.clear()

        renderReleaseBar(content, data)
        renderImport(content)
        message?.let {
            if (messageIsError) content.errorLine(it)
            else content.child("p", "tbb-gold fw-semibold mb-2", it)
        }

        if (data.rows.isEmpty()) {
            content.child("p", "text-muted", "No contestants registered for season ${data.seasonYear} yet.")
            return
        }

        content.child("div", "d-flex flex-wrap align-items-end gap-3 mb-3") {
            child("div") {
                child("label", "form-label mb-1", "Round")
                val select = child("select", "form-select") as HTMLSelectElement
                allRounds.forEach { r ->
                    select.child("option", text = "${roundLabel(r)} — ${r.displayName} (max ${r.maxPoints})") {
                        setAttribute("value", r.name)
                        if (r == round) setAttribute("selected", "selected")
                    }
                }
                select.addEventListener("change", {
                    round = Round.valueOf(select.value)
                    message = null
                    renderContent()
                })
            }
            child("div") {
                child("label", "form-label mb-1", "Sort by")
                val select = child("select", "form-select") as HTMLSelectElement
                listOf(false to "Desk order", true to "Tester ID").forEach { (byId, label) ->
                    select.child("option", text = label) {
                        setAttribute("value", byId.toString())
                        if (byId == sortByTesterId) setAttribute("selected", "selected")
                    }
                }
                select.addEventListener("change", {
                    sortByTesterId = select.value.toBoolean()
                    renderContent()
                })
            }
            // Site filter — only meaningful in a multi-site season; graders scope to their stack.
            if (data.releases.any { it.siteId.isNotEmpty() }) {
                child("div") {
                    child("label", "form-label mb-1", "Site")
                    val select = child("select", "form-select") as HTMLSelectElement
                    select.child("option", text = "All sites") {
                        setAttribute("value", "")
                        if (siteFilter == null) setAttribute("selected", "selected")
                    }
                    data.releases.forEach { s ->
                        select.child("option", text = s.siteName.ifBlank { s.siteId }) {
                            setAttribute("value", s.siteId)
                            if (siteFilter == s.siteId) setAttribute("selected", "selected")
                        }
                    }
                    select.addEventListener("change", {
                        siteFilter = select.value.ifEmpty { null }
                        message = null
                        reload()
                    })
                }
            }
            child("div") {
                child("label", "form-label mb-1") {
                    setAttribute("for", "grading-jump")
                    append("Jump to ID")
                }
                val jump = child("input", "form-control") as HTMLInputElement
                jump.id = "grading-jump"
                jump.type = "number"
                jump.setAttribute("min", "1")
                jump.setAttribute("inputmode", "numeric")
                jump.setAttribute("placeholder", "#")
                jump.style.maxWidth = "6rem"
                // Enter jumps to (and focuses) that tester's score cell — the grader types the ID off
                // the paper in front of them and lands on the right row without scrolling.
                jump.addEventListener("keydown", { e ->
                    if ((e as org.w3c.dom.events.KeyboardEvent).key == "Enter") {
                        e.preventDefault()
                        jumpToTester(jump.value.trim().toIntOrNull())
                    }
                })
            }
            child("span", "text-muted mb-2", "Season ${data.seasonYear} · ${data.rows.size} contestants")
        }

        val rows = if (sortByTesterId) {
            data.rows.sortedWith(compareBy({ it.testerId == null }, { it.testerId }))
        } else {
            data.rows
        }
        content.child("div", "table-responsive") {
            child("table", "table table-hover align-middle") {
                child("thead") {
                    child("tr") {
                        listOf("ID", "Congregation", "Team", "Contestant", "Division").forEach { child("th", text = it) }
                        child("th", text = "${roundLabel(round)} points (0–${round.maxPoints})")
                        child("th", text = "Total")
                    }
                }
                val tbody = child("tbody")
                rows.forEach { row -> renderRow(tbody, row) }
            }
        }

        val save = content.child("button", "btn btn-primary", "Save ${roundLabel(round)} scores") {
            setAttribute("type", "button")
        } as HTMLButtonElement
        save.onClick { saveRound(save) }
    }

    private fun renderRow(tbody: Element, row: ScoreRowDto) {
        tbody.child("tr") {
            row.testerId?.let { rowByTesterId[it] = this }
            child("td", "fw-semibold") {
                if (row.testerId != null) {
                    append(row.testerId.toString())
                    // The full ZipGrade external ID is a tooltip so the column stays a scannable number.
                    row.externalId?.let { setAttribute("title", it) }
                } else {
                    child("span", "text-muted", "—")
                }
            }
            child("td", text = row.congregationName)
            child("td") {
                if (row.teamName != null) append(row.teamName!!)
                else child("span", "text-muted", "Individual")
            }
            child("td", "fw-semibold", row.contestantName)
            child("td") {
                val division = row.division
                if (division == null) {
                    child("span", "badge text-bg-secondary", "Unknown")
                } else {
                    child("span", "badge text-bg-primary", divisionLabel(division, row.inexperienced))
                }
                // A team can compete above a member's own bracket — surface the elevated one too.
                val teamDivision = row.teamDivision
                if (teamDivision != null &&
                    (teamDivision != division || row.teamInexperienced != row.inexperienced)
                ) {
                    child(
                        "span", "badge text-bg-secondary ms-1",
                        "Team: ${divisionLabel(teamDivision, row.teamInexperienced)}",
                    )
                }
            }
            child("td") {
                if (row.takes(round)) {
                    val input = child("input", "form-control form-control-sm") as HTMLInputElement
                    input.type = "number"
                    input.setAttribute("min", "0")
                    input.setAttribute("max", round.maxPoints.toString())
                    input.setAttribute("step", "1")
                    input.setAttribute("inputmode", "numeric")
                    input.style.maxWidth = "6rem"
                    input.value = row.scores[round]?.toString() ?: ""
                    inputs += row.rosterEntryId to input
                    row.testerId?.let { inputByTesterId[it] = input }
                } else {
                    child("span", "text-muted", "—") {
                        setAttribute("title", "${row.division?.displayName} contestants don't take this round")
                    }
                }
            }
            child("td", text = if (row.scores.isEmpty()) "—" else row.totalPoints.toString())
        }
    }

    /** Whether this contestant takes [r] — unknown divisions grade everything (server allows it). */
    private fun ScoreRowDto.takes(r: Round): Boolean = division?.let { r in it.rounds } ?: true

    /**
     * Scrolls the tester with [testerId] into view and focuses their score cell (or the row, when the
     * contestant doesn't take the selected round). No-op with a brief message when the ID isn't found.
     */
    private fun jumpToTester(testerId: Int?) {
        if (testerId == null) return
        val input = inputByTesterId[testerId]
        val row = rowByTesterId[testerId]
        when {
            input != null -> {
                input.focus() // focusing scrolls the cell into view
                input.select()
            }
            row != null -> row.scrollIntoView()
            else -> {
                message = "No tester #$testerId on this sheet."
                messageIsError = true
                renderContent()
            }
        }
    }

    /** Collects the changed cells of the selected round and batch-saves them. */
    private fun saveRound(save: HTMLButtonElement) {
        val data = sheet ?: return
        val edits = mutableListOf<ScoreEntryDto>()
        for ((entryId, input) in inputs) {
            val existing = data.rows.first { it.rosterEntryId == entryId }.scores[round]
            val text = input.value.trim()
            if (text.isEmpty()) {
                if (existing != null) edits += ScoreEntryDto(entryId, round, null)
                continue
            }
            val points = text.toIntOrNull()
            if (points == null || points !in 0..round.maxPoints) {
                message = "Scores must be whole numbers from 0 to ${round.maxPoints}."
                messageIsError = true
                renderContent()
                return
            }
            if (points != existing) edits += ScoreEntryDto(entryId, round, points)
        }
        if (edits.isEmpty()) {
            message = "Nothing to save — no ${roundLabel(round)} cells changed."
            messageIsError = false
            renderContent()
            return
        }
        save.disabled = true
        Shell.scope.launch {
            try {
                sheet = Session.api.saveScores(edits)
                message = "Saved ${edits.size} ${roundLabel(round)} score(s)."
                messageIsError = false
            } catch (e: Throwable) {
                message = "Save failed: ${e.message}"
                messageIsError = true
            }
            renderContent()
        }
    }

    /**
     * ZipGrade import: a toggle, a paste box / file picker, and — after applying — the
     * reconciliation report. Any desk viewer holds SCORE_ENTER (server-gated), so no extra gate.
     */
    private fun renderImport(parent: Element) {
        parent.child("div", "mb-3") {
            child("button", "btn btn-outline-secondary btn-sm", if (showImport) "Hide import" else "Import ZipGrade CSV") {
                setAttribute("type", "button")
            }.let { (it as HTMLButtonElement).onClick { showImport = !showImport; renderContent() } }

            if (showImport) child("div", "card card-body mt-2") {
                child("p", "small text-muted mb-2", "Paste a ZipGrade CSV export or choose a file, then Apply. " +
                    "Scores match by tester ID and update in place — re-import a fresh export anytime.")
                val textarea = child("textarea", "form-control mb-2") {
                    setAttribute("rows", "4")
                    setAttribute("placeholder", "Quiz Name,Student First Name,Student Last Name,Student ID,Earned Points…")
                } as HTMLTextAreaElement
                val file = child("input", "form-control form-control-sm mb-2") as HTMLInputElement
                file.type = "file"
                file.setAttribute("accept", ".csv,text/csv")
                file.addEventListener("change", {
                    val f = file.files?.item(0)
                    if (f != null) (f.asDynamic().text() as kotlin.js.Promise<String>).then { textarea.value = it }
                })
                val apply = child("button", "btn btn-primary btn-sm", "Apply import") {
                    setAttribute("type", "button")
                } as HTMLButtonElement
                apply.onClick {
                    val csv = textarea.value
                    if (csv.isBlank()) {
                        message = "Paste or choose a ZipGrade CSV first."; messageIsError = true; renderContent()
                    } else {
                        apply.disabled = true
                        Shell.scope.launch {
                            try {
                                importReport = Session.api.importScores(csv)
                                message = "Imported ${importReport!!.appliedTotal} score(s)."
                                messageIsError = false
                                reload() // refresh the grid; renderContent re-shows the report
                            } catch (e: Throwable) {
                                message = "Import failed: ${e.message}"; messageIsError = true; renderContent()
                            }
                        }
                    }
                }
            }

            importReport?.let { renderImportReport(this, it) }
        }
    }

    private fun renderImportReport(parent: Element, report: ImportScoresReport) {
        parent.child("div", "mt-2") {
            val applied = report.appliedByRound.entries.joinToString(", ") { "${roundLabel(it.key)}: ${it.value}" }
            child("p", "mb-1 fw-semibold", "Applied ${report.appliedTotal} score(s)${if (applied.isBlank()) "" else " ($applied)"}.")
            issueList(this, "Unknown IDs (skipped — likely another site's scans)", report.unknownIds)
            issueList(this, "Name mismatches (applied by ID — verify)", report.nameMismatches)
            issueList(this, "Duplicate scans (last value kept)", report.duplicates)
            issueList(this, "Skipped (unreadable / out of range / round not taken)", report.skipped)
        }
    }

    private fun issueList(parent: Element, title: String, issues: List<ImportIssueDto>) {
        if (issues.isEmpty()) return
        parent.child("details", "mb-1") {
            child("summary", "small", "$title — ${issues.size}")
            child("ul", "small text-muted mb-0") {
                issues.take(50).forEach { issue ->
                    child("li", text = listOf(issue.id, issue.name, issue.detail).filter { it.isNotBlank() }.joinToString(" · "))
                }
                if (issues.size > 50) child("li", "fst-italic", "…and ${issues.size - 50} more")
            }
        }
    }

    /**
     * Per-site release state plus — for SCORE_RELEASE holders — a release/retract switch for each
     * site (each site releases on its own clock). A site-less season shows one unlabelled switch.
     */
    private fun renderReleaseBar(parent: Element, data: GradingSheetResponse) {
        val user = Session.user
        parent.child("div", "d-flex flex-wrap align-items-center gap-3 mb-3") {
            data.releases.forEach { site ->
                child("div", "d-flex align-items-center gap-2") {
                    if (site.siteName.isNotBlank()) child("span", "fw-semibold", site.siteName)
                    if (site.releasedAt != null) {
                        child("span", "badge text-bg-success", "Released ${site.releasedAt!!.take(10)}")
                    } else {
                        child("span", "badge text-bg-secondary", "Not released")
                    }
                    if (user != null && hasEventWidePermission(user.roles, Permission.SCORE_RELEASE)) {
                        val releasing = site.releasedAt == null
                        val button = child(
                            "button",
                            if (releasing) "btn btn-warning btn-sm fw-bold" else "btn btn-outline-secondary btn-sm",
                            if (releasing) "Release" else "Retract",
                        ) { setAttribute("type", "button") } as HTMLButtonElement
                        button.onClick { releaseSite(button, site.siteId, site.siteName, releasing) }
                    }
                }
            }
            child("span", "text-muted small", "Each site's contestants and coaches see scores once that site is released.")
            if (user != null && hasEventWidePermission(user.roles, Permission.SCORE_VIEW_ALL)) {
                child("a", "btn btn-outline-primary btn-sm", "Standings") {
                    setAttribute("href", "#${Routes.STANDINGS}")
                }
            }
        }
    }

    private fun releaseSite(button: HTMLButtonElement, siteId: String, siteName: String, releasing: Boolean) {
        button.disabled = true
        val label = siteName.ifBlank { "scores" }
        Shell.scope.launch {
            try {
                sheet = Session.api.setScoresReleased(releasing, siteId.ifEmpty { null })
                message = if (releasing) "Released $label." else "Retracted $label."
                messageIsError = false
            } catch (e: Throwable) {
                message = "Could not update the release: ${e.message}"
                messageIsError = true
            }
            renderContent()
        }
    }

    /** Short round label: "R1"–"R5", or "Power". */
    private fun roundLabel(r: Round): String = if (r == Round.POWER) "Power" else "R${r.number}"
}
