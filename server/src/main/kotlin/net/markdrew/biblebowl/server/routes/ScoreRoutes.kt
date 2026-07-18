package net.markdrew.biblebowl.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
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
import net.markdrew.biblebowl.api.coachedCongregationIds
import net.markdrew.biblebowl.api.division
import net.markdrew.biblebowl.api.hasEventWidePermission
import net.markdrew.biblebowl.api.isInexperienced
import net.markdrew.biblebowl.api.rounds
import net.markdrew.biblebowl.server.data.RegistrationRepository
import net.markdrew.biblebowl.server.data.ScoreRepository
import net.markdrew.biblebowl.server.data.SeasonRepository
import net.markdrew.biblebowl.server.data.UserRepository
import net.markdrew.biblebowl.server.security.currentUser
import net.markdrew.biblebowl.server.security.requireEventWidePermission

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
            if (!requireEventWidePermission(user, Permission.SCORE_ENTER)) return@get
            call.respond(gradingSheet(seasons.current(), registrations, scores))
        }

        put("/admin/scores") {
            val user = currentUser(users) ?: return@put
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
            if (!requireEventWidePermission(user, Permission.SCORE_RELEASE)) return@put
            val season = seasons.current()
            val req = call.receive<SetScoresReleasedRequest>()
            scores.setReleased(season.eventYear, user.id, req.released)
            call.respond(gradingSheet(season, registrations, scores))
        }

        get("/scores/mine") {
            val user = currentUser(users) ?: return@get
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
            call.respond(
                MyScoresResponse(season.eventYear, released = true, rows = visible.withScores(scores))
            )
        }
    }
}

/** A grading/My-Scores row plus the congregation id it belongs to (for coach scoping). */
private data class RowSeed(val congregationId: String, val row: ScoreRowDto)

/**
 * One row per contestant registered this season, in desk order (congregation, team — individuals
 * last, contestant), with each contestant's *competing* division: the team's division (its highest
 * member) and the team's experience bracket, or ADULT for an individual. Scores are attached
 * separately (see [withScores]) so save-validation can reuse the seeds without a scores fetch.
 */
private fun rowSeeds(season: SeasonDto, registrations: RegistrationRepository): List<RowSeed> =
    registrations.listForSeason(season.eventYear)
        .flatMap { reg ->
            val teamRows = reg.teams.flatMap { team ->
                val division = team.division(season)
                val inexperienced = team.isInexperienced(season.eventYear)
                team.members.map { member ->
                    RowSeed(
                        reg.congregation.id,
                        ScoreRowDto(
                            rosterEntryId = member.id,
                            contestantName = member.name,
                            congregationName = reg.congregation.name,
                            teamName = team.name,
                            division = division,
                            inexperienced = inexperienced,
                        ),
                    )
                }
            }
            val individualRows = reg.individuals.map { individual ->
                RowSeed(
                    reg.congregation.id,
                    ScoreRowDto(
                        rosterEntryId = individual.id,
                        contestantName = individual.name,
                        congregationName = reg.congregation.name,
                        teamName = null,
                        division = Division.ADULT,
                    ),
                )
            }
            teamRows + individualRows
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
