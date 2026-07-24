package net.markdrew.biblebowl.server.routes

import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.markdrew.biblebowl.api.divisionLabel
import net.markdrew.biblebowl.generation.typst.AwardBracket
import net.markdrew.biblebowl.generation.typst.AwardRow
import net.markdrew.biblebowl.generation.typst.AwardSite
import net.markdrew.biblebowl.generation.typst.awardsTypst
import net.markdrew.biblebowl.server.typst.TypstCompiler
import net.markdrew.biblebowl.server.typst.TypstException
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import net.markdrew.biblebowl.api.ApiError
import net.markdrew.biblebowl.api.ImportIssueDto
import net.markdrew.biblebowl.api.ImportScoresReport
import net.markdrew.biblebowl.api.ImportScoresRequest
import net.markdrew.biblebowl.model.Round
import net.markdrew.biblebowl.api.Division
import net.markdrew.biblebowl.api.GradingSheetResponse
import net.markdrew.biblebowl.api.MyScoresResponse
import net.markdrew.biblebowl.api.Permission
import net.markdrew.biblebowl.api.SaveScoresRequest
import net.markdrew.biblebowl.api.ScoreRowDto
import net.markdrew.biblebowl.api.SeasonDto
import net.markdrew.biblebowl.api.SetScoresReleasedRequest
import net.markdrew.biblebowl.api.SiteReleaseDto
import net.markdrew.biblebowl.api.StandingRowDto
import net.markdrew.biblebowl.api.StandingsResponse
import net.markdrew.biblebowl.api.siteFor
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
import net.markdrew.biblebowl.server.data.TesterIdRepository
import net.markdrew.biblebowl.server.data.releasedAtFor
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
    testerIds: TesterIdRepository,
) {
    authenticate {
        get("/admin/scores") {
            val user = currentUser(users) ?: return@get
            if (!requireGradingFeature(user, seasons)) return@get
            if (!requireEventWidePermission(user, Permission.SCORE_ENTER)) return@get
            val siteFilter = call.request.queryParameters["siteId"]
            call.respond(gradingSheet(seasons.current(), registrations, scores, testerIds, siteFilter))
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
            call.respond(gradingSheet(season, registrations, scores, testerIds))
        }

        // Apply a pasted/uploaded ZipGrade CSV export (grading backlog G1). Idempotent — scores go
        // in by tester id through the same validation as manual entry — and returns a reconciliation
        // report rather than a silent success. The client re-fetches the sheet afterward.
        post("/admin/scores/import") {
            val user = currentUser(users) ?: return@post
            if (!requireGradingFeature(user, seasons)) return@post
            if (!requireEventWidePermission(user, Permission.SCORE_ENTER)) return@post
            val season = seasons.current()
            val csv = call.receive<ImportScoresRequest>().csv
            val report = importZipGrade(csv, season, registrations, scores, testerIds, user.id)
                ?: return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError(
                        "bad_csv",
                        "Couldn't find the ZipGrade columns — need a Quiz Name, an ID, and an Earned Points column",
                    ),
                )
            call.respond(report)
        }

        put("/admin/scores/release") {
            val user = currentUser(users) ?: return@put
            if (!requireGradingFeature(user, seasons)) return@put
            if (!requireEventWidePermission(user, Permission.SCORE_RELEASE)) return@put
            val season = seasons.current()
            val req = call.receive<SetScoresReleasedRequest>()
            // A null siteId targets the season-wide ("") key — the only option for a site-less season.
            scores.setReleased(season.eventYear.toString(), req.siteId ?: "", user.id, req.released)
            call.respond(gradingSheet(season, registrations, scores, testerIds))
        }

        get("/admin/scores/standings") {
            val user = currentUser(users) ?: return@get
            if (!requireGradingFeature(user, seasons)) return@get
            if (!requireEventWidePermission(user, Permission.SCORE_VIEW_ALL)) return@get
            val season = seasons.current()
            val siteFilter = call.request.queryParameters["siteId"]
            val divisions = computeStandings(rowSeeds(season, registrations), scores).divisions
                .let { all -> if (siteFilter == null) all else all.filter { it.siteId == siteFilter } }
            call.respond(
                StandingsResponse(
                    seasonYear = season.eventYear.toString(),
                    releases = siteReleases(season, scores),
                    divisions = divisions,
                )
            )
        }

        // The awards/ceremony booklet (G5): top-N per bracket in reverse announcement order (10th
        // read first, champion last), one page stack per site, team member lists spelled out.
        // GET /admin/scores/awards.pdf?siteId=<id>&topN=<n>
        get("/admin/scores/awards.pdf") {
            val user = currentUser(users) ?: return@get
            if (!requireGradingFeature(user, seasons)) return@get
            if (!requireEventWidePermission(user, Permission.SCORE_VIEW_ALL)) return@get
            val season = seasons.current()
            val siteFilter = call.request.queryParameters["siteId"]
            val topN = call.request.queryParameters["topN"]?.toIntOrNull()?.coerceIn(1, 100) ?: 10
            val divisions = computeStandings(rowSeeds(season, registrations), scores).divisions
                .let { all -> if (siteFilter == null) all else all.filter { it.siteId == siteFilter } }
            val sites = divisions.groupBy { it.siteId to it.siteName }.entries
                .sortedWith(compareBy({ it.key.first == null }, { it.key.second }))
                .map { (key, brackets) ->
                    AwardSite(
                        heading = key.second.ifBlank { "Texas Bible Bowl" },
                        brackets = brackets.map { bracket ->
                            AwardBracket(
                                title = divisionLabel(bracket.division, bracket.inexperienced),
                                individuals = awardRows(bracket.individuals, topN, teamRow = false),
                                teams = awardRows(bracket.teams, topN, teamRow = true),
                            )
                        },
                    )
                }
            if (sites.all { s -> s.brackets.all { it.individuals.isEmpty() && it.teams.isEmpty() } }) {
                return@get call.respond(HttpStatusCode.NotFound, ApiError("no_results", "No ranked results to print yet"))
            }
            val title = "Texas Bible Bowl ${season.eventYear} — Awards"
            try {
                val pdf = withContext(Dispatchers.IO) { TypstCompiler.compile(awardsTypst(title, sites)) }
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    ContentDisposition.Attachment
                        .withParameter(ContentDisposition.Parameters.FileName, "tbb-awards-${season.eventYear}.pdf")
                        .toString(),
                )
                call.respondBytes(pdf, ContentType.Application.Pdf)
            } catch (e: TypstException) {
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    ApiError("typst_failed", e.message ?: "PDF generation failed"),
                )
            }
        }

        get("/scores/mine") {
            val user = currentUser(users) ?: return@get
            if (!requireGradingFeature(user, seasons)) return@get
            val season = seasons.current()
            val releases = scores.releases(season.eventYear.toString())
            if (releases.isEmpty()) {
                // Nothing is visible pre-release — not even to the entries' owners.
                return@get call.respond(MyScoresResponse(season.eventYear.toString(), released = false))
            }
            val seeds = rowSeeds(season, registrations)
            val owns = if (hasEventWidePermission(user.roles, Permission.SCORE_VIEW_ALL)) {
                seeds
            } else {
                val coached = coachedCongregationIds(user.roles).toSet()
                val owned = registrations.entryIdsOwnedBy(user.id)
                seeds.filter { it.congregationId in coached || it.row.rosterEntryId in owned }
            }
            // A contestant's row appears only once THEIR site (or the season-wide "" stamp) is released,
            // so a finished site's families see scores while a still-grading site stays dark.
            val visible = owns.filter { releases.releasedAtFor(it.row.siteId) != null }
            if (visible.isEmpty()) {
                return@get call.respond(MyScoresResponse(season.eventYear.toString(), released = false))
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
            call.respond(MyScoresResponse(season.eventYear.toString(), released = true, rows = rows))
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
 * A grading/My-Scores row plus the ids scoping needs: the congregation (coach scoping), the team
 * (standings grouping; null for individual contestants), and the team's host site (teams rank
 * within their host's site, which can differ from a visiting combo member's own site).
 */
private data class RowSeed(
    val congregationId: String,
    val teamId: String?,
    val teamSiteId: String?,
    val teamSiteName: String,
    val row: ScoreRowDto,
)

/**
 * One row per contestant registered this season, in desk order (congregation, team — teamless
 * contestants last, contestant). Each contestant carries their OWN division and experience
 * (individual rounds and placement always use these), plus the team's possibly-elevated bracket
 * for the team round — a Junior on a Senior team tests and ranks as a Junior individually while
 * the team competes Senior. Unassigned (teamless) youths compete individually in their own
 * bracket — the normal case for elementary contestants, since there are no Elementary teams.
 * Each contestant's site is their OWN congregation's resolved event site (a visiting combo member
 * included), while a team's site is its host registration's — the two differ only for a combo team
 * whose congregations sit at different sites. Scores are attached separately (see [withScores]) so
 * save-validation can reuse the seeds without a scores fetch.
 */
private fun rowSeeds(season: SeasonDto, registrations: RegistrationRepository): List<RowSeed> {
    val regs = registrations.listForSeason(season.eventYear.toString())
    val siteByCongregation = regs.associate { it.congregation.id to season.siteFor(it.siteId) }
    return regs
        .flatMap { reg ->
            val hostSite = season.siteFor(reg.siteId)
            val teamRows = reg.teams.flatMap { team ->
                val teamDivision = team.division(season)
                val teamInexperienced = team.isInexperienced(season.eventYear.toString())
                team.members.map { member ->
                    // A visiting (combo-team) member scopes and displays under their OWN
                    // congregation and site — only the team round uses the hosting team's identity.
                    val ownSite = siteByCongregation[member.participation.congregationId]
                    RowSeed(
                        member.participation.congregationId,
                        team.id,
                        hostSite?.id,
                        hostSite?.name.orEmpty(),
                        ScoreRowDto(
                            rosterEntryId = member.participation.id,
                            siteId = ownSite?.id,
                            siteName = ownSite?.name.orEmpty(),
                            contestantName = member.person.name,
                            congregationName = member.participation.congregationName,
                            teamName = team.name,
                            division = member.division(season),
                            inexperienced = member.isInexperienced(season.eventYear.toString()),
                            teamDivision = teamDivision,
                            teamInexperienced = teamInexperienced,
                        ),
                    )
                }
            }
            val individualRows = reg.individuals.map { individual ->
                RowSeed(
                    reg.congregation.id, null, null, "",
                    ScoreRowDto(
                        rosterEntryId = individual.participation.id,
                        siteId = hostSite?.id,
                        siteName = hostSite?.name.orEmpty(),
                        contestantName = individual.person.name,
                        congregationName = reg.congregation.name,
                        teamName = null,
                        division = Division.ADULT,
                    ),
                )
            }
            val unassignedRows = reg.unassigned.map { entry ->
                RowSeed(
                    reg.congregation.id, null, null, "",
                    ScoreRowDto(
                        rosterEntryId = entry.participation.id,
                        siteId = hostSite?.id,
                        siteName = hostSite?.name.orEmpty(),
                        contestantName = entry.person.name,
                        congregationName = reg.congregation.name,
                        teamName = null,
                        division = entry.division(season),
                        inexperienced = entry.isInexperienced(season.eventYear.toString()),
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
}

private fun List<RowSeed>.withScores(scores: ScoreRepository): List<ScoreRowDto> {
    val byEntry = scores.forEntries(map { it.row.rosterEntryId })
    return map { seed -> seed.row.copy(scores = byEntry[seed.row.rosterEntryId].orEmpty()) }
}

private fun gradingSheet(
    season: SeasonDto,
    registrations: RegistrationRepository,
    scores: ScoreRepository,
    testerIds: TesterIdRepository,
    siteFilter: String? = null,
): GradingSheetResponse {
    // The desk shows the same tester + ZipGrade IDs as the nametags and the ZipGrade export — reuse
    // the tester list (which lazily assigns any missing numbers, append-only) rather than recomputing.
    val idByEntry = testerList(season, registrations, testerIds).rows.associateBy { it.rosterEntryId }
    val rows = rowSeeds(season, registrations).withScores(scores)
        .let { all -> if (siteFilter == null) all else all.filter { it.siteId == siteFilter } }
        .map { row ->
            val id = idByEntry[row.rosterEntryId]
            row.copy(testerId = id?.testerId, externalId = id?.externalId)
        }
    return GradingSheetResponse(
        seasonYear = season.eventYear.toString(),
        releases = siteReleases(season, scores),
        rows = rows,
    )
}

// --- ZipGrade import (G1) -------------------------------------------------------------------

/** "Round 4: Quotes" → the Round with number 4. Match the stable number, never the display name. */
private val QUIZ_ROUND = Regex("""round\s*0*(\d+)""", RegexOption.IGNORE_CASE)

private fun quizToRound(quiz: String): Round? {
    val n = QUIZ_ROUND.find(quiz.trim())?.groupValues?.get(1)?.toIntOrNull() ?: return null
    return Round.entries.firstOrNull { it.number == n }
}

private fun namesMatch(a: String, b: String): Boolean {
    fun norm(s: String) = s.trim().lowercase().replace(Regex("\\s+"), " ")
    return norm(a) == norm(b)
}

/** A tolerant CSV reader: handles quoted fields, doubled quotes, and CRLF/LF; drops blank lines. */
private fun parseCsv(text: String): List<List<String>> {
    val rows = mutableListOf<List<String>>()
    var row = mutableListOf<String>()
    val field = StringBuilder()
    var inQuotes = false
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            inQuotes -> when {
                c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { field.append('"'); i++ }
                c == '"' -> inQuotes = false
                else -> field.append(c)
            }
            c == '"' -> inQuotes = true
            c == ',' -> { row.add(field.toString()); field.clear() }
            c == '\r' -> {}
            c == '\n' -> { row.add(field.toString()); field.clear(); rows.add(row); row = mutableListOf() }
            else -> field.append(c)
        }
        i++
    }
    if (field.isNotEmpty() || row.isNotEmpty()) { row.add(field.toString()); rows.add(row) }
    return rows.filter { cells -> cells.any { it.isNotBlank() } }
}

/**
 * Parses a ZipGrade CSV export and applies its scores to the grading desk by tester id, returning
 * a reconciliation report (or null when the header has no recognizable Quiz/ID/Points columns).
 * Matching reuses the same tester list the export was built from — an id cell matches by numeric
 * tester id or by external id — so the statewide export's other-site rows fall out as unknown ids
 * rather than errors. Duplicate (tester, round) scans keep the last value (the re-paste rhythm);
 * scores apply through [ScoreRepository.set], so a re-import updates in place.
 */
private fun importZipGrade(
    csv: String,
    season: SeasonDto,
    registrations: RegistrationRepository,
    scores: ScoreRepository,
    testerIds: TesterIdRepository,
    userId: String,
): ImportScoresReport? {
    val grid = parseCsv(csv)
    if (grid.size < 2) return null
    val header = grid.first().map { it.trim().lowercase() }
    val quizCol = header.indexOfFirst { it.contains("quiz") }
    val firstCol = header.indexOfFirst { it.contains("first") }
    val lastCol = header.indexOfFirst { it.contains("last") }
    val pointsCol = header.indexOfFirst { it.contains("earned") }
        .let { if (it >= 0) it else header.indexOfFirst { h -> h.contains("point") && !h.contains("possible") } }
    val idCols = header.indices.filter { header[it].contains("id") }
    if (quizCol < 0 || pointsCol < 0 || idCols.isEmpty()) return null

    val testers = testerList(season, registrations, testerIds).rows
    val byTesterId = testers.mapNotNull { r -> r.testerId?.let { it to r } }.toMap()
    val byExternalId = testers.mapNotNull { r -> r.externalId?.let { it.uppercase() to r } }.toMap()

    val appliedByRound = mutableMapOf<Round, Int>()
    val unknownIds = mutableListOf<ImportIssueDto>()
    val nameMismatches = mutableListOf<ImportIssueDto>()
    val duplicates = mutableListOf<ImportIssueDto>()
    val skipped = mutableListOf<ImportIssueDto>()
    // (participant, round) -> points to apply; a later row overwrites an earlier one (last wins).
    val winners = LinkedHashMap<Pair<String, Round>, Int>()

    for (line in grid.drop(1)) {
        fun cell(i: Int) = if (i in line.indices) line[i].trim() else ""
        val idCells = idCols.map { cell(it) }.filter { it.isNotEmpty() }
        val idShown = idCells.firstOrNull().orEmpty()
        val name = listOfNotNull(
            firstCol.takeIf { it >= 0 }?.let { cell(it) },
            lastCol.takeIf { it >= 0 }?.let { cell(it) },
        ).filter { it.isNotBlank() }.joinToString(" ")

        val round = quizToRound(cell(quizCol))
        if (round == null) {
            skipped += ImportIssueDto(idShown, name, "unrecognized quiz \"${cell(quizCol)}\"")
            continue
        }
        val tester = idCells.firstNotNullOfOrNull { c ->
            c.toIntOrNull()?.let { byTesterId[it] } ?: byExternalId[c.uppercase()]
        }
        if (tester == null) {
            unknownIds += ImportIssueDto(idShown, name, "no tester with this id this season")
            continue
        }
        val points = cell(pointsCol).toIntOrNull()
        if (points == null) {
            skipped += ImportIssueDto(idShown, name, "unreadable points \"${cell(pointsCol)}\"")
            continue
        }
        if (points !in 0..round.maxPoints) {
            skipped += ImportIssueDto(idShown, name, "${round.displayName} points $points out of 0..${round.maxPoints}")
            continue
        }
        val division = tester.division
        if (division != null && round !in division.rounds) {
            skipped += ImportIssueDto(idShown, name, "${division.displayName} doesn't take ${round.displayName}")
            continue
        }
        if (name.isNotBlank() && !namesMatch(name, tester.name)) {
            nameMismatches += ImportIssueDto(idShown, name, "roster has \"${tester.name}\"")
        }
        val key = tester.rosterEntryId to round
        if (winners.containsKey(key)) {
            duplicates += ImportIssueDto(idShown, name, "repeat ${round.displayName} scan; kept $points")
        }
        winners[key] = points
    }

    winners.forEach { (key, points) ->
        scores.set(key.first, key.second, points, userId)
        appliedByRound[key.second] = (appliedByRound[key.second] ?: 0) + 1
    }
    return ImportScoresReport(
        appliedByRound = appliedByRound,
        appliedTotal = winners.size,
        unknownIds = unknownIds,
        nameMismatches = nameMismatches,
        duplicates = duplicates,
        skipped = skipped,
    )
}

/**
 * The top-[topN] rows of a bracket, in reverse announcement order (highest place first, champion
 * last) — the order the emcee reads. Individual rows carry their congregation as detail; team rows
 * carry their member list (already on the standing row).
 */
private fun awardRows(rows: List<StandingRowDto>, topN: Int, teamRow: Boolean): List<AwardRow> =
    rows.filter { it.rank <= topN }
        .sortedByDescending { it.rank }
        .map { r ->
            AwardRow(
                place = r.rank,
                name = r.name,
                detail = if (teamRow) "" else r.congregationName,
                score = "${r.points} / ${r.maxPoints}",
                members = r.members,
            )
        }

/**
 * Per-site release state for the desk/standings: one entry per configured event site (its own
 * release stamp), or — in a site-less season — a single "" entry standing for the whole season.
 */
private fun siteReleases(season: SeasonDto, scores: ScoreRepository): List<SiteReleaseDto> {
    val releases = scores.releases(season.eventYear.toString())
    return if (season.sites.isEmpty()) {
        listOf(SiteReleaseDto("", "", releases[""]))
    } else {
        season.sites.map { SiteReleaseDto(it.id, it.name, releases[it.id]) }
    }
}

// --- Standings (the division tally) ---------------------------------------------------------

private data class BracketKey(val siteId: String?, val siteName: String, val division: Division, val inexperienced: Boolean)

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
 * rank skips. Brackets are per SITE (2026 ran two sites that awarded independently): a contestant's
 * INDIVIDUAL placement is within their own (site, division, experience) bracket; their TEAM competes
 * in its host site's (division, experience) bracket — its highest member's division and
 * most-experienced member's level — so the two can differ (a Junior on a Senior team ranks
 * individually among Juniors at their site). Individual totals span every eligible round; team totals are the
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
        .groupBy { (_, row) -> BracketKey(row.siteId, row.siteName, row.division!!, row.inexperienced) }
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
        .groupBy { (seed, row) -> BracketKey(seed.teamSiteId, seed.teamSiteName, row.teamDivision!!, row.teamInexperienced) }
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
                        members = agg.members.map { it.contestantName }.sorted(),
                    )
                }
        }

    val divisions = (individualsByBracket.keys + teamsByBracket.keys)
        // Real sites first (in name order), unpinned last; then division, then experience.
        .sortedWith(compareBy({ it.siteId == null }, { it.siteName }, { it.division.ordinal }, { it.inexperienced }))
        .map { key ->
            DivisionStandingsDto(
                division = key.division,
                inexperienced = key.inexperienced,
                siteId = key.siteId,
                siteName = key.siteName,
                individuals = individualsByBracket[key].orEmpty(),
                teams = teamsByBracket[key].orEmpty(),
            )
        }

    return StandingsData(divisions, individualRank, teamRank)
}
