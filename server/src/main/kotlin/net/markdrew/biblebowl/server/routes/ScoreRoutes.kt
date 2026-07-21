package net.markdrew.biblebowl.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import net.markdrew.biblebowl.api.ApiError
import net.markdrew.biblebowl.api.Division
import net.markdrew.biblebowl.api.GradingSheetResponse
import net.markdrew.biblebowl.api.MyScoresResponse
import net.markdrew.biblebowl.api.Permission
import net.markdrew.biblebowl.api.SaveScoresRequest
import net.markdrew.biblebowl.api.ScoreRowDto
import net.markdrew.biblebowl.api.SeasonDto
import net.markdrew.biblebowl.api.SetScoresReleasedRequest
import net.markdrew.biblebowl.api.StandingRowDto
import net.markdrew.biblebowl.api.StandingsResponse
import net.markdrew.biblebowl.api.TEAM_MEMBER_MAX_POINTS
import net.markdrew.biblebowl.api.coachedCongregationIds
import net.markdrew.biblebowl.api.division
import net.markdrew.biblebowl.api.DivisionStandingsDto
import net.markdrew.biblebowl.api.hasEventWidePermission
import net.markdrew.biblebowl.api.isInexperienced
import net.markdrew.biblebowl.api.maxScore
import net.markdrew.biblebowl.api.rounds
import net.markdrew.biblebowl.api.teamPoints
import net.markdrew.biblebowl.api.totalPoints
import net.markdrew.biblebowl.server.data.RegistrationRepository
import net.markdrew.biblebowl.server.data.ScoreRepository
import net.markdrew.biblebowl.server.data.SeasonRepository
import net.markdrew.biblebowl.server.data.UserRecord
import net.markdrew.biblebowl.server.data.UserRepository
import net.markdrew.biblebowl.server.security.currentUser
import net.markdrew.biblebowl.server.security.requireEventWidePermission
import net.markdrew.biblebowl.server.security.requireFeatureEnabled

/**
 * Event-ops scoring (docs/gui-redesign.md §5F): the grading desk (enter round scores for every
 * contestant), the season release switch, and the scoped My Scores view. Grading and release
 * require event-wide grants (GRADER/ADMIN) — nothing is visible to coaches/contestants until a
 * SCORE_RELEASE holder releases the season.
 */
