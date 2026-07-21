package net.markdrew.biblebowl.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import net.markdrew.biblebowl.api.ApiError
import net.markdrew.biblebowl.api.Permission
import net.markdrew.biblebowl.api.Role
import net.markdrew.biblebowl.api.RoleGrant
import net.markdrew.biblebowl.api.ScopeType
import net.markdrew.biblebowl.api.UserDto
import net.markdrew.biblebowl.server.data.CongregationRepository
import net.markdrew.biblebowl.server.data.UserRecord
import net.markdrew.biblebowl.server.data.UserRepository
import net.markdrew.biblebowl.server.security.currentUser
import net.markdrew.biblebowl.server.security.requirePermission
import net.markdrew.biblebowl.server.security.toDto

/**
 * User management (docs/gui-redesign.md §5G): search users and grant/revoke role grants. This is
 * how an admin makes someone COACH of an *existing* congregation — the self-serve path only covers
 * congregations the coach creates. Grant identity rides DELETE query params (proxies drop DELETE
 * bodies), and revoking your own GLOBAL ADMIN grant is refused to prevent lockout.
 */
fun Route.userRoutes(users: UserRepository, congregations: CongregationRepository) {
    authenticate {
        get("/users") {
            val user = currentUser(users) ?: return@get
            if (!requirePermission(user, Permission.USER_MANAGE)) return@get
            val query = call.request.queryParameters["query"] ?: ""
            call.respond(users.search(query).toDtosWithCongregationNames(congregations))
        }

        post("/users/{userId}/roles") {
            val user = currentUser(users) ?: return@post
            if (!requirePermission(user, Permission.ROLE_GRANT)) return@post
            val userId = call.parameters["userId"]!!
            val grant = call.receive<RoleGrant>()
            validateGrant(grant, congregations)?.let { error ->
                return@post call.respond(HttpStatusCode.BadRequest, error)
            }
            val target = users.findById(userId) ?: return@post call.respond(
                HttpStatusCode.NotFound,
                ApiError("not_found", "No such user"),
            )
            // Read-before-insert: the unique index treats NULL scopeIds as distinct, so
            // insertIgnore alone would duplicate GLOBAL/EVENT grants on a repeat request.
            if (grant !in target.roles) users.addRoleGrant(userId, grant)
            call.respond(users.findById(userId)!!.toDtoWithCongregationNames(congregations))
        }

        delete("/users/{userId}/roles") {
            val user = currentUser(users) ?: return@delete
            if (!requirePermission(user, Permission.ROLE_GRANT)) return@delete
            val userId = call.parameters["userId"]!!
            val role = call.request.queryParameters["role"]?.let { runCatching { Role.valueOf(it) }.getOrNull() }
            val scopeType = call.request.queryParameters["scopeType"]
                ?.let { runCatching { ScopeType.valueOf(it) }.getOrNull() }
            if (role == null || scopeType == null) {
                return@delete call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("invalid_role", "role and scopeType query parameters are required"),
                )
            }
            val scopeId = call.request.queryParameters["scopeId"]
            if (userId == user.id && role == Role.ADMIN && scopeType == ScopeType.GLOBAL) {
                return@delete call.respond(
                    HttpStatusCode.Conflict,
                    ApiError("cannot_revoke_own_admin", "You can't revoke your own administrator access"),
                )
            }
            val target = users.findById(userId) ?: return@delete call.respond(
                HttpStatusCode.NotFound,
                ApiError("not_found", "No such user"),
            )
            if (!users.removeRoleGrant(target.id, RoleGrant(role, scopeType, scopeId))) {
                return@delete call.respond(
                    HttpStatusCode.NotFound,
                    ApiError("grant_not_found", "The user doesn't hold that grant"),
                )
            }
            call.respond(users.findById(userId)!!.toDtoWithCongregationNames(congregations))
        }
    }
}

/**
 * Maps records to DTOs with [UserDto.congregationNames] resolved (one batch lookup) so the manage-
 * users UI can label CONGREGATION-scoped grants by name instead of UUID. Ids with no surviving
 * congregation are simply absent from the map.
 */
private fun List<UserRecord>.toDtosWithCongregationNames(
    congregations: CongregationRepository,
): List<UserDto> {
    val ids = flatMap { record ->
        record.roles.filter { it.scopeType == ScopeType.CONGREGATION }.mapNotNull { it.scopeId }
    }.toSet()
    if (ids.isEmpty()) return map { it.toDto() }
    val names = congregations.findByIds(ids).associate { it.id to it.name }
    return map { record ->
        val mine = record.roles.mapNotNull { grant -> grant.scopeId?.let { id -> names[id]?.let { id to it } } }
        record.toDto().copy(congregationNames = mine.toMap())
    }
}

private fun UserRecord.toDtoWithCongregationNames(congregations: CongregationRepository): UserDto =
    listOf(this).toDtosWithCongregationNames(congregations).single()

/** Returns the 400 error for an ill-formed grant, or null when it's valid. */
private fun validateGrant(grant: RoleGrant, congregations: CongregationRepository): ApiError? = when {
    grant.scopeType != grant.role.defaultScope ->
        ApiError("invalid_scope", "${grant.role} grants must be ${grant.role.defaultScope}-scoped")
    grant.scopeType == ScopeType.CONGREGATION -> when {
        grant.scopeId == null ->
            ApiError("unknown_congregation", "A congregation-scoped grant needs a congregation id")
        congregations.findById(grant.scopeId!!) == null ->
            ApiError("unknown_congregation", "No such congregation")
        else -> null
    }
    // No event entities yet: SELF/EVENT/GLOBAL grants must be unscoped (loosen for EVENT when
    // events exist — see hasEventWidePermission).
    grant.scopeId != null ->
        ApiError("invalid_scope", "${grant.scopeType} grants must not carry a scope id")
    else -> null
}
