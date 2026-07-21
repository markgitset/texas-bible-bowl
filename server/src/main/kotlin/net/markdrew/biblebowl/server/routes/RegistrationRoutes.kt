package net.markdrew.biblebowl.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import net.markdrew.biblebowl.api.ApiError
import net.markdrew.biblebowl.api.AssignMemberTeamRequest
import net.markdrew.biblebowl.api.CodeSuggestionResponse
import net.markdrew.biblebowl.api.CreateCongregationRequest
import net.markdrew.biblebowl.api.EnrollContestantRequest
import net.markdrew.biblebowl.api.MyRegistrationResponse
import net.markdrew.biblebowl.api.Permission
import net.markdrew.biblebowl.api.RegistrationDto
import net.markdrew.biblebowl.api.Role
import net.markdrew.biblebowl.api.RoleGrant
import net.markdrew.biblebowl.api.ScopeType
import net.markdrew.biblebowl.api.SeasonDto
import net.markdrew.biblebowl.api.SetRegistrationSiteRequest
import net.markdrew.biblebowl.api.UpdateCongregationRequest
import net.markdrew.biblebowl.api.UpsertGuestRequest
import net.markdrew.biblebowl.api.UpsertIndividualRequest
import net.markdrew.biblebowl.api.UpsertRosterEntryRequest
import net.markdrew.biblebowl.api.UpsertTeamRequest
import net.markdrew.biblebowl.api.coachedCongregationIds
import net.markdrew.biblebowl.api.gradeForBirthdate
import net.markdrew.biblebowl.api.isEligibleReturningCandidate
import net.markdrew.biblebowl.api.hasEventWidePermission
import net.markdrew.biblebowl.api.multiSite
import net.markdrew.biblebowl.api.registrationTotalCents
import net.markdrew.biblebowl.api.siteFor
import net.markdrew.biblebowl.api.ClaimEntryRequest
import net.markdrew.biblebowl.server.RegistrationWindowState
import net.markdrew.biblebowl.server.data.AddMemberResult
import net.markdrew.biblebowl.server.data.AssignResult
import net.markdrew.biblebowl.server.data.ClaimCodes
import net.markdrew.biblebowl.server.data.EnrollResult
import net.markdrew.biblebowl.server.data.ClaimResult
import net.markdrew.biblebowl.server.data.CongregationRepository
import net.markdrew.biblebowl.server.data.CreateCongregationResult
import net.markdrew.biblebowl.server.data.RegistrationRepository
import net.markdrew.biblebowl.server.data.SeasonRepository
import net.markdrew.biblebowl.server.data.UpdateCongregationResult
import net.markdrew.biblebowl.server.data.UserRecord
import net.markdrew.biblebowl.server.data.UserRepository
import net.markdrew.biblebowl.server.registrationWindowState
import net.markdrew.biblebowl.server.security.currentUser
import net.markdrew.biblebowl.server.security.isAdmin
import net.markdrew.biblebowl.server.security.requireEventWidePermission
import net.markdrew.biblebowl.server.security.requireFeatureEnabled
import net.markdrew.biblebowl.server.security.requireScopedPermission

/**
 * Registration (docs/gui-redesign.md §5E): congregations, teams, rosters, and the coach flow's
 * resume/submit endpoints. Everything requires sign-in; mutations additionally require the
 * congregation-scoped grant and (except congregation creation, which is onboarding) an open
 * registration window. Creating a congregation self-serve grants the creator COACH scoped to it —
 * but only adult accounts may do so; claiming an existing one goes through an admin (the client
 * shows "contact us" on the 409).
 */
