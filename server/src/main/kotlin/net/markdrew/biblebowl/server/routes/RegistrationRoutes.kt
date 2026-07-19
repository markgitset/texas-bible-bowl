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
import net.markdrew.biblebowl.api.MyRegistrationResponse
import net.markdrew.biblebowl.api.Permission
import net.markdrew.biblebowl.api.RegistrationDto
import net.markdrew.biblebowl.api.Role
import net.markdrew.biblebowl.api.RoleGrant
import net.markdrew.biblebowl.api.ScopeType
import net.markdrew.biblebowl.api.SeasonDto
import net.markdrew.biblebowl.api.UpdateCongregationRequest
import net.markdrew.biblebowl.api.UpsertIndividualRequest
import net.markdrew.biblebowl.api.UpsertRosterEntryRequest
import net.markdrew.biblebowl.api.UpsertTeamRequest
import net.markdrew.biblebowl.api.coachedCongregationIds
import net.markdrew.biblebowl.api.contestantCount
import net.markdrew.biblebowl.api.gradeForBirthdate
import net.markdrew.biblebowl.api.hasEventWidePermission
import net.markdrew.biblebowl.api.registrationTotalCents
import net.markdrew.biblebowl.api.ClaimEntryRequest
import net.markdrew.biblebowl.server.RegistrationWindowState
import net.markdrew.biblebowl.server.data.AddMemberResult
import net.markdrew.biblebowl.server.data.AssignResult
import net.markdrew.biblebowl.server.data.ClaimCodes
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
            val registration = coached.firstOrNull()
                ?.let { registrations.find(it.id, season.eventYear) }
                ?.withTotal(seasons)
            call.respond(
                MyRegistrationResponse(
                    congregations = coached,
                    registration = registration,
                    windowOpen = season.registrationWindowState() == RegistrationWindowState.OPEN,
                )
            )
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
        put("/registration/members/{memberId}/team") {
            val user = currentUser(users) ?: return@put
            if (!requireRegistrationFeature(user, seasons)) return@put
            val memberId = call.parameters["memberId"]!!
            val congregationId = registrations.congregationIdForMember(memberId)
                ?: return@put call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such roster entry"))
            if (!requireCongregationEditor(user, congregationId, seasons)) return@put
            val req = call.receive<AssignMemberTeamRequest>()
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
            val submitted = registrations.submit(congregationId, seasons.current().eventYear)
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

/** Decorates a registration with the contestant total computed from the current season's fees. */
internal fun RegistrationDto.withTotal(seasons: SeasonRepository): RegistrationDto =
    copy(totalCents = registrationTotalCents(seasons.current(), contestantCount))

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
