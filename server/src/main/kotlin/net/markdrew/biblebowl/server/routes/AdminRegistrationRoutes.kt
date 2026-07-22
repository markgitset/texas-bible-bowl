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
 * season's registrations for registrars/admins (`?year=` reviews a past season's data instead).
 * Requires an event-wide REGISTRATION_MANAGE grant — a coach's congregation-scoped grant
 * deliberately does not qualify. No window gating: desk workers record payments whenever checks
 * arrive.
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
            val current = seasons.current()
            // `?year=` reviews a past season's data; unset (or the current year) is the live desk.
            val year = call.request.queryParameters["year"]?.takeIf { it.isNotBlank() } ?: current.eventYear.toString()
            val isCurrentYear = year == current.eventYear.toString()
            // A past year's totals use that season's stored fees; a year with no season row (e.g.
            // workbook-seeded history) gets no totals rather than misleading current-fee ones.
            val seasonForYear = if (isCurrentYear) current else seasons.byYear(year)
            val regsByCongregation = registrations.listForSeason(year)
                .associateBy { it.congregation.id }
            val allCongregations = congregations.listAll()
            val coaches = users.coachesByCongregation(allCongregations.map { it.id })
            call.respond(
                RegistrationDeskResponse(
                    seasonYear = year,
                    availableYears = (registrations.seasonYears() + current.eventYear.toString())
                        .distinct().sortedDescending(),
                    rows = allCongregations.map { cong ->
                        RegistrationDeskRowDto(
                            congregation = cong,
                            registration = regsByCongregation[cong.id]
                                ?.let { reg -> seasonForYear?.let { reg.withTotal(it) } ?: reg },
                            coaches = coaches[cong.id].orEmpty()
                                .map { CoachContactDto(it.displayName, it.email, it.contact) },
                            // Enrollment only targets the current season — a past-year review offers none.
                            returningCandidates = if (!isCurrentYear) emptyList() else
                                registrations.returningContestants(cong.id, year)
                                    .filter { current.isEligibleReturningCandidate(it) },
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