fun Route.scoreRoutes(
    users: UserRepository,
    seasons: SeasonRepository,
    registrations: RegistrationRepository,
    scores: ScoreRepository,
) {
    authenticate {
        get("/admin/scores") {
            val user = currentUser(users) ?: return@get
            if (!requireGradingFeature(user, seasons)) return@get
            if (!requireEventWidePermission(user, Permission.SCORE_ENTER)) return@get
            call.respond(gradingSheet(seasons.current(), registrations, scores))
        }

        put("/admin/scores") {
            val user = currentUser(users) ?: return@put
            if (!requireGradingFeature(user, seasons)) return@put
            if (!requireEventWidePermission(user, Permission.SCORE_ENTER)) return@put
            val season = seasons.current()
            val req = call.receive<SaveScoresRequest>()
            val divisionByEntry = rowSeeds(season, registrations)
                .associate { it.row.rosterEntryId to it.row.division }
            // Validate every cell before saving any, so a bad batch never half-applies.
            for (cell in req.scores) {
                if (cell.rosterEntryId !in divisionByEntry) {
                    return@put call.respond(
                        HttpStatusCode.BadRequest,
                        ApiError("unknown_entry", "No contestant ${cell.rosterEntryId} this season"),
                    )
                }
                val division = divisionByEntry[cell.rosterEntryId]
                if (division != null && cell.round !in division.rounds) {
                    return@put call.respond(
                        HttpStatusCode.BadRequest,
                        ApiError(
                            "round_not_taken",
                            "${division.displayName} contestants don't take ${cell.round.displayName}",
                        ),
                    )
                }
                val points = cell.points
                if (points != null && points !in 0..cell.round.maxPoints) {
                    return@put call.respond(
                        HttpStatusCode.BadRequest,
                        ApiError(
                            "points_out_of_range",
                            "${cell.round.displayName} scores must be 0–${cell.round.maxPoints}",
                        ),
                    )
                }
            }
            req.scores.forEach { scores.set(it.rosterEntryId, it.round, it.points, user.id) }
            call.respond(gradingSheet(season, registrations, scores))
        }

        put("/admin/scores/release") {
            val user = currentUser(users) ?: return@put
            if (!requireGradingFeature(user, seasons)) return@put
            if (!requireEventWidePermission(user, Permission.SCORE_RELEASE)) return@put
            val season = seasons.current()
            val req = call.receive<SetScoresReleasedRequest>()
            scores.setReleased(season.eventYear, user.id, req.released)
            call.respond(gradingSheet(season, registrations, scores))
        }

        get("/admin/scores/standings") {
            val user = currentUser(users) ?: return@get
            if (!requireGradingFeature(user, seasons)) return@get
            if (!requireEventWidePermission(user, Permission.SCORE_VIEW_ALL)) return@get
            val season = seasons.current()
            call.respond(
                StandingsResponse(
                    seasonYear = season.eventYear,
                    releasedAt = scores.releasedAt(season.eventYear),
                    divisions = computeStandings(rowSeeds(season, registrations), scores).divisions,
                )
            )
        }

        get("/scores/mine") {
            val user = currentUser(users) ?: return@get
            if (!requireGradingFeature(user, seasons)) return@get
            val season = seasons.current()
            val released = scores.releasedAt(season.eventYear) != null
            if (!released) {
                // Nothing is visible pre-release — not even to the entries' owners.
                return@get call.respond(MyScoresResponse(season.eventYear, released = false))
            }
            val seeds = rowSeeds(season, registrations)
            val visible = if (hasEventWidePermission(user.roles, Permission.SCORE_VIEW_ALL)) {
                seeds
            } else {
                val coached = coachedCongregationIds(user.roles).toSet()
                val owned = registrations.entryIdsOwnedBy(user.id)
                seeds.filter { it.congregationId in coached || it.row.rosterEntryId in owned }
            }
            // Placement is ranked against the WHOLE field (all seeds), not just the visible rows.
            val standings = computeStandings(seeds, scores)
            val rows = visible.withScores(scores).map { row ->
                val individual = standings.individualRank[row.rosterEntryId]
                val team = standings.teamRank[row.rosterEntryId]
                row.copy(
                    rank = individual?.rank,
                    rankOf = individual?.of,
                    teamRank = team?.rank,
                    teamRankOf = team?.of,
                    teamPoints = team?.points,
                )
            }
            call.respond(MyScoresResponse(season.eventYear, released = true, rows = rows))
        }
    }
}

/**
 * Every scoring endpoint (grading sheet, release, standings, My Scores) stays dark until the
 * season's `gradingEnabled` feature toggle is switched on — global admins are exempt so the
 * feature can be tested in production before launch.
 */
private suspend fun RoutingContext.requireGradingFeature(user: UserRecord, seasons: SeasonRepository): Boolean =
    requireFeatureEnabled(user, seasons.current().gradingEnabled, "Scoring")

/**
 * A grading/My-Scores row plus the ids scoping needs: the congregation (coach scoping) and the
 * team (standings grouping; null for individual contestants).
 */
private data class RowSeed(val congregationId: String, val teamId: String?, val row: ScoreRowDto)

