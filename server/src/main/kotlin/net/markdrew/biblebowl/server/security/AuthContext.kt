package net.markdrew.biblebowl.server.security

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.RoutingContext
import net.markdrew.biblebowl.api.ApiError
import net.markdrew.biblebowl.api.Permission
import net.markdrew.biblebowl.api.Role
import net.markdrew.biblebowl.api.ScopeType
import net.markdrew.biblebowl.api.UserDto
import net.markdrew.biblebowl.api.hasEventWidePermission
import net.markdrew.biblebowl.api.hasScopedPermission
import net.markdrew.biblebowl.api.permissionsFor
import net.markdrew.biblebowl.server.data.UserRecord
import net.markdrew.biblebowl.server.data.UserRepository

/** Maps a server [UserRecord] to the client-facing [UserDto], computing effective permissions. */
fun UserRecord.toDto(): UserDto = UserDto(
    id = id,
    email = email,
    displayName = displayName,
    birthdate = birthdate,
    adult = adult,
    contact = contact,
    roles = roles,
    permissions = permissionsFor(roles.map { it.role }),
)

/**
 * Loads the authenticated user from the JWT subject, or responds 401 and returns null.
 * Call from inside an `authenticate { }` block.
 */
suspend fun RoutingContext.currentUser(users: UserRepository): UserRecord? {
    val userId = call.principal<JWTPrincipal>()?.subject
    val user = userId?.let { users.findById(it) }
    if (user == null) {
        call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "Not authenticated"))
    }
    return user
}

/** Returns true if [user] holds [permission]; otherwise responds 403 and returns false. */
suspend fun RoutingContext.requirePermission(user: UserRecord, permission: Permission): Boolean {
    val granted = permissionsFor(user.roles.map { it.role }).contains(permission)
    if (!granted) {
        call.respond(HttpStatusCode.Forbidden, ApiError("forbidden", "Missing permission: $permission"))
    }
    return granted
}

/**
 * Returns true if [user] holds [permission] via a grant scoped to [congregationId] (or a GLOBAL
 * grant); otherwise responds 403 and returns false. This is what keeps coach A out of coach B's
 * registration — holding TEAM_MANAGE at all isn't enough, the grant's scope must match.
 */
suspend fun RoutingContext.requireScopedPermission(
    user: UserRecord,
    permission: Permission,
    congregationId: String,
): Boolean {
    val granted = hasScopedPermission(user.roles, permission, congregationId)
    if (!granted) {
        call.respond(
            HttpStatusCode.Forbidden,
            ApiError("forbidden_scope", "Missing permission $permission for this congregation"),
        )
    }
    return granted
}

/**
 * Returns true if [user] holds [permission] event-wide (a GLOBAL or EVENT-scoped grant);
 * otherwise responds 403 and returns false. This is what keeps coaches — whose
 * congregation-scoped grant also carries REGISTRATION_MANAGE — off cross-congregation
 * surfaces like the registration desk.
 */
suspend fun RoutingContext.requireEventWidePermission(user: UserRecord, permission: Permission): Boolean {
    val granted = hasEventWidePermission(user.roles, permission)
    if (!granted) {
        call.respond(
            HttpStatusCode.Forbidden,
            ApiError("forbidden_scope", "Missing event-wide permission: $permission"),
        )
    }
    return granted
}

/** True for a globally-scoped ADMIN grant (used e.g. to exempt admins from the registration window). */
val UserRecord.isAdmin: Boolean
    get() = roles.any { it.role == Role.ADMIN && it.scopeType == ScopeType.GLOBAL }

/**
 * Season feature-toggle gate: passes while [enabled] — and always for global admins, so a feature
 * can be deployed dark and exercised in production before launch. Otherwise responds 403
 * `feature_disabled` and returns false. Checked before any permission/window rule so a dark
 * feature answers uniformly regardless of the caller's grants.
 */
suspend fun RoutingContext.requireFeatureEnabled(user: UserRecord, enabled: Boolean, feature: String): Boolean {
    if (enabled || user.isAdmin) return true
    call.respond(HttpStatusCode.Forbidden, ApiError("feature_disabled", "$feature is not open yet"))
    return false
}
