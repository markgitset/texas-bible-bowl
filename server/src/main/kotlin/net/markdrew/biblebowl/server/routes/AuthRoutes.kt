package net.markdrew.biblebowl.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.put
import net.markdrew.biblebowl.api.ApiError
import net.markdrew.biblebowl.api.AuthResponse
import net.markdrew.biblebowl.api.LoginRequest
import net.markdrew.biblebowl.api.RegisterRequest
import net.markdrew.biblebowl.api.Role
import net.markdrew.biblebowl.api.RoleGrant
import net.markdrew.biblebowl.api.ScopeType
import net.markdrew.biblebowl.api.UpdateProfileRequest
import net.markdrew.biblebowl.api.isValidBirthdate
import net.markdrew.biblebowl.server.data.UserRepository
import net.markdrew.biblebowl.server.security.JwtService
import net.markdrew.biblebowl.server.security.Passwords
import net.markdrew.biblebowl.server.security.currentUser
import net.markdrew.biblebowl.server.security.toDto

fun Route.authRoutes(users: UserRepository, jwt: JwtService) {
    route("/auth") {
        post("/register") {
            val req = call.receive<RegisterRequest>()
            if (req.email.isBlank() || req.password.length < 8) {
                call.respond(HttpStatusCode.BadRequest, ApiError("invalid", "Email required, password >= 8 chars"))
                return@post
            }
            if (!validBirthdateChoice(req.adult, req.birthdate)) {
                call.respond(HttpStatusCode.BadRequest, birthdateError())
                return@post
            }
            if (users.findByEmail(req.email) != null) {
                call.respond(HttpStatusCode.Conflict, ApiError("email_taken", "Email already registered"))
                return@post
            }
            val user = users.create(
                email = req.email,
                displayName = req.displayName,
                birthdate = req.birthdate.takeUnless { req.adult }, // adults don't give one
                adult = req.adult,
                passwordHash = Passwords.hash(req.password),
                roles = listOf(RoleGrant(Role.CONTESTANT)), // everyone starts as a contestant
            )
            // A coach email known from the workbook seed (item 17, F13) gets its congregation-scoped
            // COACH role the moment the account exists — no admin hand-granting for returning coaches.
            users.consumePendingCoachGrants(user.email).forEach { congregationId ->
                users.addRoleGrant(user.id, RoleGrant(Role.COACH, ScopeType.CONGREGATION, congregationId))
            }
            val token = jwt.issue(user.id, user.email)
            call.respond(HttpStatusCode.Created, AuthResponse(token, user.toDto()))
        }

        post("/login") {
            val req = call.receive<LoginRequest>()
            val user = users.findByEmail(req.email)
            if (user == null || !Passwords.verify(req.password, user.passwordHash)) {
                call.respond(HttpStatusCode.Unauthorized, ApiError("bad_credentials", "Invalid email or password"))
                return@post
            }
            val token = jwt.issue(user.id, user.email)
            call.respond(AuthResponse(token, user.toDto()))
        }

        authenticate {
            get("/me") {
                val user = currentUser(users) ?: return@get
                call.respond(user.toDto())
            }

            /** Self-service profile edit: display name, birthdate/adult eligibility, contact info. */
            put("/me") {
                val user = currentUser(users) ?: return@put
                val req = call.receive<UpdateProfileRequest>()
                if (req.displayName.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, ApiError("invalid_profile", "Display name is required"))
                    return@put
                }
                if (!validBirthdateChoice(req.adult, req.birthdate)) {
                    call.respond(HttpStatusCode.BadRequest, birthdateError())
                    return@put
                }
                val updated = users.updateProfile(
                    userId = user.id,
                    displayName = req.displayName.trim(),
                    birthdate = req.birthdate.takeUnless { req.adult },
                    adult = req.adult,
                    // Omitted contact (older clients) keeps what's stored; sent-but-empty clears it.
                    contact = (req.contact ?: user.contact)?.takeUnless { it.isEmpty() },
                ) ?: run {
                    call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such user"))
                    return@put
                }
                call.respond(updated.toDto())
            }
        }
    }
}

/** Everyone is either an adult (no birthdate collected) or supplies a valid birthdate. */
private fun validBirthdateChoice(adult: Boolean, birthdate: String?): Boolean =
    adult || (birthdate != null && isValidBirthdate(birthdate))

private fun birthdateError() =
    ApiError("invalid_birthdate", "A valid birthdate (YYYY-MM-DD) is required unless registering as an adult")