/**
 * One row per contestant registered this season, in desk order (congregation, team — teamless
 * contestants last, contestant). Each contestant carries their OWN division and experience
 * (individual rounds and placement always use these), plus the team's possibly-elevated bracket
 * for the team round — a Junior on a Senior team tests and ranks as a Junior individually while
 * the team competes Senior. Unassigned (teamless) youths compete individually in their own
 * bracket — the normal case for elementary contestants, since there are no Elementary teams.
 * Scores are attached separately (see [withScores]) so save-validation can reuse the seeds
 * without a scores fetch.
 */
private fun rowSeeds(season: SeasonDto, registrations: RegistrationRepository): List<RowSeed> =
    registrations.listForSeason(season.eventYear)
        .flatMap { reg ->
            val teamRows = reg.teams.flatMap { team ->
                val teamDivision = team.division(season)
                val teamInexperienced = team.isInexperienced(season.eventYear)
                team.members.map { member ->
                    // A visiting (combo-team) member scopes and displays under their OWN
                    // congregation — only the team round uses the hosting team's identity.
                    RowSeed(
                        member.congregationId ?: reg.congregation.id,
                        team.id,
                        ScoreRowDto(
                            rosterEntryId = member.id,
                            contestantName = member.name,
                            congregationName = member.congregationName ?: reg.congregation.name,
                            teamName = team.name,
                            division = member.division(season),
                            inexperienced = member.isInexperienced(season.eventYear),
                            teamDivision = teamDivision,
                            teamInexperienced = teamInexperienced,
                        ),
                    )
                }
            }
            val individualRows = reg.individuals.map { individual ->
                RowSeed(
                    reg.congregation.id,
                    null,
                    ScoreRowDto(
                        rosterEntryId = individual.id,
                        contestantName = individual.name,
                        congregationName = reg.congregation.name,
                        teamName = null,
                        division = Division.ADULT,
                    ),
                )
            }
            val unassignedRows = reg.unassigned.map { entry ->
                RowSeed(
                    reg.congregation.id,
                    null,
                    ScoreRowDto(
                        rosterEntryId = entry.id,
                        contestantName = entry.name,
                        congregationName = reg.congregation.name,
                        teamName = null,
                        division = entry.division(season),
                        inexperienced = entry.isInexperienced(season.eventYear),
                    ),
                )
            }
            teamRows + individualRows + unassignedRows
        }
        .sortedWith(
            compareBy(
                { it.row.congregationName.lowercase() },
                { it.row.teamName == null }, // individuals after the congregation's teams
                { it.row.teamName?.lowercase() ?: "" },
                { it.row.contestantName.lowercase() },
            )
        )

private fun List<RowSeed>.withScores(scores: ScoreRepository): List<ScoreRowDto> {
    val byEntry = scores.forEntries(map { it.row.rosterEntryId })
    return map { seed -> seed.row.copy(scores = byEntry[seed.row.rosterEntryId].orEmpty()) }
}

private fun gradingSheet(
    season: SeasonDto,
    registrations: RegistrationRepository,
    scores: ScoreRepository,
): GradingSheetResponse = GradingSheetResponse(
    seasonYear = season.eventYear,
    releasedAt = scores.releasedAt(season.eventYear),
    rows = rowSeeds(season, registrations).withScores(scores),
)

// --- Standings (the division tally) ---------------------------------------------------------

private data class BracketKey(val division: Division, val inexperienced: Boolean)

private data class Placement(val rank: Int, val of: Int)

private data class TeamPlacement(val rank: Int, val of: Int, val points: Int)

private data class TeamAgg(val members: List<ScoreRowDto>, val points: Int)

/** Standings DTOs plus per-entry placement lookups (for decorating My Scores rows). */
private class StandingsData(
    val divisions: List<DivisionStandingsDto>,
    /** Roster entry id → individual placement within its division bracket. */
    val individualRank: Map<String, Placement>,
    /** Roster entry id → its TEAM's placement (absent for individual contestants). */
    val teamRank: Map<String, TeamPlacement>,
)

