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
import net.markdrew.biblebowl.api.AddTribeLeaderRequest
import net.markdrew.biblebowl.api.ApiError
import net.markdrew.biblebowl.api.Permission
import net.markdrew.biblebowl.api.TribesResponse
import net.markdrew.biblebowl.api.UpsertTribeRequest
import net.markdrew.biblebowl.api.multiSite
import net.markdrew.biblebowl.server.data.AddLeaderResult
import net.markdrew.biblebowl.server.data.SeasonRepository
import net.markdrew.biblebowl.server.data.TribeRepository
import net.markdrew.biblebowl.server.data.TribeResult
import net.markdrew.biblebowl.server.data.UserRepository
import net.markdrew.biblebowl.server.security.currentUser
import net.markdrew.biblebowl.server.security.requireEventWidePermission

/**
 * Tribes & tribe leaders (item 16, F10; replaces the workbook's `Tribe leader assignment` tab):
 * a thin event-ops tool — tribes per season/site (2026: color names, two leaders each) with
 * free-form leader names, the picker seeded client-side from item 8's willing adults. Gated
 * exactly like housing: the registration feature toggle plus an event-wide REGISTRATION_MANAGE
 * grant; no window gating (tribes are arranged after registrations close).
 */
fun Route.tribeRoutes(
    users: UserRepository,
    seasons: SeasonRepository,
    tribes: TribeRepository,
) {
    fun response(seasonYear: String): TribesResponse =
        TribesResponse(seasonYear = seasonYear, tribes = tribes.listTribes(seasonYear))

    suspend fun RoutingContext.gate(): String? {
        val user = currentUser(users) ?: return null
        if (!requireRegistrationFeature(user, seasons)) return null
        if (!requireEventWidePermission(user, Permission.REGISTRATION_MANAGE)) return null
        return seasons.current().eventYear.toString()
    }

    /** Validates a tribe upsert; responds and returns false when invalid. */
    suspend fun RoutingContext.validTribe(req: UpsertTribeRequest): Boolean {
        val season = seasons.current()
        if (req.name.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, ApiError("invalid_tribe", "A tribe needs a name"))
            return false
        }
        // On a multi-site season the site is required and must be one of the season's sites; a
        // single-site season never surfaces the choice (null, like CabinDto.siteId).
        if (season.multiSite && season.sites.none { it.id == req.siteId }) {
            call.respond(HttpStatusCode.BadRequest, ApiError("invalid_tribe", "Pick which event site the tribe is at"))
            return false
        }
        return true
    }

    authenticate {
        get("/admin/tribes") {
            val seasonYear = gate() ?: return@get
            call.respond(response(seasonYear))
        }

        post("/admin/tribes") {
            val seasonYear = gate() ?: return@post
            val req = call.receive<UpsertTribeRequest>()
            if (!validTribe(req)) return@post
            when (tribes.addTribe(seasonYear, req)) {
                is TribeResult.Ok -> call.respond(response(seasonYear))
                TribeResult.NameTaken -> call.respond(
                    HttpStatusCode.Conflict,
                    ApiError("tribe_name_taken", "A tribe with that name already exists at this site"),
                )
                TribeResult.NotFound -> error("unreachable: addTribe never reports NotFound")
            }
        }

        put("/admin/tribes/{tribeId}") {
            val seasonYear = gate() ?: return@put
            val req = call.receive<UpsertTribeRequest>()
            if (!validTribe(req)) return@put
            when (tribes.updateTribe(call.parameters["tribeId"]!!, req)) {
                is TribeResult.Ok -> call.respond(response(seasonYear))
                TribeResult.NameTaken -> call.respond(
                    HttpStatusCode.Conflict,
                    ApiError("tribe_name_taken", "A tribe with that name already exists at this site"),
                )
                TribeResult.NotFound -> call.respond(
                    HttpStatusCode.NotFound,
                    ApiError("not_found", "No such tribe"),
                )
            }
        }

        delete("/admin/tribes/{tribeId}") {
            val seasonYear = gate() ?: return@delete
            if (!tribes.deleteTribe(call.parameters["tribeId"]!!)) {
                return@delete call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such tribe"))
            }
            call.respond(response(seasonYear))
        }

        post("/admin/tribes/{tribeId}/leaders") {
            val seasonYear = gate() ?: return@post
            val req = call.receive<AddTribeLeaderRequest>()
            if (req.name.isBlank()) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("invalid_leader", "A leader needs a name"),
                )
            }
            when (tribes.addLeader(call.parameters["tribeId"]!!, req.name)) {
                is AddLeaderResult.Added -> call.respond(response(seasonYear))
                AddLeaderResult.TribeNotFound ->
                    return@post call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such tribe"))
                AddLeaderResult.LeaderNotRegistered ->
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        ApiError("leader_not_registered", "A tribe leader must be a registered attendee this season"),
                    )
            }
        }

        delete("/admin/tribes/leaders/{leaderId}") {
            val seasonYear = gate() ?: return@delete
            if (!tribes.deleteLeader(call.parameters["leaderId"]!!)) {
                return@delete call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such leader"))
            }
            call.respond(response(seasonYear))
        }
    }
}
