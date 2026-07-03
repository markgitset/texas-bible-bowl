package net.markdrew.biblebowl.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import net.markdrew.biblebowl.api.ApiError
import net.markdrew.biblebowl.api.AuthResponse
import net.markdrew.biblebowl.api.LoginRequest
import net.markdrew.biblebowl.api.RegisterRequest
import net.markdrew.biblebowl.api.Role
import net.markdrew.biblebowl.api.RoleGrant
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
            if (users.findByEmail(req.email) != null) {
                call.respond(HttpStatusCode.Conflict, ApiError("email_taken", "Email already registered"))
                return@post
            }
            val user = users.create(
                email = req.email,
                displayName = req.displayName,
                grade = req.grade,
                passwordHash = Passwords.hash(req.password),
                roles = listOf(RoleGrant(Role.CONTESTANT)), // everyone starts as a contestant
            )
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
        }
    }
}