/**
 * The division tally, ranked by points — competition ranking, so ties share a rank and the next
 * rank skips. A contestant's INDIVIDUAL placement is within their own (division, experience)
 * bracket; their TEAM competes in the team's bracket — its highest member's division and
 * most-experienced member's level — so the two can differ (a Junior on a Senior team ranks
 * individually among Juniors). Individual totals span every eligible round; team totals are the
 * members' rounds 1–5 only (the Power Round never counts toward team scores — the published 800
 * team max is 4 × 200). Ungraded rounds simply count 0, so the tally is meaningful mid-grading.
 * Rows whose division is unknown (legacy unparseable birthdates) can't be bracketed and are left
 * out.
 */
private fun computeStandings(seeds: List<RowSeed>, scores: ScoreRepository): StandingsData {
    val scored = seeds.zip(seeds.withScores(scores)) // RowSeed + its row with scores attached
    val individualRank = mutableMapOf<String, Placement>()
    val teamRank = mutableMapOf<String, TeamPlacement>()

    val individualsByBracket: Map<BracketKey, List<StandingRowDto>> = scored
        .filter { (_, row) -> row.division != null }
        .groupBy { (_, row) -> BracketKey(row.division!!, row.inexperienced) }
        .mapValues { (key, entries) ->
            val allPoints = entries.map { (_, row) -> row.totalPoints }
            entries
                .sortedWith(
                    compareByDescending<Pair<RowSeed, ScoreRowDto>> { it.second.totalPoints }
                        .thenBy { it.second.contestantName.lowercase() }
                )
                .map { (_, row) ->
                    val rank = allPoints.count { it > row.totalPoints } + 1
                    individualRank[row.rosterEntryId] = Placement(rank, entries.size)
                    StandingRowDto(
                        rank = rank,
                        name = row.contestantName,
                        congregationName = row.congregationName,
                        teamName = row.teamName,
                        rosterEntryId = row.rosterEntryId,
                        points = row.totalPoints,
                        maxPoints = key.division.maxScore,
                    )
                }
        }

    val teamsByBracket: Map<BracketKey, List<StandingRowDto>> = scored
        .filter { (seed, row) -> seed.teamId != null && row.teamDivision != null }
        .groupBy { (_, row) -> BracketKey(row.teamDivision!!, row.teamInexperienced) }
        .mapValues { (_, entries) ->
            val teamAggs = entries
                .groupBy { (seed, _) -> seed.teamId!! }
                .values
                .map { members ->
                    val rows = members.map { (_, row) -> row }
                    TeamAgg(rows, teamPoints(rows.map { it.scores }))
                }
            val allTeamPoints = teamAggs.map { it.points }
            teamAggs
                .sortedWith(
                    compareByDescending<TeamAgg> { it.points }
                        .thenBy { it.members.first().teamName?.lowercase() ?: "" }
                )
                .map { agg ->
                    val rank = allTeamPoints.count { it > agg.points } + 1
                    agg.members.forEach { row ->
                        teamRank[row.rosterEntryId] = TeamPlacement(rank, teamAggs.size, agg.points)
                    }
                    StandingRowDto(
                        rank = rank,
                        name = agg.members.first().teamName ?: "?",
                        // A combo team lists every member congregation ("McDermott Road + League City").
                        congregationName = agg.members.map { it.congregationName }.distinct().joinToString(" + "),
                        teamName = null,
                        rosterEntryId = null,
                        points = agg.points,
                        maxPoints = TEAM_MEMBER_MAX_POINTS * agg.members.size,
                    )
                }
        }

    val divisions = (individualsByBracket.keys + teamsByBracket.keys)
        .sortedWith(compareBy({ it.division.ordinal }, { it.inexperienced }))
        .map { key ->
            DivisionStandingsDto(
                key.division,
                key.inexperienced,
                individualsByBracket[key].orEmpty(),
                teamsByBracket[key].orEmpty(),
            )
        }

    return StandingsData(divisions, individualRank, teamRank)
}