fun Route.registrationRoutes(
    users: UserRepository,
    seasons: SeasonRepository,
    congregations: CongregationRepository,
    registrations: RegistrationRepository,
) {
    authenticate {
        post("/congregations") {
            val user = currentUser(users) ?: return@post
            if (!requireRegistrationFeature(user, seasons)) return@post
            if (!user.adult && !user.isAdmin) {
                return@post call.respond(
                    HttpStatusCode.Forbidden,
                    ApiError(
                        "adult_required",
                        "Congregations must be registered by an adult — if you are one, mark it on your Account page",
                    ),
                )
            }
            val req = call.receive<CreateCongregationRequest>()
            if (!req.isValid()) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError(
                        "invalid_congregation",
                        "Name, mailing address, city, two-letter state, and 5-digit ZIP are required",
                    ),
                )
            }
            val code = req.code.trim().uppercase()
            if (!isCodeValid(code)) {
                return@post call.respond(HttpStatusCode.BadRequest, INVALID_CODE_ERROR)
            }
            val normalized = req.copy(state = req.state.trim().uppercase(), code = code)
            when (val result = congregations.create(normalized, user.id)) {
                is CreateCongregationResult.Created -> {
                    users.addRoleGrant(user.id, RoleGrant(Role.COACH, ScopeType.CONGREGATION, result.congregation.id))
                    call.respond(HttpStatusCode.Created, result.congregation)
                }
                CreateCongregationResult.NameCityTaken -> call.respond(
                    HttpStatusCode.Conflict,
                    ApiError(
                        "congregation_exists",
                        "That congregation is already registered — contact us to be added as its coach",
                    ),
                )
                CreateCongregationResult.CodeTaken -> call.respond(HttpStatusCode.Conflict, CODE_TAKEN_ERROR)
            }
        }

        get("/congregations") {
            val user = currentUser(users) ?: return@get
            if (!requireRegistrationFeature(user, seasons)) return@get
            call.respond(congregations.search(call.request.queryParameters["query"] ?: ""))
        }

        // Suggests an available two-letter code derived from a congregation name (see
        // congregationCodeCandidates) — the register form prefills it as the coach types the name.
        get("/congregations/code-suggestion") {
            val user = currentUser(users) ?: return@get
            if (!requireRegistrationFeature(user, seasons)) return@get
            call.respond(CodeSuggestionResponse(congregations.suggestCode(call.request.queryParameters["name"] ?: "")))
        }

        // Editing a congregation after registration — a coach may fix its name, address, city,
        // state, or ZIP until registration closes (admins any time). The two-letter congregation
        // code is set-once for a coach: they choose it while it's blank, but once set only an admin
        // may change it.
        put("/congregations/{congregationId}") {
            val user = currentUser(users) ?: return@put
            if (!requireRegistrationFeature(user, seasons)) return@put
            val congregationId = call.parameters["congregationId"]!!
            if (!requireScopedPermission(user, Permission.REGISTRATION_MANAGE, congregationId)) return@put
            if (!requireWindowOpen(user, seasons)) return@put
            val existing = congregations.findById(congregationId)
                ?: return@put call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such congregation"))
            val req = call.receive<UpdateCongregationRequest>()
            if (!req.isValid()) {
                return@put call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError(
                        "invalid_congregation",
                        "Name, mailing address, city, two-letter state, and 5-digit ZIP are required",
                    ),
                )
            }
            val code = req.code.trim().uppercase()
            if (!isCodeValid(code)) {
                return@put call.respond(HttpStatusCode.BadRequest, INVALID_CODE_ERROR)
            }
            // Once a coach has picked the code, it's locked to everyone but an admin.
            if (existing.code.isNotBlank() && code != existing.code && !user.isAdmin) {
                return@put call.respond(
                    HttpStatusCode.Forbidden,
                    ApiError(
                        "forbidden_code_change",
                        "Your congregation code is already set — contact us and an admin can change it",
                    ),
                )
            }
            val normalized = req.copy(state = req.state.trim().uppercase(), code = code)
            when (val result = congregations.update(congregationId, normalized)) {
                is UpdateCongregationResult.Updated -> call.respond(result.congregation)
                UpdateCongregationResult.NotFound ->
                    call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such congregation"))
                UpdateCongregationResult.NameCityTaken ->
                    call.respond(
                        HttpStatusCode.Conflict,
                        ApiError("congregation_exists", "Another congregation already uses that name and city"),
                    )
                UpdateCongregationResult.CodeTaken -> call.respond(HttpStatusCode.Conflict, CODE_TAKEN_ERROR)
            }
        }

        get("/registration/mine") {
            val user = currentUser(users) ?: return@get
            if (!requireRegistrationFeature(user, seasons)) return@get
            val season = seasons.current()
            val coached = congregations.findByIds(coachedCongregationIds(user.roles))
            val congregation = coached.firstOrNull()
            val registration = congregation?.let { registrations.find(it.id, season.eventYear) }?.withTotal(seasons)
            val candidates = congregation
                ?.let { registrations.returningContestants(it.id, season.eventYear) }
                ?.filter { season.isEligibleReturningCandidate(it.birthdate) }
                ?: emptyList()
            call.respond(
                MyRegistrationResponse(
                    congregations = coached,
                    registration = registration,
                    windowOpen = season.registrationWindowState() == RegistrationWindowState.OPEN,
                    returningCandidates = candidates,
                )
            )
        }

        // Pin the congregation's registration to one of the season's event sites (multi-site
        // seasons; item F6). Part of the congregation step, so it creates the draft registration
        // if none exists yet. Coaches pick their own site; a registrar may fix one any time.
        put("/registration/{congregationId}/site") {
            val user = currentUser(users) ?: return@put
            if (!requireRegistrationFeature(user, seasons)) return@put
            val congregationId = call.parameters["congregationId"]!!
            if (!requireCongregationEditor(user, congregationId, seasons)) return@put
            val req = call.receive<SetRegistrationSiteRequest>()
            val season = seasons.current()
            if (season.sites.none { it.id == req.siteId }) {
                return@put call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("unknown_site", "That event site isn't one of this season's sites"),
                )
            }
            call.respond(registrations.setSite(congregationId, season.eventYear, req.siteId).withTotal(seasons))
        }

        post("/registration/{congregationId}/teams") {
            val user = currentUser(users) ?: return@post
            if (!requireRegistrationFeature(user, seasons)) return@post
            val congregationId = call.parameters["congregationId"]!!
            // Coaches (window-gated) create their own teams; a registrar can add one to place leftovers.
            if (!requireCongregationEditor(user, congregationId, seasons)) return@post
            val req = call.receive<UpsertTeamRequest>()
            if (req.name.isBlank()) {
                return@post call.respond(HttpStatusCode.BadRequest, ApiError("invalid_team", "Team name is required"))
            }
            registrations.addTeam(congregationId, seasons.current().eventYear, req.name)
                ?: return@post call.respond(
                    HttpStatusCode.Conflict,
                    ApiError("team_exists", "A team with that name already exists"),
                )
            call.respond(registrations.find(congregationId, seasons.current().eventYear)!!.withTotal(seasons))
        }

        put("/registration/teams/{teamId}") {
            val user = currentUser(users) ?: return@put
            if (!requireRegistrationFeature(user, seasons)) return@put
            val teamId = call.parameters["teamId"]!!
            val congregationId = registrations.congregationIdForTeam(teamId)
                ?: return@put call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such team"))
            if (!requireScopedPermission(user, Permission.TEAM_MANAGE, congregationId)) return@put
            if (!requireWindowOpen(user, seasons)) return@put
            val req = call.receive<UpsertTeamRequest>()
            if (req.name.isBlank()) {
                return@put call.respond(HttpStatusCode.BadRequest, ApiError("invalid_team", "Team name is required"))
            }
            registrations.renameTeam(teamId, req.name)
            call.respond(registrations.find(congregationId, seasons.current().eventYear)!!.withTotal(seasons))
        }

        delete("/registration/teams/{teamId}") {
            val user = currentUser(users) ?: return@delete
            if (!requireRegistrationFeature(user, seasons)) return@delete
            val teamId = call.parameters["teamId"]!!
            val congregationId = registrations.congregationIdForTeam(teamId)
                ?: return@delete call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such team"))
            if (!requireScopedPermission(user, Permission.TEAM_MANAGE, congregationId)) return@delete
            if (!requireWindowOpen(user, seasons)) return@delete
            registrations.deleteTeam(teamId)
            call.respond(registrations.find(congregationId, seasons.current().eventYear)!!.withTotal(seasons))
        }

        post("/registration/teams/{teamId}/members") {
            val user = currentUser(users) ?: return@post
            if (!requireRegistrationFeature(user, seasons)) return@post
            val teamId = call.parameters["teamId"]!!
            val congregationId = registrations.congregationIdForTeam(teamId)
                ?: return@post call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such team"))
            if (!requireScopedPermission(user, Permission.TEAM_MANAGE, congregationId)) return@post
            if (!requireWindowOpen(user, seasons)) return@post
            val req = call.receive<UpsertRosterEntryRequest>()
            if (!req.isValid(seasons.current())) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("invalid_member", INVALID_MEMBER_MESSAGE),
                )
            }
            when (registrations.addMember(teamId, req)) {
                is AddMemberResult.Added ->
                    call.respond(registrations.find(congregationId, seasons.current().eventYear)!!.withTotal(seasons))
                AddMemberResult.RosterFull ->
                    call.respond(HttpStatusCode.Conflict, ApiError("roster_full", "A team may have at most 4 contestants"))
                AddMemberResult.TeamNotFound ->
                    call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such team"))
            }
        }

        put("/registration/members/{memberId}") {
            val user = currentUser(users) ?: return@put
            if (!requireRegistrationFeature(user, seasons)) return@put
            val memberId = call.parameters["memberId"]!!
            val congregationId = registrations.congregationIdForMember(memberId)
                ?: return@put call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such roster entry"))
            if (!requireScopedPermission(user, Permission.TEAM_MANAGE, congregationId)) return@put
            if (!requireWindowOpen(user, seasons)) return@put
            val req = call.receive<UpsertRosterEntryRequest>()
            if (!req.isValid(seasons.current())) {
                return@put call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("invalid_member", INVALID_MEMBER_MESSAGE),
                )
            }
            registrations.updateMember(memberId, req)
            call.respond(registrations.find(congregationId, seasons.current().eventYear)!!.withTotal(seasons))
        }

        delete("/registration/members/{memberId}") {
            val user = currentUser(users) ?: return@delete
            if (!requireRegistrationFeature(user, seasons)) return@delete
            val memberId = call.parameters["memberId"]!!
            val congregationId = registrations.congregationIdForMember(memberId)
                ?: return@delete call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such roster entry"))
            if (!requireScopedPermission(user, Permission.TEAM_MANAGE, congregationId)) return@delete
            if (!requireWindowOpen(user, seasons)) return@delete
            registrations.deleteMember(memberId)
            call.respond(registrations.find(congregationId, seasons.current().eventYear)!!.withTotal(seasons))
        }

        // (Re)assign a youth contestant to a team, or free it to the unassigned pool (null teamId).
        // Coaches move their own contestants; a registrar places any left unassigned at submit time.
        // Placing a contestant on ANOTHER congregation's team (a combo team) is registrar-mediated:
        // it needs an event-wide REGISTRATION_MANAGE grant, not either side's coach grant. Pulling
        // the member back (null teamId or a home team) stays a home-coach action.
        put("/registration/members/{memberId}/team") {
            val user = currentUser(users) ?: return@put
            if (!requireRegistrationFeature(user, seasons)) return@put
            val memberId = call.parameters["memberId"]!!
            val congregationId = registrations.congregationIdForMember(memberId)
                ?: return@put call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such roster entry"))
            val req = call.receive<AssignMemberTeamRequest>()
            val teamCongregationId = req.teamId?.let {
                registrations.congregationIdForTeam(it)
                    ?: return@put call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such team"))
            }
            if (teamCongregationId != null && teamCongregationId != congregationId) {
                if (!requireEventWidePermission(user, Permission.REGISTRATION_MANAGE)) return@put
            } else {
                if (!requireCongregationEditor(user, congregationId, seasons)) return@put
            }
            when (registrations.assignMemberToTeam(memberId, req.teamId)) {
                AssignResult.Assigned ->
                    call.respond(registrations.find(congregationId, seasons.current().eventYear)!!.withTotal(seasons))
                AssignResult.RosterFull ->
                    call.respond(HttpStatusCode.Conflict, ApiError("roster_full", "A team may have at most 4 contestants"))
                AssignResult.TeamNotFound ->
                    call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such team for this registration"))
                AssignResult.MemberNotFound ->
                    call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such roster entry"))
            }
        }

        // Enroll a returning contestant into this season — creates their roster entry from the durable
        // contestant (they competed before but aren't on this year's roster yet). Only youth-eligible
        // returning candidates qualify; enrolling is what starts billing them ("candidates until kept").
        post("/registration/{congregationId}/contestants/{contestantId}/enroll") {
            val user = currentUser(users) ?: return@post
            if (!requireRegistrationFeature(user, seasons)) return@post
            val congregationId = call.parameters["congregationId"]!!
            val contestantId = call.parameters["contestantId"]!!
            if (!requireCongregationEditor(user, congregationId, seasons)) return@post
            val season = seasons.current()
            val eligible = registrations.returningContestants(congregationId, season.eventYear)
                .any { it.contestantId == contestantId && season.isEligibleReturningCandidate(it.birthdate) }
            if (!eligible) {
                return@post call.respond(
                    HttpStatusCode.Conflict,
                    ApiError("not_eligible", "That contestant isn't an eligible returning contestant this season"),
                )
            }
            val req = call.receive<EnrollContestantRequest>()
            when (registrations.enrollContestant(congregationId, season.eventYear, contestantId, req.shirtSize, req.teamId)) {
                EnrollResult.Enrolled ->
                    call.respond(registrations.find(congregationId, season.eventYear)!!.withTotal(seasons))
                EnrollResult.RosterFull ->
                    call.respond(HttpStatusCode.Conflict, ApiError("roster_full", "A team may have at most 4 contestants"))
                EnrollResult.TeamNotFound ->
                    call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such team for this registration"))
                EnrollResult.ContestantNotFound ->
                    call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such contestant"))
                EnrollResult.AlreadyEnrolled ->
                    call.respond(HttpStatusCode.Conflict, ApiError("already_enrolled", "That contestant is already on this season's roster"))
            }
        }

        // Individual (adult) contestants — adults are never on a team; they compete individually.
        post("/registration/{congregationId}/individuals") {
            val user = currentUser(users) ?: return@post
            if (!requireRegistrationFeature(user, seasons)) return@post
            val congregationId = call.parameters["congregationId"]!!
            if (!requireScopedPermission(user, Permission.TEAM_MANAGE, congregationId)) return@post
            if (!requireWindowOpen(user, seasons)) return@post
            val req = call.receive<UpsertIndividualRequest>()
            if (req.name.isBlank()) {
                return@post call.respond(HttpStatusCode.BadRequest, ApiError("invalid_individual", "Name is required"))
            }
            registrations.addIndividual(congregationId, seasons.current().eventYear, req)
            call.respond(registrations.find(congregationId, seasons.current().eventYear)!!.withTotal(seasons))
        }

        put("/registration/individuals/{individualId}") {
            val user = currentUser(users) ?: return@put
            if (!requireRegistrationFeature(user, seasons)) return@put
            val individualId = call.parameters["individualId"]!!
            val congregationId = registrations.congregationIdForIndividual(individualId)
                ?: return@put call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such individual contestant"))
            if (!requireScopedPermission(user, Permission.TEAM_MANAGE, congregationId)) return@put
            if (!requireWindowOpen(user, seasons)) return@put
            val req = call.receive<UpsertIndividualRequest>()
            if (req.name.isBlank()) {
                return@put call.respond(HttpStatusCode.BadRequest, ApiError("invalid_individual", "Name is required"))
            }
            registrations.updateIndividual(individualId, req)
            call.respond(registrations.find(congregationId, seasons.current().eventYear)!!.withTotal(seasons))
        }

        delete("/registration/individuals/{individualId}") {
            val user = currentUser(users) ?: return@delete
            if (!requireRegistrationFeature(user, seasons)) return@delete
            val individualId = call.parameters["individualId"]!!
            val congregationId = registrations.congregationIdForIndividual(individualId)
                ?: return@delete call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such individual contestant"))
            if (!requireScopedPermission(user, Permission.TEAM_MANAGE, congregationId)) return@delete
            if (!requireWindowOpen(user, seasons)) return@delete
            registrations.deleteIndividual(individualId)
            call.respond(registrations.find(congregationId, seasons.current().eventYear)!!.withTotal(seasons))
        }

        // Registered guests — attendees (mostly volunteers) who must register and pay but are not
        // contestants: never on a team, no division, no claim code. Billed at the volunteer fee,
        // or the child fee when marked as a child (ages 3–8).
        post("/registration/{congregationId}/guests") {
            val user = currentUser(users) ?: return@post
            if (!requireRegistrationFeature(user, seasons)) return@post
            val congregationId = call.parameters["congregationId"]!!
            if (!requireScopedPermission(user, Permission.TEAM_MANAGE, congregationId)) return@post
            if (!requireWindowOpen(user, seasons)) return@post
            val req = call.receive<UpsertGuestRequest>()
            if (req.name.isBlank()) {
                return@post call.respond(HttpStatusCode.BadRequest, ApiError("invalid_guest", "Name is required"))
            }
            registrations.addGuest(congregationId, seasons.current().eventYear, req)
            call.respond(registrations.find(congregationId, seasons.current().eventYear)!!.withTotal(seasons))
        }

        put("/registration/guests/{guestId}") {
            val user = currentUser(users) ?: return@put
            if (!requireRegistrationFeature(user, seasons)) return@put
            val guestId = call.parameters["guestId"]!!
            val congregationId = registrations.congregationIdForGuest(guestId)
                ?: return@put call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such guest"))
            if (!requireScopedPermission(user, Permission.TEAM_MANAGE, congregationId)) return@put
            if (!requireWindowOpen(user, seasons)) return@put
            val req = call.receive<UpsertGuestRequest>()
            if (req.name.isBlank()) {
                return@put call.respond(HttpStatusCode.BadRequest, ApiError("invalid_guest", "Name is required"))
            }
            registrations.updateGuest(guestId, req)
            call.respond(registrations.find(congregationId, seasons.current().eventYear)!!.withTotal(seasons))
        }

        delete("/registration/guests/{guestId}") {
            val user = currentUser(users) ?: return@delete
            if (!requireRegistrationFeature(user, seasons)) return@delete
            val guestId = call.parameters["guestId"]!!
            val congregationId = registrations.congregationIdForGuest(guestId)
                ?: return@delete call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such guest"))
            if (!requireScopedPermission(user, Permission.TEAM_MANAGE, congregationId)) return@delete
            if (!requireWindowOpen(user, seasons)) return@delete
            registrations.deleteGuest(guestId)
            call.respond(registrations.find(congregationId, seasons.current().eventYear)!!.withTotal(seasons))
        }

        // Claiming a roster entry (docs/gui-redesign.md, owner-account model): a contestant/parent
        // account redeems the coach-shared code, linking the entry to the account — that link is
        // what My Scores' owner scoping keys off. No window gating: claiming happens any time,
        // including after registration closes.
        post("/roster/claim") {
            val user = currentUser(users) ?: return@post
            if (!requireRegistrationFeature(user, seasons)) return@post
            val req = call.receive<ClaimEntryRequest>()
            val code = req.code.uppercase().filter { it.isLetterOrDigit() }
            if (code.length != ClaimCodes.LENGTH) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("invalid_claim_code", "Claim codes look like ABCD-2345 — check yours with your coach"),
                )
            }
            when (val result = registrations.claimEntry(code, user.id)) {
                is ClaimResult.Claimed -> call.respond(result.entry)
                ClaimResult.NotFound -> call.respond(
                    HttpStatusCode.NotFound,
                    ApiError("claim_code_not_found", "No roster entry matches that code — check it with your coach"),
                )
                ClaimResult.AlreadyClaimed -> call.respond(
                    HttpStatusCode.Conflict,
                    ApiError("already_claimed", "That entry was already claimed by another account — contact us"),
                )
            }
        }

        post("/registration/{congregationId}/submit") {
            val user = currentUser(users) ?: return@post
            if (!requireRegistrationFeature(user, seasons)) return@post
            val congregationId = call.parameters["congregationId"]!!
            if (!requireScopedPermission(user, Permission.REGISTRATION_MANAGE, congregationId)) return@post
            if (!requireWindowOpen(user, seasons)) return@post
            val season = seasons.current()
            // A multi-site season needs the site chosen before submit (a removed site unchooses).
            val current = registrations.find(congregationId, season.eventYear)
            if (season.multiSite && current != null && season.siteFor(current.siteId) == null) {
                return@post call.respond(
                    HttpStatusCode.Conflict,
                    ApiError("site_required", "Choose which event site your congregation attends before submitting"),
                )
            }
            val submitted = registrations.submit(congregationId, season.eventYear)
                ?: return@post call.respond(
                    HttpStatusCode.NotFound,
                    ApiError("not_found", "No registration to submit — add a team or an individual contestant first"),
                )
            call.respond(submitted.withTotal(seasons))
        }
    }
}

