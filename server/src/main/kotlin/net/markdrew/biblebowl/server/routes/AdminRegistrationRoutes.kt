package net.markdrew.biblebowl.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import net.markdrew.biblebowl.api.ApiError
import net.markdrew.biblebowl.api.CoachContactDto
import net.markdrew.biblebowl.api.Permission
import net.markdrew.biblebowl.api.RegistrationDeskResponse
import net.markdrew.biblebowl.api.RegistrationDeskRowDto
import net.markdrew.biblebowl.api.SetPaidRequest
import net.markdrew.biblebowl.api.isEligibleReturningCandidate
import net.markdrew.biblebowl.server.data.CongregationRepository
import net.markdrew.biblebowl.server.data.RegistrationRepository
import net.markdrew.biblebowl.server.data.SeasonRepository
import net.markdrew.biblebowl.server.data.UserRepository
import net.markdrew.biblebowl.server.security.currentUser
import net.markdrew.biblebowl.server.security.requireEventWidePermission

/**
 * The registration desk (docs/gui-redesign.md §5E): a cross-congregation view of the current
 * season's registrations for registrars/admins. Requires an event-wide REGISTRATION_MANAGE grant —
 * a coach's congregation-scoped grant deliberately does not qualify. No window gating: desk
 * workers record payments whenever checks arrive.
 */
fun Route.adminRegistrationRoutes(
    users: UserRepository,
    seasons: SeasonRepository,
    congregations: CongregationRepository,
    registrations: RegistrationRepository,
) {
    authenticate {
        get("/admin/registrations") {
            val user = currentUser(users) ?: return@get
            if (!requireRegistrationFeature(user, seasons)) return@get
            if (!requireEventWidePermission(user, Permission.REGISTRATION_MANAGE)) return@get
            val season = seasons.current()
            val regsByCongregation = registrations.listForSeason(season.eventYear)
                .associateBy { it.congregation.id }
            val allCongregations = congregations.listAll()
            val coaches = users.coachesByCongregation(allCongregations.map { it.id })
            call.respond(
                RegistrationDeskResponse(
                    seasonYear = season.eventYear,
                    rows = allCongregations.map { cong ->
                        RegistrationDeskRowDto(
                            congregation = cong,
                            registration = regsByCongregation[cong.id]?.withTotal(seasons),
                            coaches = coaches[cong.id].orEmpty()
                                .map { CoachContactDto(it.displayName, it.email, it.contact) },
                            returningCandidates = registrations.returningContestants(cong.id, season.eventYear)
                                .filter { season.isEligibleReturningCandidate(it.birthdate) },
                        )
                    },
                )
            )
        }

        put("/admin/registrations/{registrationId}/paid") {
            val user = currentUser(users) ?: return@put
            if (!requireRegistrationFeature(user, seasons)) return@put
            if (!requireEventWidePermission(user, Permission.REGISTRATION_MANAGE)) return@put
            val registrationId = call.parameters["registrationId"]!!
            val req = call.receive<SetPaidRequest>()
            val updated = registrations.setPaid(
                registrationId,
                if (req.paid) System.currentTimeMillis() else null,
            ) ?: return@put call.respond(
                HttpStatusCode.NotFound,
                ApiError("not_found", "No such registration"),
            )
            call.respond(updated.withTotal(seasons))
        }
    }
}
