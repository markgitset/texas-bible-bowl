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