private const val INVALID_MEMBER_MESSAGE =
    "Name is required and the birthdate (YYYY-MM-DD) must land in grades 3\u201312 \u2014 adults register as " +
        "individual contestants, not on a team"

/** A team member needs a name and a birthdate implying school grade 3\u201312 this season. */
private fun UpsertRosterEntryRequest.isValid(season: SeasonDto): Boolean =
    name.isNotBlank() && (season.gradeForBirthdate(birthdate) ?: -1) in 3..12

/** A congregation code is optional (blank), but if present must be exactly two letters. */
private fun isCodeValid(code: String): Boolean {
    val c = code.trim()
    return c.isBlank() || (c.length == 2 && c.all { it.isLetter() })
}

private val INVALID_CODE_ERROR = ApiError("invalid_code", "A congregation code must be exactly two letters")
private val CODE_TAKEN_ERROR = ApiError("code_taken", "That two-letter code is already taken — pick another")

private val ZIP_REGEX = Regex("""\d{5}(-\d{4})?""")

private fun CreateCongregationRequest.isValid(): Boolean =
    name.isNotBlank() && city.isNotBlank() && mailingAddress.isNotBlank() &&
        state.trim().length == 2 && state.trim().all { it.isLetter() } &&
        zip.trim().matches(ZIP_REGEX)

