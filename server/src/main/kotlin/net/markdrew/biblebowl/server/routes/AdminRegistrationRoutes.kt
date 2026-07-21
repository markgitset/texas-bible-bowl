package net.markdrew.biblebowl.server.routes

import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.markdrew.biblebowl.api.ApiError
import net.markdrew.biblebowl.api.CoachContactDto
import net.markdrew.biblebowl.api.Permission
import net.markdrew.biblebowl.api.RegistrationDeskResponse
import net.markdrew.biblebowl.api.RegistrationDeskRowDto
import net.markdrew.biblebowl.api.SetPaidRequest
import net.markdrew.biblebowl.api.division
import net.markdrew.biblebowl.api.isEligibleReturningCandidate
import net.markdrew.biblebowl.api.missingTesterIds
import net.markdrew.biblebowl.api.ownEntries
import net.markdrew.biblebowl.api.siteFor
import net.markdrew.biblebowl.generation.typst.Nametag
import net.markdrew.biblebowl.generation.typst.NametagSheet
import net.markdrew.biblebowl.generation.typst.nametagsTypst
import net.markdrew.biblebowl.server.data.CongregationRepository
import net.markdrew.biblebowl.server.data.RegistrationRepository
import net.markdrew.biblebowl.server.data.SeasonRepository
import net.markdrew.biblebowl.server.data.UserRepository
import net.markdrew.biblebowl.server.security.currentUser
import net.markdrew.biblebowl.server.security.requireEventWidePermission
import net.markdrew.biblebowl.server.typst.TypstCompiler
import net.markdrew.biblebowl.server.typst.TypstException

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

        // GET /admin/registrations/nametags.pdf?siteId=<EventSiteDto.id>
        //
        // Printable nametags (item 14, F8 — replaces the workbook's four nametag tabs): 4×3in
        // badges per site for every attendee — testers (with division and tester ID) and guests.
        // Generating first assigns any missing tester IDs (item 13's ID half) and persists them,
        // so the numbering is stable across reprints and late registrations only extend it.
        // Authenticated (attendee names include minors') — unlike the public /generate PDFs.
        get("/admin/registrations/nametags.pdf") {
            val user = currentUser(users) ?: return@get
            if (!requireRegistrationFeature(user, seasons)) return@get
            if (!requireEventWidePermission(user, Permission.REGISTRATION_MANAGE)) return@get
            val season = seasons.current()
            val siteIdParam = call.request.queryParameters["siteId"]

            var regs = registrations.listForSeason(season.eventYear)
            val missing = missingTesterIds(season, regs)
            if (missing.isNotEmpty()) {
                registrations.setTesterIds(missing)
                regs = registrations.listForSeason(season.eventYear)
            }

            val sheets = regs.groupBy { season.siteFor(it.siteId) }.entries
                .filter { (site, _) -> siteIdParam == null || site?.id == siteIdParam }
                .sortedBy { (site, _) -> site?.name?.lowercase() ?: "" }
                .map { (site, siteRegs) ->
                    val heading = listOfNotNull("Texas Bible Bowl ${season.eventYear}", site?.name)
                        .joinToString(" — ")
                    val tags = siteRegs.sortedBy { it.congregation.name.lowercase() }.flatMap { reg ->
                        reg.ownEntries.sortedBy { it.name.lowercase() }.map { entry ->
                            Nametag(
                                name = entry.name,
                                congregation = reg.congregation.name,
                                role = entry.division(season)?.displayName ?: "",
                                testerId = entry.testerId,
                            )
                        } + reg.guests.sortedBy { it.name.lowercase() }.map { guest ->
                            Nametag(
                                name = guest.name,
                                congregation = reg.congregation.name,
                                role = if (guest.positions.isNotEmpty()) "Volunteer" else "Guest",
                            )
                        }
                    }
                    NametagSheet(heading, tags)
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
