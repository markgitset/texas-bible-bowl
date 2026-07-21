package net.markdrew.biblebowl.server.routes

import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import net.markdrew.biblebowl.generation.typst.Nametag
import net.markdrew.biblebowl.generation.typst.NametagSheet
import net.markdrew.biblebowl.generation.typst.nametagsTypst
import net.markdrew.biblebowl.server.data.RegistrationRepository
import net.markdrew.biblebowl.server.data.SeasonRepository
import net.markdrew.biblebowl.server.data.TesterIdRepository
import net.markdrew.biblebowl.server.data.UserRepository
import net.markdrew.biblebowl.server.security.currentUser
import net.markdrew.biblebowl.server.security.requireEventWidePermission
import net.markdrew.biblebowl.server.typst.TypstCompiler
import net.markdrew.biblebowl.server.typst.TypstException

/**
 * Tester IDs + the ZipGrade roster (registration backlog item 13, F7; scheme in shared-api's
 * TesterIds.kt), and the nametags PDF built on those IDs (item 14, F8). `GET /admin/testers`
 * lists every contestant this season with their per-site tester ID and ZipGrade external ID,
 * lazily assigning numbers to any tester who doesn't have one yet (append-only — an assigned
 * number never changes, so nametags can print early). The ZipGrade CSV itself is built
 * client-side from this response, like the registration-desk CSV.
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

        // GET /admin/registrations/nametags.pdf?siteId=<EventSiteDto.id>
        //
        // Printable nametags (registration backlog item 14, F8 — replaces the workbook's four
        // nametag tabs): 4×3in badges per site for every attendee — testers (with division and
        // tester ID, assigned lazily via the same append-only scheme as /admin/testers) and
        // guests (no ID). Authenticated (attendee names include minors') — unlike the public
        // /generate PDFs — and registrar-gated like the desk.
        get("/admin/registrations/nametags.pdf") {
            val user = currentUser(users) ?: return@get
            if (!requireRegistrationFeature(user, seasons)) return@get
            if (!requireEventWidePermission(user, Permission.REGISTRATION_MANAGE)) return@get
            val season = seasons.current()
            val siteIdParam = call.request.queryParameters["siteId"]

            // Testers (assigning any missing IDs) in per-site tester-ID order, then guests per
            // congregation — every tag carries its resolved site so the sheets group cleanly.
            data class SitedTag(val siteId: String?, val siteName: String, val tag: Nametag)
            val testerTags = testerList(season, registrations, testerIds).rows.map { row ->
                SitedTag(
                    siteId = row.siteId,
                    siteName = row.siteName,
                    tag = Nametag(
                        name = row.name,
                        congregation = row.congregationName,
                        role = row.division?.displayName ?: "",
                        testerId = row.testerId,
                    ),
                )
            }
            val guestTags = registrations.listForSeason(season.eventYear)
                .sortedBy { it.congregation.name.lowercase() }
                .flatMap { reg ->
                    val site = season.siteFor(reg.siteId)
                    reg.guests.sortedBy { it.name.lowercase() }.map { guest ->
                        SitedTag(
                            siteId = site?.id,
                            siteName = site?.name.orEmpty(),
                            tag = Nametag(
                                name = guest.name,
                                congregation = reg.congregation.name,
                                role = if (guest.positions.isNotEmpty()) "Volunteer" else "Guest",
                            ),
                        )
                    }
                }

            // One sheet per site, in season site order; tags with no resolvable site (unpinned in
            // a multi-site season) come last so they're printable but visibly unplaced.
            val siteOrder: Map<String?, Int> = season.sites.mapIndexed { i, s -> s.id to i }.toMap()
            val sheets = (testerTags + guestTags)
                .filter { siteIdParam == null || it.siteId == siteIdParam }
                .groupBy { it.siteId to it.siteName }
                .entries
                .sortedBy { (key, _) -> siteOrder[key.first] ?: season.sites.size }
                .map { (key, sited) ->
                    val (_, siteName) = key
                    NametagSheet(
                        heading = listOfNotNull(
                            "Texas Bible Bowl ${season.eventYear}",
                            siteName.takeIf { it.isNotBlank() },
                        ).joinToString(" — "),
                        tags = sited.map { it.tag },
                    )
                }
                .filter { it.tags.isNotEmpty() }
            if (sheets.isEmpty()) return@get call.respond(
                HttpStatusCode.NotFound,
                ApiError("no_attendees", "No registered attendees to print nametags for"),
            )

            val siteSuffix = siteIdParam?.let { season.siteFor(it)?.name }
                ?.lowercase()?.replace(Regex("[^a-z0-9]+"), "-")?.let { "-$it" } ?: ""
            try {
                val pdf = withContext(Dispatchers.IO) { TypstCompiler.compile(nametagsTypst(sheets)) }
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    ContentDisposition.Attachment
                        .withParameter(
                            ContentDisposition.Parameters.FileName,
                            "tbb-nametags-${season.eventYear}$siteSuffix.pdf",
                        ).toString(),
                )
                call.respondBytes(pdf, ContentType.Application.Pdf)
            } catch (e: TypstException) {
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    ApiError("typst_failed", e.message ?: "PDF generation failed"),
                )
            }
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