/** Required-field rules for an update — the same as create (the code is validated separately). */
private fun UpdateCongregationRequest.isValid(): Boolean =
    name.isNotBlank() && city.isNotBlank() && mailingAddress.isNotBlank() &&
        state.trim().length == 2 && state.trim().all { it.isLetter() } &&
        zip.trim().matches(ZIP_REGEX)

/** Decorates a registration with its total (contestants + guests) from the current season's fees. */
internal fun RegistrationDto.withTotal(seasons: SeasonRepository): RegistrationDto =
    copy(totalCents = registrationTotalCents(seasons.current(), this))

/**
 * Passes when the caller may edit [congregationId]'s teams/roster: an event-wide REGISTRATION_MANAGE
 * holder (registrar or admin) any time — they place leftover unassigned contestants after coaches
 * submit, so they aren't window-gated — or the congregation's own TEAM_MANAGE coach while the
 * registration window is open. Responds 403/409 and returns false otherwise.
 */
private suspend fun RoutingContext.requireCongregationEditor(
    user: UserRecord,
    congregationId: String,
    seasons: SeasonRepository,
): Boolean {
    if (hasEventWidePermission(user.roles, Permission.REGISTRATION_MANAGE)) return true
    if (!requireScopedPermission(user, Permission.TEAM_MANAGE, congregationId)) return false
    return requireWindowOpen(user, seasons)
}

/**
 * Every registration endpoint (here and in AdminRegistrationRoutes) stays dark until the season's
 * `registrationEnabled` feature toggle is switched on — global admins are exempt so the feature
 * can be tested in production before launch.
 */
internal suspend fun RoutingContext.requireRegistrationFeature(
    user: UserRecord,
    seasons: SeasonRepository,
): Boolean = requireFeatureEnabled(user, seasons.current().registrationEnabled, "Registration")

/**
 * Rejects the mutation with 409 while the registration window isn't open — except for admins,
 * who may fix registrations after the deadline.
 */
private suspend fun RoutingContext.requireWindowOpen(user: UserRecord, seasons: SeasonRepository): Boolean {
    if (user.isAdmin) return true
    return when (seasons.current().registrationWindowState()) {
        RegistrationWindowState.OPEN -> true
        RegistrationWindowState.NOT_YET_OPEN -> {
            call.respond(HttpStatusCode.Conflict, ApiError("registration_not_open", "Registration hasn't opened yet"))
            false
        }
        RegistrationWindowState.CLOSED -> {
            call.respond(HttpStatusCode.Conflict, ApiError("registration_closed", "Registration has closed for this season"))
            false
        }
    }
}
