package net.markdrew.biblebowl.api

import kotlinx.serialization.Serializable

/**
 * The roles a user can hold in the Texas Bible Bowl app. Roles are **stackable** (a user may be both a
 * [COACH] and a [CONTESTANT]) and each grant is scoped (see [ScopeType]).
 */
@Serializable
enum class Role(val displayName: String, val defaultScope: ScopeType) {
    /** Default role for every account: study, contribute/vote questions, view own released scores. */
    CONTESTANT("Contestant", ScopeType.SELF),

    /** Manages a team within a congregation: roster (max 4), invites, team registration. */
    COACH("Coach", ScopeType.CONGREGATION),

    /** Manages event registration across congregations: rosters, seating/codes. */
    REGISTRAR("Registrar", ScopeType.EVENT),

    /** Enters/verifies round scores on test day and releases scores. */
    GRADER("Grader", ScopeType.EVENT),

    /** Global administrator: seasons, question moderation, user & role management. */
    ADMIN("Administrator", ScopeType.GLOBAL);
}

/** The kind of entity a [RoleGrant] is scoped to. */
@Serializable
enum class ScopeType {
    /** Applies to the user's own resources only. */
    SELF,

    /** Scoped to a single congregation (and its teams). */
    CONGREGATION,

    /** Scoped to a single team. */
    TEAM,

    /** Scoped to a single test event. */
    EVENT,

    /** Applies everywhere (admin). */
    GLOBAL,
}

/**
 * Fine-grained capabilities checked on the server. The UI reveals/hides features using the same set,
 * delivered to the client in [UserDto.permissions].
 */
@Serializable
enum class Permission {
    // Study & questions
    STUDY,
    QUESTION_SUBMIT,
    QUESTION_VOTE,
    QUESTION_MODERATE,

    // Teams & registration
    TEAM_MANAGE,
    REGISTRATION_MANAGE,

    // Scoring
    SCORE_ENTER,
    SCORE_RELEASE,
    SCORE_VIEW_OWN,
    SCORE_VIEW_ALL,

    // Administration
    SEASON_MANAGE,
    USER_MANAGE,
    ROLE_GRANT,
}

/** Static mapping from a [Role] to the [Permission]s it confers. Server-authoritative. */
val ROLE_PERMISSIONS: Map<Role, Set<Permission>> = mapOf(
    Role.CONTESTANT to setOf(
        Permission.STUDY,
        Permission.QUESTION_SUBMIT,
        Permission.QUESTION_VOTE,
        Permission.SCORE_VIEW_OWN,
    ),
    Role.COACH to setOf(
        Permission.STUDY,
        Permission.QUESTION_SUBMIT,
        Permission.QUESTION_VOTE,
        Permission.TEAM_MANAGE,
        Permission.REGISTRATION_MANAGE,
        Permission.SCORE_VIEW_OWN,
    ),
    Role.REGISTRAR to setOf(
        Permission.REGISTRATION_MANAGE,
    ),
    Role.GRADER to setOf(
        Permission.SCORE_ENTER,
        Permission.SCORE_RELEASE,
        Permission.SCORE_VIEW_ALL,
    ),
    Role.ADMIN to Permission.entries.toSet(),
)

/** Resolves the union of [Permission]s for a set of held [roles]. */
fun permissionsFor(roles: Iterable<Role>): Set<Permission> =
    roles.flatMap { ROLE_PERMISSIONS[it].orEmpty() }.toSet()

/**
 * A single role assignment for a user, optionally scoped to a concrete entity.
 *
 * @param role the granted role
 * @param scopeType the kind of entity the grant applies to
 * @param scopeId the id of the scoped entity, or null for [ScopeType.SELF]/[ScopeType.GLOBAL]
 */
@Serializable
data class RoleGrant(
    val role: Role,
    val scopeType: ScopeType = role.defaultScope,
    val scopeId: String? = null,
)

/**
 * True if any of [roles] confers [permission] **for [congregationId]**: either a GLOBAL grant
 * (admin) or a grant scoped to that congregation. This is the congregation-scoped counterpart of
 * the unscoped [permissionsFor] check; the server enforces it on every registration mutation and
 * the web UI mirrors it to decide what to render.
 */
fun hasScopedPermission(roles: List<RoleGrant>, permission: Permission, congregationId: String): Boolean =
    roles.any { grant ->
        permission in ROLE_PERMISSIONS[grant.role].orEmpty() && when (grant.scopeType) {
            ScopeType.GLOBAL -> true
            ScopeType.CONGREGATION -> grant.scopeId == congregationId
            else -> false
        }
    }

/** The congregation ids this user coaches (COACH grants scoped to a congregation). */
fun coachedCongregationIds(roles: List<RoleGrant>): List<String> =
    roles.filter { it.role == Role.COACH && it.scopeType == ScopeType.CONGREGATION }
        .mapNotNull { it.scopeId }

/**
 * True if any of [roles] confers [permission] **event-wide**: a GLOBAL grant (admin) or an
 * EVENT-scoped grant whose role carries the permission (e.g. REGISTRAR). A congregation-scoped
 * COACH grant does NOT qualify even though COACH's permission union contains
 * [Permission.REGISTRATION_MANAGE] — this is the gate for cross-congregation surfaces like the
 * registration desk. An EVENT grant's scopeId is ignored until event entities exist; when they
 * do, this check and the grant-validation rule in the user routes should loosen together.
 */
fun hasEventWidePermission(roles: List<RoleGrant>, permission: Permission): Boolean =
    roles.any { grant ->
        permission in ROLE_PERMISSIONS[grant.role].orEmpty() &&
            (grant.scopeType == ScopeType.GLOBAL || grant.scopeType == ScopeType.EVENT)
    }
