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
import net.markdrew.biblebowl.api.AddCabinAssignmentRequest
import net.markdrew.biblebowl.api.ApiError
import net.markdrew.biblebowl.api.HousingResponse
import net.markdrew.biblebowl.api.Permission
import net.markdrew.biblebowl.api.SetCheckoutDutyRequest
import net.markdrew.biblebowl.api.UpsertCabinRequest
import net.markdrew.biblebowl.api.multiSite
import net.markdrew.biblebowl.server.data.CabinResult
import net.markdrew.biblebowl.server.data.CongregationRepository
import net.markdrew.biblebowl.server.data.HousingRepository
import net.markdrew.biblebowl.server.data.SeasonRepository
import net.markdrew.biblebowl.server.data.UserRepository
import net.markdrew.biblebowl.server.security.currentUser
import net.markdrew.biblebowl.server.security.requireEventWidePermission

/**
 * Housing / cabin assignments (item 15, F9; replaces the workbook's `Housing Assignments` and
 * `Check out assignments` tabs): a thin event-ops tool — cabins per season/site, free-form
 * assignment rows, and the per-congregation check-out duty roster. Gated exactly like the
 * registration desk: the registration feature toggle plus an event-wide REGISTRATION_MANAGE
 * grant; no window gating (housing is arranged after registrations close).
 */
fun Route.housingRoutes(
    users: UserRepository,
    seasons: SeasonRepository,
    congregations: CongregationRepository,
    housing: HousingRepository,
) {
    /** The full housing picture with congregation names resolved onto assignments and duties. */
    fun response(seasonYear: String): HousingResponse {
        val cabins = housing.listCabins(seasonYear)
        val duties = housing.listDuties(seasonYear)
        val names = congregations
            .findByIds((cabins.flatMap { c -> c.assignments.mapNotNull { it.congregationId } } +
                duties.map { it.congregationId }).toSet())
            .associate { it.id to it.name }
        return HousingResponse(
            seasonYear = seasonYear,
            cabins = cabins.map { cabin ->
                cabin.copy(assignments = cabin.assignments.map { it.copy(congregationName = names[it.congregationId]) })
            },
            duties = duties.map { it.copy(congregationName = names[it.congregationId].orEmpty()) }
                .sortedBy { it.congregationName.lowercase() },
        )
    }

    suspend fun RoutingContext.gate(): String? {
        val user = currentUser(users) ?: return null
        if (!requireRegistrationFeature(user, seasons)) return null
        if (!requireEventWidePermission(user, Permission.REGISTRATION_MANAGE)) return null
        return seasons.current().eventYear
    }

    /** Validates a cabin upsert; responds and returns false when invalid. */
    suspend fun RoutingContext.validCabin(req: UpsertCabinRequest): Boolean {
        val season = seasons.current()
        if (req.name.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, ApiError("invalid_cabin", "A cabin needs a name"))
            return false
        }
        if (req.capacity != null && req.capacity!! < 0) {
            call.respond(HttpStatusCode.BadRequest, ApiError("invalid_cabin", "Capacity can't be negative"))
            return false
        }
        // On a multi-site season the site is required and must be one of the season's sites; a
        // single-site season never surfaces the choice (null, like RegistrationDto.siteId).
        if (season.multiSite && season.sites.none { it.id == req.siteId }) {
            call.respond(HttpStatusCode.BadRequest, ApiError("invalid_cabin", "Pick which event site the cabin is at"))
            return false
        }
        return true
    }

    authenticate {
        get("/admin/housing") {
            val seasonYear = gate() ?: return@get
            call.respond(response(seasonYear))
        }

        post("/admin/housing/cabins") {
            val seasonYear = gate() ?: return@post
            val req = call.receive<UpsertCabinRequest>()
            if (!validCabin(req)) return@post
            when (housing.addCabin(seasonYear, req)) {
                is CabinResult.Ok -> call.respond(response(seasonYear))
                CabinResult.NameTaken -> call.respond(
                    HttpStatusCode.Conflict,
                    ApiError("cabin_name_taken", "A cabin with that name already exists at this site"),
                )
                CabinResult.NotFound -> error("unreachable: addCabin never reports NotFound")
            }
        }

        put("/admin/housing/cabins/{cabinId}") {
            val seasonYear = gate() ?: return@put
            val req = call.receive<UpsertCabinRequest>()
            if (!validCabin(req)) return@put
            when (housing.updateCabin(call.parameters["cabinId"]!!, req)) {
                is CabinResult.Ok -> call.respond(response(seasonYear))
                CabinResult.NameTaken -> call.respond(
                    HttpStatusCode.Conflict,
                    ApiError("cabin_name_taken", "A cabin with that name already exists at this site"),
                )
                CabinResult.NotFound -> call.respond(
                    HttpStatusCode.NotFound,
                    ApiError("not_found", "No such cabin"),
                )
            }
        }

        delete("/admin/housing/cabins/{cabinId}") {
            val seasonYear = gate() ?: return@delete
            if (!housing.deleteCabin(call.parameters["cabinId"]!!)) {
                return@delete call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such cabin"))
            }
            call.respond(response(seasonYear))
        }

        post("/admin/housing/cabins/{cabinId}/assignments") {
            val seasonYear = gate() ?: return@post
            val req = call.receive<AddCabinAssignmentRequest>()
            if (req.congregationId == null && req.label.isBlank()) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("invalid_assignment", "Pick a congregation or enter a label"),
                )
            }
            if (req.congregationId != null && congregations.findById(req.congregationId!!) == null) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("invalid_assignment", "No such congregation"),
                )
            }
            housing.addAssignment(call.parameters["cabinId"]!!, req)
                ?: return@post call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such cabin"))
            call.respond(response(seasonYear))
        }

        delete("/admin/housing/assignments/{assignmentId}") {
            val seasonYear = gate() ?: return@delete
            if (!housing.deleteAssignment(call.parameters["assignmentId"]!!)) {
                return@delete call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such assignment"))
            }
            call.respond(response(seasonYear))
        }

        // Sets (non-blank) or clears (blank) the congregation's check-out duty adult.
        put("/admin/housing/checkout/{congregationId}") {
            val seasonYear = gate() ?: return@put
            val congregationId = call.parameters["congregationId"]!!
            if (congregations.findById(congregationId) == null) {
                return@put call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such congregation"))
            }
            val req = call.receive<SetCheckoutDutyRequest>()
            housing.setDuty(seasonYear, congregationId, req.adultName)
            call.respond(response(seasonYear))
        }
    }
}
