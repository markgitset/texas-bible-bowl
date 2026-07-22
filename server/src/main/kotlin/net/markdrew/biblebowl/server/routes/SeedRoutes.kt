package net.markdrew.biblebowl.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import net.markdrew.biblebowl.api.ApiError
import net.markdrew.biblebowl.api.CongregationDto
import net.markdrew.biblebowl.api.CreateCongregationRequest
import net.markdrew.biblebowl.api.EventSiteDto
import net.markdrew.biblebowl.api.Role
import net.markdrew.biblebowl.api.RoleGrant
import net.markdrew.biblebowl.api.ScopeType
import net.markdrew.biblebowl.api.SeedCongregationDto
import net.markdrew.biblebowl.api.SeedMemberDto
import net.markdrew.biblebowl.api.SeedRequest
import net.markdrew.biblebowl.api.SeedSummary
import net.markdrew.biblebowl.api.UpdateCongregationRequest
import net.markdrew.biblebowl.api.UpsertGuestRequest
import net.markdrew.biblebowl.api.UpsertIndividualRequest
import net.markdrew.biblebowl.api.siteSlug
import net.markdrew.biblebowl.server.data.CongregationRepository
import net.markdrew.biblebowl.server.data.CreateCongregationResult
import net.markdrew.biblebowl.server.data.RegistrationRepository
import net.markdrew.biblebowl.server.data.SeasonRepository
import net.markdrew.biblebowl.server.data.UpdateCongregationResult
import net.markdrew.biblebowl.server.data.UserRepository
import net.markdrew.biblebowl.server.security.currentUser
import net.markdrew.biblebowl.server.security.isAdmin
import java.time.Instant

/**
 * The one-time 2026 workbook seed (item 17, F13): `POST /admin/seed` ingests a [SeedRequest]
 * produced offline by `tools/seed/convert_registration_xlsx.py` (the workbook's PII never enters
 * git — the converter runs on Mark's machine and the JSON is posted straight here). Global admins
 * only; deliberately not behind the registration feature toggle (admins bypass it anyway).
 * Idempotent by natural keys — re-running updates in place; the seed fills gaps but never
 * overwrites curated data (an existing congregation keeps its address, an existing contestant
 * their birthdate). Seed-season registrations are marked submitted + paid so history reads as
 * settled; coach emails become pending grants consumed at signup (see AuthRoutes). Seeded
 * siteIds (name-slugs from the workbook) are resolved against the current season's sites by id
 * or name-slug — an unresolved one is stored as-is with a warning, since unpinned registrations
 * in a multi-site season leave their testers un-numbered (TesterRoutes).
 */
fun Route.seedRoutes(
    users: UserRepository,
    congregations: CongregationRepository,
    registrations: RegistrationRepository,
    seasons: SeasonRepository,
) {
    val seeder = WorkbookSeeder(users, congregations, registrations, seasons)
    authenticate {
        post("/admin/seed") {
            val user = currentUser(users) ?: return@post
            if (!user.isAdmin) {
                return@post call.respond(
                    HttpStatusCode.Forbidden,
                    ApiError("forbidden_scope", "The workbook seed is for global admins only"),
                )
            }
            val req = call.receive<SeedRequest>()
            if (req.seasonYear.toIntOrNull() == null || req.congregations.isEmpty()) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("invalid_seed", "A numeric seasonYear and at least one congregation are required"),
                )
            }
            call.respond(seeder.run(req, user.id))
        }
    }
}

