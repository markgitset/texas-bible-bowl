package net.markdrew.biblebowl.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import net.markdrew.biblebowl.api.ApiError
import net.markdrew.biblebowl.api.ClaimPersonRequest
import net.markdrew.biblebowl.api.ClaimPersonResponse
import net.markdrew.biblebowl.api.MyPeopleResponse
import net.markdrew.biblebowl.api.PersonRelation
import net.markdrew.biblebowl.server.data.ClaimCodes
import net.markdrew.biblebowl.server.data.PeopleRepository
import net.markdrew.biblebowl.server.data.PersonClaimResult
import net.markdrew.biblebowl.server.data.SeasonRepository
import net.markdrew.biblebowl.server.data.UserRepository
import net.markdrew.biblebowl.server.security.currentUser

/**
 * Person-centric registration API (schema redesign phase 4) — additive alongside the roster-entry
 * endpoints, which stay until the clients migrate.
 *
 * - `POST /people/claim` redeems a person's coach-shared code. The SELF-vs-MANAGED decision is
 *   automatic: an adult person whose email matches the account becomes the account's own identity
 *   (`users.person_id`); anyone else (a child, or an adult with a different email) is managed. Both
 *   set `people.managed_by_user_id`, so `GET /people/mine` returns them either way.
 * - `GET /people/mine` lists every person the account is or manages, with their participations.
 *
 * Gated by the registration feature toggle like the rest of the registration area; no window
 * gating (claiming happens any time, including after registration closes).
 */
fun Route.peopleRoutes(
    users: UserRepository,
    seasons: SeasonRepository,
    people: PeopleRepository,
) {
    authenticate {
        post("/people/claim") {
            val user = currentUser(users) ?: return@post
            if (!requireRegistrationFeature(user, seasons)) return@post
            val req = call.receive<ClaimPersonRequest>()
            val code = req.code.uppercase().filter { it.isLetterOrDigit() }
            if (code.length != ClaimCodes.LENGTH) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("invalid_claim_code", "Claim codes look like ABCD-2345 — check yours with your coach"),
                )
            }
            when (val result = people.claimPerson(code, user.id)) {
                is PersonClaimResult.Claimed -> {
                    val person = result.person
                    // An adult person whose email matches this account IS this account (self-claim);
                    // everyone else is managed (a parent claiming a child).
                    val isSelf = person.isAdult &&
                        person.email?.equals(user.email, ignoreCase = true) == true
                    val relation = if (isSelf) PersonRelation.SELF else PersonRelation.MANAGED
                    if (isSelf) users.linkPerson(user.id, person.id)
                    call.respond(ClaimPersonResponse(person.copy(relation = relation), relation))
                }
                PersonClaimResult.NotFound -> call.respond(
                    HttpStatusCode.NotFound,
                    ApiError("claim_code_not_found", "No one matches that code — check it with your coach"),
                )
                PersonClaimResult.AlreadyClaimed -> call.respond(
                    HttpStatusCode.Conflict,
                    ApiError("already_claimed", "That person was already claimed by another account — contact us"),
                )
            }
        }

        get("/people/mine") {
            val user = currentUser(users) ?: return@get
            if (!requireRegistrationFeature(user, seasons)) return@get
            val withRelation = people.peopleManagedBy(user.id).map { pw ->
                val relation = if (pw.person.id == user.personId) PersonRelation.SELF else PersonRelation.MANAGED
                pw.copy(person = pw.person.copy(relation = relation))
            }
            call.respond(MyPeopleResponse(withRelation))
        }
    }
}
