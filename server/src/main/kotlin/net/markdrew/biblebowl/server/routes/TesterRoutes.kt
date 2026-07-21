package net.markdrew.biblebowl.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import net.markdrew.biblebowl.api.ApiError
import net.markdrew.biblebowl.api.Division
import net.markdrew.biblebowl.api.Permission
import net.markdrew.biblebowl.api.SeasonDto
import net.markdrew.biblebowl.api.TESTER_ID_SITE_BLOCK
import net.markdrew.biblebowl.api.TesterListResponse
import net.markdrew.biblebowl.api.TesterRowDto
import net.markdrew.biblebowl.api.division
import net.markdrew.biblebowl.api.externalTesterId
import net.markdrew.biblebowl.api.hasEventWidePermission
import net.markdrew.biblebowl.api.isInexperienced
import net.markdrew.biblebowl.api.siteFor
import net.markdrew.biblebowl.api.testerTeamPart
import net.markdrew.biblebowl.server.data.RegistrationRepository
import net.markdrew.biblebowl.server.data.SeasonRepository
import net.markdrew.biblebowl.server.data.TesterIdRepository
import net.markdrew.biblebowl.server.data.UserRepository
import net.markdrew.biblebowl.server.security.currentUser

/**
 * Tester IDs + the ZipGrade roster (registration backlog item 13, F7; scheme in shared-api's
 * TesterIds.kt). One endpoint: `GET /admin/testers` lists every contestant this season with their
 * per-site tester ID and ZipGrade external ID, lazily assigning numbers to any tester who doesn't
 * have one yet (append-only — an assigned number never changes, so nametags can print early). The
 * ZipGrade CSV itself is built client-side from this response, like the registration-desk CSV.
 *
 * Open to event-wide REGISTRATION_MANAGE (registrars prep IDs and nametags) *or* SCORE_ENTER
 * (ZipGrade is the primary 2027 scan-grading path, so graders need the export too).
 */
fun Route.testerRoutes(
    users: UserRepository,
    seasons: SeasonRepository,
    registrations: RegistrationRepository,
    testerIds: TesterIdRepository,
) {
    authenticate {
        get("/admin/testers") {
            val user = currentUser(users) ?: return@get
            if (!requireRegistrationFeature(user, seasons)) return@get
            val allowed = hasEventWidePermission(user.roles, Permission.REGISTRATION_MANAGE) ||
                hasEventWidePermission(user.roles, Permission.SCORE_ENTER)
            if (!allowed) {
                return@get call.respond(
                    HttpStatusCode.Forbidden,
                    ApiError(
                        "forbidden_scope",
                        "Requires event-wide ${Permission.REGISTRATION_MANAGE} or ${Permission.SCORE_ENTER}",
                    ),
                )
            }
            call.respond(testerList(seasons.current(), registrations, testerIds))
        }
    }
}

/** One tester before numbering: identity plus everything the external ID derives from. */
private data class TesterSeed(
    val rosterEntryId: String,
    val name: String,
    val congregationName: String,
    val congregationCode: String,
    val teamName: String?,
    val teamDivision: Division?,
    val teamInexperienced: Boolean,
    val division: Division?,
    val inexperienced: Boolean,
    /** Resolved site; null = unpinned in a multi-site season (can't be numbered yet). */
    val siteId: String?,
    val siteName: String,
)

/**
 * Every contestant this season (team members — visiting combo members under their OWN
 * congregation and site — plus adult individuals and unassigned youth), numbered per site.
 */