private class WorkbookSeeder(
    private val users: UserRepository,
    private val congregations: CongregationRepository,
    private val registrations: RegistrationRepository,
    private val seasons: SeasonRepository,
) {
    fun run(req: SeedRequest, adminUserId: String): SeedSummary {
        val warnings = mutableListOf<String>()
        val sites = seasons.current().sites
        // Two passes: every congregation must exist before any roster is seeded, because a combo
        // team's visiting members belong to a *different* congregation than the team's host.
        val byName = req.congregations.mapNotNull { sc ->
            upsertCongregation(sc, adminUserId, warnings)?.let { sc to it }
        }
        val congregationIdByName = byName.associate { (_, cong) -> cong.name.lowercase() to cong.id }
        val seeded = byName.map { (sc, cong) ->
            seedCongregation(sc, cong, req.seasonYear, congregationIdByName, sites, warnings)
        }
        // Summary counts are what's present after the run (idempotent-friendly), not deltas.
        val regs = seeded.mapNotNull { registrations.find(it.id, req.seasonYear) }
        return SeedSummary(
            seasonYear = req.seasonYear,
            congregations = seeded.size,
            teams = regs.sumOf { it.teams.size },
            members = regs.sumOf { it.teams.sumOf { t -> t.members.size } + it.unassigned.size },
            individuals = regs.sumOf { it.individuals.size },
            guests = regs.sumOf { it.guests.size },
            pendingCoachGrants = users.pendingCoachGrants().values.sumOf { it.size },
            warnings = warnings,
        )
    }

    private fun seedCongregation(
        sc: SeedCongregationDto,
        cong: CongregationDto,
        seasonYear: String,
        congregationIdByName: Map<String, String>,
        sites: List<EventSiteDto>,
        warnings: MutableList<String>,
    ): CongregationDto {
        sc.siteId?.let { registrations.setSite(cong.id, seasonYear, resolveSiteId(it, sites, cong.name, warnings)) }

        // A combo team's visiting member seeds under their OWN congregation (combo rule, item 5).
        fun ownCongregationId(m: SeedMemberDto): String =
            m.congregationName?.let { own ->
                congregationIdByName[own.trim().lowercase()] ?: cong.id.also {
                    warnings += "${cong.name}: unknown congregation \"$own\" for ${m.name} — seeded as home"
                }
            } ?: cong.id

        // Create any missing teams first (addTeam returns null on an existing name), then load the
        // registration ONCE and match everything against that snapshot. Re-runs used to re-load the
        // full registration graph per seeded item, which ground a real Postgres to a timeout; the
        // input's names are unique within a congregation, so a start-of-pass snapshot matches
        // exactly what a per-item lookup would.
        sc.teams.forEach { registrations.addTeam(cong.id, seasonYear, it.name) }
        val snapshot = registrations.find(cong.id, seasonYear)

        sc.teams.forEach { st ->
            val teamId = snapshot?.teams?.firstOrNull { it.name.equals(st.name.trim(), ignoreCase = true) }?.id
            if (teamId == null) {
                warnings += "${cong.name}: could not create team \"${st.name}\""
                return@forEach
            }
            st.members.forEach { m ->
                registrations.seedMember(ownCongregationId(m), seasonYear, teamId, m)
                    ?: warnings.add("${cong.name}: could not seed ${m.name} onto \"${st.name}\"")
            }
        }
        sc.unassigned.forEach { m ->
            registrations.seedMember(ownCongregationId(m), seasonYear, null, m)
                ?: warnings.add("${cong.name}: could not seed ${m.name}")
        }

        // Adult individual contestants — matched by name against the snapshot.
        sc.individuals.forEach { si ->
            val gender = si.gender
            if (gender == null) {
                warnings += "${cong.name}: individual ${si.name} has no gender in the workbook — skipped"
                return@forEach
            }
            val req = UpsertIndividualRequest(si.name, si.shirtSize, gender, si.tribeLeaderWilling)
            val existing = snapshot?.individuals?.firstOrNull { it.name.equals(si.name.trim(), ignoreCase = true) }
            if (existing == null) registrations.addIndividual(cong.id, seasonYear, req)
            else registrations.updateIndividual(existing.id, req)
        }

        // Guests (volunteers, families, coach-typed attendees) — matched by name against the snapshot.
        sc.guests.forEach { sg ->
            val req = UpsertGuestRequest(
                name = sg.name, shirtSize = sg.shirtSize, birthdate = null, gender = sg.gender,
                positions = sg.positions, tribeLeaderWilling = sg.tribeLeaderWilling, contact = sg.contact,
            )
            val existing = snapshot?.guests?.firstOrNull { it.name.equals(sg.name.trim(), ignoreCase = true) }
            if (existing == null) registrations.addGuest(cong.id, seasonYear, req)
            else registrations.updateGuest(existing.id, req)
        }

        // Coach emails: grant now when the account already exists, else park a pending grant.
        sc.coachEmails.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.forEach { email ->
            val existing = users.findByEmail(email)
            if (existing != null) {
                users.addRoleGrant(existing.id, RoleGrant(Role.COACH, ScopeType.CONGREGATION, cong.id))
            } else {
                users.addPendingCoachGrant(email, cong.id, seasonYear)
            }
        }

        // The historical registration reads as settled: submitted, and paid mid-season-year.
        registrations.submit(cong.id, seasonYear)
        registrations.find(cong.id, seasonYear)?.let {
            registrations.setPaid(it.id, Instant.parse("$seasonYear-06-15T00:00:00Z").toEpochMilli())
        }
        return cong
    }

    /**
     * Maps a seeded siteId onto the current season's configured sites. The converter slugs the
     * workbook's site names ("White River" → "white-river"), while sites created before the slug
     * convention may carry any id — so match by exact id first, then by name-slug. An unmatched
     * id is stored as-is with a warning: in a multi-site season it counts as "unpinned" and its
     * testers stay un-numbered, so fix Season settings and re-run the seed. With no sites
     * configured there is nothing to resolve against (everyone numbers as one implicit site).
     */
    private fun resolveSiteId(
        seeded: String,
        sites: List<EventSiteDto>,
        congregationName: String,
        warnings: MutableList<String>,
    ): String {
        if (sites.isEmpty()) return seeded
        val match = sites.firstOrNull { it.id == seeded } ?: sites.firstOrNull { siteSlug(it.name) == seeded }
        if (match == null) {
            warnings += "$congregationName: site \"$seeded\" matches none of the season's sites — " +
                "left unresolved (fix Season settings and re-run the seed)"
            return seeded
        }
        return match.id
    }

    /**
     * Finds the congregation by (trimmed, case-insensitive) name or creates it; an existing one
     * only has its *blank* fields filled from the seed — curated edits always win.
     */
    private fun upsertCongregation(
        sc: SeedCongregationDto,
        adminUserId: String,
        warnings: MutableList<String>,
    ): CongregationDto? {
        val name = sc.name.trim()
        val existing = congregations.listAll().firstOrNull { it.name.equals(name, ignoreCase = true) }
        if (existing == null) {
            val create = CreateCongregationRequest(
                name = name, city = sc.city.trim(), state = sc.state.trim().uppercase(),
                mailingAddress = sc.mailingAddress.trim(), zip = sc.zip.trim(), phone = sc.phone.trim(),
                code = sc.code.trim().uppercase(),
            )
            return when (val result = congregations.create(create, adminUserId)) {
                is CreateCongregationResult.Created -> result.congregation
                CreateCongregationResult.CodeTaken -> {
                    warnings += "$name: code \"${sc.code}\" is taken — created without one"
                    (congregations.create(create.copy(code = ""), adminUserId) as? CreateCongregationResult.Created)
                        ?.congregation
                }
                CreateCongregationResult.NameCityTaken -> {
                    warnings += "$name: name+city collision — skipped"
                    null
                }
            }
        }
        val update = UpdateCongregationRequest(
            name = existing.name,
            city = existing.city.ifBlank { sc.city.trim() },
            state = existing.state.ifBlank { sc.state.trim().uppercase() },
            mailingAddress = existing.mailingAddress.ifBlank { sc.mailingAddress.trim() },
            zip = existing.zip.ifBlank { sc.zip.trim() },
            phone = existing.phone.ifBlank { sc.phone.trim() },
            code = existing.code.ifBlank { sc.code.trim().uppercase() },
        )
        return when (val result = congregations.update(existing.id, update)) {
            is UpdateCongregationResult.Updated -> result.congregation
            UpdateCongregationResult.CodeTaken -> {
                warnings += "$name: code \"${sc.code}\" is taken — left unset"
                (congregations.update(existing.id, update.copy(code = existing.code)) as? UpdateCongregationResult.Updated)
                    ?.congregation ?: existing
            }
            else -> existing
        }
    }
}
