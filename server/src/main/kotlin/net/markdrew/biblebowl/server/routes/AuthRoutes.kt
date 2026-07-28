package net.markdrew.biblebowl.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.put
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import net.markdrew.biblebowl.api.ApiError
import net.markdrew.biblebowl.api.AuthResponse
import net.markdrew.biblebowl.api.ForgotPasswordRequest
import net.markdrew.biblebowl.api.LoginRequest
import net.markdrew.biblebowl.api.RegisterRequest
import net.markdrew.biblebowl.api.ResetPasswordRequest
import net.markdrew.biblebowl.api.Role
import net.markdrew.biblebowl.api.RoleGrant
import net.markdrew.biblebowl.api.ScopeType
import net.markdrew.biblebowl.api.UpdateProfileRequest
import net.markdrew.biblebowl.api.isValidBirthdate
import net.markdrew.biblebowl.server.data.InMemoryPasswordResetRepository
import net.markdrew.biblebowl.server.data.PasswordResetRepository
import net.markdrew.biblebowl.server.data.ResetCode
import net.markdrew.biblebowl.server.data.UserRepository
import net.markdrew.biblebowl.server.email.EmailService
import net.markdrew.biblebowl.server.email.LogOnlyEmailService
import net.markdrew.biblebowl.server.security.JwtService
import net.markdrew.biblebowl.server.security.Passwords
import net.markdrew.biblebowl.server.security.currentUser
import net.markdrew.biblebowl.server.security.toDto

/** Per-IP limit on /auth/forgot-password — each request costs an outbound email. */
val AUTH_RATE_LIMIT = RateLimitName("auth")

private const val RESET_CODE_TTL_MS = 15 * 60 * 1000L
private const val RESET_CODE_MAX_ATTEMPTS = 5
private val resetCodeRandom = SecureRandom()

fun Route.authRoutes(
    users: UserRepository,
    jwt: JwtService,
    resets: PasswordResetRepository = InMemoryPasswordResetRepository(),
    email: EmailService = LogOnlyEmailService(),
) {
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

        rateLimit(AUTH_RATE_LIMIT) {
            /**
             * Emails a short-lived reset code to the account's address. Always answers OK — the
             * response never reveals whether an account exists (no enumeration oracle).
             */
            post("/forgot-password") {
                val req = call.receive<ForgotPasswordRequest>()
                users.findByEmail(req.email.trim())?.let { user ->
                    val code = "%06d".format(resetCodeRandom.nextInt(1_000_000))
                    resets.save(
                        ResetCode(
                            userId = user.id,
                            codeHash = Passwords.hash(code),
                            expiresAtEpochMs = System.currentTimeMillis() + RESET_CODE_TTL_MS,
                        )
                    )
                    // SMTP blocks and can be slow or down; a failed send must look identical to a
                    // nonexistent account from outside, so log-and-continue.
                    withContext(Dispatchers.IO) {
                        runCatching {
                            email.send(
                                to = user.email,
                                subject = "Texas Bible Bowl password reset code",
                                body = "Your Texas Bible Bowl password reset code is $code. It expires in " +
                                    "15 minutes.\n\nIf you didn't request this, you can ignore this email — " +
                                    "your password is unchanged.",
                            )
                        }.onFailure {
                            call.application.environment.log.error("Password-reset email send failed", it)
                        }
                    }
                }
                call.respond(mapOf("status" to "sent"))
            }
        }

        /** Redeems an emailed code for a new password; a success signs the user in. */
        post("/reset-password") {
            val req = call.receive<ResetPasswordRequest>()
            if (req.newPassword.length < 8) {
                call.respond(HttpStatusCode.BadRequest, ApiError("invalid", "Password must be at least 8 characters"))
                return@post
            }
            // One generic failure for every rejection below — which check failed is an oracle.
            val invalid = ApiError("invalid_code", "That code is invalid or has expired")
            val user = users.findByEmail(req.email.trim())
            val reset = user?.let { resets.find(it.id) }
            if (user == null || reset == null || reset.expiresAtEpochMs < System.currentTimeMillis() ||
                reset.attempts >= RESET_CODE_MAX_ATTEMPTS
            ) {
                call.respond(HttpStatusCode.BadRequest, invalid)
                return@post
            }
            if (!Passwords.verify(req.code.trim(), reset.codeHash)) {
                if (resets.incrementAttempts(user.id) >= RESET_CODE_MAX_ATTEMPTS) resets.delete(user.id)
                call.respond(HttpStatusCode.BadRequest, invalid)
                return@post
            }
            users.updatePassword(user.id, Passwords.hash(req.newPassword))
            resets.delete(user.id)
            call.respond(AuthResponse(jwt.issue(user.id, user.email), user.toDto()))
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