private fun testerList(
    season: SeasonDto,
    registrations: RegistrationRepository,
    testerIds: TesterIdRepository,
): TesterListResponse {
    val regs = registrations.listForSeason(season.eventYear)
    val codeByCongregation = regs.associate { it.congregation.id to it.congregation.code }
    val siteByCongregation = regs.associate { it.congregation.id to season.siteFor(it.siteId) }

    val seeds = regs.flatMap { reg ->
        // A visiting (combo-team) member belongs to their own congregation — its code, its site —
        // while the team part of the external ID names the hosting team. Enumerating host teams
        // (never awayMembers, which mirror the same entries) covers every contestant exactly once.
        val teamRows = reg.teams.flatMap { team ->
            val teamDivision = team.division(season)
            val teamInexperienced = team.isInexperienced(season.eventYear)
            team.members.map { member ->
                val homeCongregationId = member.congregationId ?: reg.congregation.id
                val site = siteByCongregation[homeCongregationId]
                TesterSeed(
                    rosterEntryId = member.id,
                    name = member.name,
                    congregationName = member.congregationName ?: reg.congregation.name,
                    congregationCode = codeByCongregation[homeCongregationId].orEmpty(),
                    teamName = team.name,
                    teamDivision = teamDivision,
                    teamInexperienced = teamInexperienced,
                    division = member.division(season),
                    inexperienced = member.isInexperienced(season.eventYear),
                    siteId = site?.id,
                    siteName = site?.name.orEmpty(),
                )
            }
        }
        val site = siteByCongregation[reg.congregation.id]
        val teamless = (reg.individuals + reg.unassigned).map { entry ->
            TesterSeed(
                rosterEntryId = entry.id,
                name = entry.name,
                congregationName = reg.congregation.name,
                congregationCode = reg.congregation.code,
                teamName = null,
                teamDivision = null,
                teamInexperienced = false,
                division = entry.division(season),
                inexperienced = entry.isInexperienced(season.eventYear),
                siteId = site?.id,
                siteName = site?.name.orEmpty(),
            )
        }
        teamRows + teamless
    }

    val assigned = assignMissingIds(season, seeds, testerIds)

    val rows = seeds
        .map { seed ->
            val testerId = assigned[seed.rosterEntryId]
            val externalId = if (testerId == null || seed.division == null) null else externalTesterId(
                division = seed.division,
                inexperienced = seed.inexperienced,
                congregationCode = seed.congregationCode,
                teamPart = testerTeamPart(seed.division, seed.teamName, seed.teamDivision, seed.teamInexperienced),
                testerId = testerId,
            )
            TesterRowDto(
                rosterEntryId = seed.rosterEntryId,
                testerId = testerId,
                externalId = externalId,
                name = seed.name,
                congregationName = seed.congregationName,
                congregationCode = seed.congregationCode,
                teamName = seed.teamName,
                division = seed.division,
                inexperienced = seed.inexperienced,
                siteId = seed.siteId,
                siteName = seed.siteName,
            )
        }
        // Tester-ID order within each site; the un-numbered (unpinned site) sort last, by name.
        .sortedWith(compareBy({ it.testerId == null }, { it.siteName }, { it.testerId }, { it.name.lowercase() }))

    return TesterListResponse(seasonYear = season.eventYear, rows = rows)
}

/**
 * Assigns a tester ID to every seed that lacks one, per event site: site *i* (season order)
 * numbers from `i * TESTER_ID_SITE_BLOCK + 1`, each new tester taking the next number after the
 * site's highest (never below the base, never a number in use anywhere this season — so even an
 * overflowing site can't collide with another's block). First-time assignment follows desk order
 * (congregation, team — teamless last, name); later additions append after, keeping every earlier
 * number stable. Seeds with no resolvable site (unpinned in a multi-site season) stay un-numbered
 * until the registration pins one. A zero-site season numbers everyone as one implicit site.
 */
private fun assignMissingIds(
    season: SeasonDto,
    seeds: List<TesterSeed>,
    testerIds: TesterIdRepository,
): Map<String, Int> {
    val assigned = testerIds.forSeason(season.eventYear).toMutableMap()
    val used = assigned.values.toMutableSet()

    val siteIdsInOrder: List<String?> =
        if (season.sites.isEmpty()) listOf(null) else season.sites.map { it.id }
    siteIdsInOrder.forEachIndexed { siteIndex, siteId ->
        val siteSeeds = seeds.filter { it.siteId == siteId }
        val base = siteIndex * TESTER_ID_SITE_BLOCK + 1
        var next = maxOf(base, (siteSeeds.mapNotNull { assigned[it.rosterEntryId] }.maxOrNull() ?: 0) + 1)
        siteSeeds
            .filter { it.rosterEntryId !in assigned }
            .sortedWith(
                compareBy(
                    { it.congregationName.lowercase() },
                    { it.teamName == null },
                    { it.teamName?.lowercase() ?: "" },
                    { it.name.lowercase() },
                )
            )
            .forEach { seed ->
                while (next in used) next++
                testerIds.assign(season.eventYear, seed.rosterEntryId, next)
                assigned[seed.rosterEntryId] = next
                used += next
                next++
            }
    }
    return assigned
}
