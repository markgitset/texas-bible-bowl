package net.markdrew.biblebowl.server.security

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.RoutingContext
import net.markdrew.biblebowl.api.ApiError
import net.markdrew.biblebowl.api.Permission
import net.markdrew.biblebowl.api.UserDto
import net.markdrew.biblebowl.api.permissionsFor
import net.markdrew.biblebowl.server.data.UserRecord
import net.markdrew.biblebowl.server.data.UserRepository

/** Maps a server [UserRecord] to the client-facing [UserDto], computing effective permissions. */
fun UserRecord.toDto(): UserDto = UserDto(
    id = id,
    email = email,
    displayName = displayName,
    grade = grade,
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
