package net.markdrew.biblebowl.api

import kotlinx.serialization.Serializable

// ---------------------------------------------------------------------------
// Auth & users
// ---------------------------------------------------------------------------

@Serializable
data class RegisterRequest(
    val email: String,
    val password: String,
    val displayName: String,
    val grade: Int? = null,
)

@Serializable
data class LoginRequest(val email: String, val password: String)

/** Returned on successful auth: the JWT plus the resolved user (with effective permissions). */
@Serializable
data class AuthResponse(val token: String, val user: UserDto)

@Serializable
data class UserDto(
    val id: String,
    val email: String,
    val displayName: String,
    val grade: Int? = null,
    val roles: List<RoleGrant> = emptyList(),
    val permissions: Set<Permission> = emptySet(),
) {
    val division: Division? get() = grade?.let { Division.forGrade(it) }
}

// ---------------------------------------------------------------------------
// Crowd-sourced questions (MVP focus)
// ---------------------------------------------------------------------------

@Serializable
enum class QuestionStatus { PENDING, APPROVED, REJECTED }

/** A study question contributed by the community, typed to a competition round. */
@Serializable
data class QuestionDto(
    val id: String,
    val roundType: RoundType,
    val prompt: String,
    val answer: String,
    /** Serialized verse references supporting the answer, e.g. "Acts 2:38" (see core VerseRef). */
    val references: List<String> = emptyList(),
    /** Optional multiple-choice options for [RoundType.multipleChoice] rounds; the correct one equals [answer]. */
    val choices: List<String> = emptyList(),
    val chapter: Int? = null,
    val status: QuestionStatus = QuestionStatus.PENDING,
    val authorId: String,
    val authorName: String? = null,
    val votes: Int = 0,
    val viewerHasVoted: Boolean = false,
)

@Serializable
data class SubmitQuestionRequest(
    val roundType: RoundType,
    val prompt: String,
    val answer: String,
    val references: List<String> = emptyList(),
    val choices: List<String> = emptyList(),
    val chapter: Int? = null,
)

/** Admin/grader moderation action on a pending question. */
@Serializable
data class ModerateQuestionRequest(val status: QuestionStatus, val note: String? = null)

// ---------------------------------------------------------------------------
// Generic API envelope for errors
// ---------------------------------------------------------------------------

@Serializable
data class ApiError(val code: String, val message: String)
