package net.markdrew.biblebowl.web.screens

import kotlinx.coroutines.launch
import net.markdrew.biblebowl.api.Division
import net.markdrew.biblebowl.api.GradingSheetResponse
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
    private val inputs = mutableListOf<Pair<String, HTMLInputElement>>() // rosterEntryId → cell input
    private lateinit var content: HTMLElement

    fun render(container: HTMLElement) {
        container.child("h1", "page-title", "Grading")
        content = container.child("div")
        content.spinner()
        sheet = null
        message = null
        Shell.scope.launch {
            try {
                sheet = Session.api.gradingSheet()
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

        renderReleaseBar(content, data)
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
            child("span", "text-muted mb-2", "Season ${data.seasonYear} · ${data.rows.size} contestants")
        }

        content.child("div", "table-responsive") {
            child("table", "table table-hover align-middle") {
                child("thead") {
                    child("tr") {
                        listOf("Congregation", "Team", "Contestant", "Division").forEach { child("th", text = it) }
                        child("th", text = "${roundLabel(round)} points (0–${round.maxPoints})")
                        child("th", text = "Total")
                    }
                }
                val tbody = child("tbody")
                data.rows.forEach { row -> renderRow(tbody, row) }
            }
        }

        val save = content.child("button", "btn btn-primary", "Save ${roundLabel(round)} scores") {
            setAttribute("type", "button")
        } as HTMLButtonElement
        save.onClick { saveRound(save) }
    }

    private fun renderRow(tbody: Element, row: ScoreRowDto) {
        tbody.child("tr") {
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

    /** The released state plus — for SCORE_RELEASE holders — the release/retract switch. */
    private fun renderReleaseBar(parent: Element, data: GradingSheetResponse) {
        parent.child("div", "d-flex flex-wrap align-items-center gap-3 mb-3") {
            if (data.releasedAt != null) {
                child("span", "badge text-bg-success", "Released ${data.releasedAt!!.take(10)}")
                child("span", "text-muted small", "Contestants and coaches can see their scores.")
            } else {
                child("span", "badge text-bg-secondary", "Not released")
                child("span", "text-muted small", "Nothing is visible outside this desk until release.")
            }
            val user = Session.user
            if (user != null && hasEventWidePermission(user.roles, Permission.SCORE_VIEW_ALL)) {
                child("a", "btn btn-outline-primary btn-sm", "Standings") {
                    setAttribute("href", "#${Routes.STANDINGS}")
                }
            }
            if (user != null && hasEventWidePermission(user.roles, Permission.SCORE_RELEASE)) {
                val releasing = data.releasedAt == null
                val button = child(
                    "button",
                    if (releasing) "btn btn-warning btn-sm fw-bold" else "btn btn-outline-secondary btn-sm",
                    if (releasing) "Release scores" else "Retract release",
                ) { setAttribute("type", "button") } as HTMLButtonElement
                button.onClick {
                    button.disabled = true
                    Shell.scope.launch {
                        try {
                            sheet = Session.api.setScoresReleased(releasing)
                            message = if (releasing) "Scores released." else "Release retracted."
                            messageIsError = false
                        } catch (e: Throwable) {
                            message = "Could not update the release: ${e.message}"
                            messageIsError = true
                        }
                        renderContent()
                    }
                }
            }
        }
    }

    /** Short round label: "R1"–"R5", or "Power". */
    private fun roundLabel(r: Round): String = if (r == Round.POWER) "Power" else "R${r.number}"
}
