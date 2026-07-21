package net.markdrew.biblebowl.web.screens

import kotlinx.coroutines.launch
import net.markdrew.biblebowl.api.Division
import net.markdrew.biblebowl.api.MyScoresResponse
import net.markdrew.biblebowl.api.ScoreRowDto
import net.markdrew.biblebowl.api.divisionLabel
import net.markdrew.biblebowl.api.maxScore
import net.markdrew.biblebowl.api.ordinal
import net.markdrew.biblebowl.api.rounds
import net.markdrew.biblebowl.api.totalPoints
import net.markdrew.biblebowl.model.Round
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
 * My Scores (docs/gui-redesign.md §5F): the released scores of every contestant the signed-in
 * user owns (claimed entries) or coaches (their congregations' rosters). The server returns
 * nothing until a SCORE_RELEASE holder releases the season, and this screen says so plainly.
 */
object MyScoresScreen {

    /** All six rounds in test-day order (rounds 1–5, then Power). */
    private val allRounds: List<Round> = Division.ADULT.rounds

    fun render(container: HTMLElement) {
        container.child("h1", "page-title", "My scores")
        val content = container.child("div")
        content.spinner()
        Shell.scope.launch {
            try {
                val data = Session.api.myScores()
                content.clear()
                renderContent(content, data)
            } catch (e: Throwable) {
                content.clear()
                content.errorLine("Could not load scores: ${e.message}")
            }
        }
    }

    private fun renderContent(content: HTMLElement, data: MyScoresResponse) {
        if (!data.released) {
            content.child(
                "p", "fs-5",
                "Season ${data.seasonYear} scores haven't been released yet — check back after the event!",
            )
            return
        }
        if (data.rows.isEmpty()) {
            content.child(
                "p", "fs-5",
                "No contestant entries are linked to your account for season ${data.seasonYear}.",
            )
            content.child("p", "text-muted") {
                append("Contestants and parents: claim your roster entry with the code from your coach on your ")
                child("a", text = "Account page") { setAttribute("href", "#${Routes.ACCOUNT}") }
                append(". Coaches see their whole congregation here automatically.")
            }
            return
        }

        content.child("p", "text-muted", "Season ${data.seasonYear} · released scores")
        content.child("div", "table-responsive") {
            child("table", "table table-hover align-middle") {
                child("thead") {
                    child("tr") {
                        listOf("Contestant", "Congregation", "Team", "Division").forEach { child("th", text = it) }
                        allRounds.forEach { r ->
                            child("th", "text-end", roundLabel(r)) { setAttribute("title", r.displayName) }
                        }
                        child("th", "text-end", "Total")
                        child("th", text = "Placement")
                    }
                }
                val tbody = child("tbody")
                data.rows.forEach { row -> renderRow(tbody, row) }
            }
        }
    }

    private fun renderRow(tbody: Element, row: ScoreRowDto) {
        tbody.child("tr") {
            child("td", "fw-semibold", row.contestantName)
            child("td", text = row.congregationName)
            child("td") {
                if (row.teamName != null) append(row.teamName!!)
                else child("span", "text-muted", "Individual")
            }
            child("td") {
                val division = row.division
                if (division == null) child("span", "text-muted", "—")
                else child("span", "badge text-bg-primary", divisionLabel(division, row.inexperienced))
            }
            allRounds.forEach { r ->
                val takes = row.division?.let { r in it.rounds } ?: true
                val text = when {
                    !takes -> "·"
                    else -> row.scores[r]?.toString() ?: "—"
                }
                child("td", "text-end", text)
            }
            child("td", "text-end fw-semibold") {
                append(row.totalPoints.toString())
                row.division?.let { child("span", "text-muted small", " / ${it.maxScore}") }
            }
            child("td") {
                val rank = row.rank
                if (rank == null) {
                    child("span", "text-muted", "—")
                } else {
                    child("div", "fw-semibold", "${ordinal(rank)} of ${row.rankOf}")
                    val teamRank = row.teamRank
                    if (teamRank != null) {
                        // The team may compete in a higher bracket than the contestant's own.
                        val teamDivision = row.teamDivision
                        val elevated = teamDivision != null &&
                            (teamDivision != row.division || row.teamInexperienced != row.inexperienced)
                        val teamLabel =
                            if (elevated) "Team (${divisionLabel(teamDivision!!, row.teamInexperienced)})"
                            else "Team"
                        child(
                            "div", "text-muted small",
                            "$teamLabel: ${ordinal(teamRank)} of ${row.teamRankOf} · ${row.teamPoints} pts",
                        )
                    }
                }
            }
        }
    }

    /** Short round label: "R1"–"R5", or "Power". */
    private fun roundLabel(r: Round): String = if (r == Round.POWER) "Power" else "R${r.number}"
}
