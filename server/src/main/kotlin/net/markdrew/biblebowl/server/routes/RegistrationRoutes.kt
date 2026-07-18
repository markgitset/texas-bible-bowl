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
import net.markdrew.biblebowl.api.CreateCongregationRequest
import net.markdrew.biblebowl.api.MyRegistrationResponse
import net.markdrew.biblebowl.api.Permission
import net.markdrew.biblebowl.api.RegistrationDto
import net.markdrew.biblebowl.api.Role
import net.markdrew.biblebowl.api.RoleGrant
import net.markdrew.biblebowl.api.ScopeType
import net.markdrew.biblebowl.api.UpsertIndividualRequest
import net.markdrew.biblebowl.api.UpsertRosterEntryRequest
import net.markdrew.biblebowl.api.UpsertTeamRequest
import net.markdrew.biblebowl.api.coachedCongregationIds
import net.markdrew.biblebowl.api.contestantCount
import net.markdrew.biblebowl.api.registrationTotalCents
import net.markdrew.biblebowl.server.RegistrationWindowState
import net.markdrew.biblebowl.server.data.AddMemberResult
import net.markdrew.biblebowl.server.data.CongregationRepository
import net.markdrew.biblebowl.server.data.RegistrationRepository
import net.markdrew.biblebowl.server.data.SeasonRepository
import net.markdrew.biblebowl.server.data.UserRecord
import net.markdrew.biblebowl.server.data.UserRepository
import net.markdrew.biblebowl.server.registrationWindowState
import net.markdrew.biblebowl.server.security.currentUser
import net.markdrew.biblebowl.server.security.isAdmin
import net.markdrew.biblebowl.server.security.requireScopedPermission

/**
 * Registration (docs/gui-redesign.md §5E): congregations, teams, rosters, and the coach flow's
 * resume/submit endpoints. Everything requires sign-in; mutations additionally require the
 * congregation-scoped grant and (except congregation creation, which is onboarding) an open
 * registration window. Creating a congregation self-serve grants the creator COACH scoped to it;
 * claiming an existing one goes through an admin (the client shows "contact us" on the 409).
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
            val created = congregations.create(req.copy(state = req.state.trim().uppercase()), user.id)
                ?: return@post call.respond(
                    HttpStatusCode.Conflict,
                    ApiError(
                        "congregation_exists",
                        "That congregation is already registered — contact us to be added as its coach",
                    ),
                )
            users.addRoleGrant(user.id, RoleGrant(Role.COACH, ScopeType.CONGREGATION, created.id))
            call.respond(HttpStatusCode.Created, created)
        }

        get("/congregations") {
            if (currentUser(users) == null) return@get
            call.respond(congregations.search(call.request.queryParameters["query"] ?: ""))
        }

        get("/registration/mine") {
            val user = currentUser(users) ?: return@get
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
            val congregationId = call.parameters["congregationId"]!!
            if (!requireScopedPermission(user, Permission.TEAM_MANAGE, congregationId)) return@post
            if (!requireWindowOpen(user, seasons)) return@post
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
            val teamId = call.parameters["teamId"]!!
            val congregationId = registrations.congregationIdForTeam(teamId)
                ?: return@post call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such team"))
            if (!requireScopedPermission(user, Permission.TEAM_MANAGE, congregationId)) return@post
            if (!requireWindowOpen(user, seasons)) return@post
            val req = call.receive<UpsertRosterEntryRequest>()
            if (!req.isValid()) {
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
            val memberId = call.parameters["memberId"]!!
            val congregationId = registrations.congregationIdForMember(memberId)
                ?: return@put call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such roster entry"))
            if (!requireScopedPermission(user, Permission.TEAM_MANAGE, congregationId)) return@put
            if (!requireWindowOpen(user, seasons)) return@put
            val req = call.receive<UpsertRosterEntryRequest>()
            if (!req.isValid()) {
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
            val memberId = call.parameters["memberId"]!!
            val congregationId = registrations.congregationIdForMember(memberId)
                ?: return@delete call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such roster entry"))
            if (!requireScopedPermission(user, Permission.TEAM_MANAGE, congregationId)) return@delete
            if (!requireWindowOpen(user, seasons)) return@delete
            registrations.deleteMember(memberId)
            call.respond(registrations.find(congregationId, seasons.current().eventYear)!!.withTotal(seasons))
        }

        // Individual (adult) contestants — adults are never on a team; they compete individually.
        post("/registration/{congregationId}/individuals") {
            val user = currentUser(users) ?: return@post
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
            val individualId = call.parameters["individualId"]!!
            val congregationId = registrations.congregationIdForIndividual(individualId)
                ?: return@delete call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such individual contestant"))
            if (!requireScopedPermission(user, Permission.TEAM_MANAGE, congregationId)) return@delete
            if (!requireWindowOpen(user, seasons)) return@delete
            registrations.deleteIndividual(individualId)
            call.respond(registrations.find(congregationId, seasons.current().eventYear)!!.withTotal(seasons))
        }

        post("/registration/{congregationId}/submit") {
            val user = currentUser(users) ?: return@post
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
    "Name is required and grade must be 3–12 — adults register as individual contestants, not on a team"

private fun UpsertRosterEntryRequest.isValid(): Boolean =
    name.isNotBlank() && grade in 3..12

private val ZIP_REGEX = Regex("""\d{5}(-\d{4})?""")

private fun CreateCongregationRequest.isValid(): Boolean =
    name.isNotBlank() && city.isNotBlank() && mailingAddress.isNotBlank() &&
        state.trim().length == 2 && state.trim().all { it.isLetter() } &&
        zip.trim().matches(ZIP_REGEX)

/** Decorates a registration with the contestant total computed from the current season's fees. */
private fun RegistrationDto.withTotal(seasons: SeasonRepository): RegistrationDto =
    copy(totalCents = registrationTotalCents(seasons.current(), contestantCount))

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
