package net.markdrew.biblebowl.server.data

import net.markdrew.biblebowl.api.QuestionDto
import net.markdrew.biblebowl.api.QuestionStatus
import net.markdrew.biblebowl.api.RoleGrant
import net.markdrew.biblebowl.api.SubmitQuestionRequest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Server-side user record (never leaves the server as-is; mapped to UserDto for clients). */
data class UserRecord(
    val id: String,
    val email: String,
    val displayName: String,
    /** ISO-8601 birthdate for youth; null for adults and pre-birthdate accounts. */
    val birthdate: String?,
    /** Self-attested adult; only adults may register a congregation. */
    val adult: Boolean,
    val passwordHash: String,
    val roles: MutableList<RoleGrant>,
)

interface UserRepository {
    fun create(
        email: String,
        displayName: String,
        birthdate: String?,
        adult: Boolean,
        passwordHash: String,
        roles: List<RoleGrant>,
    ): UserRecord
    fun findByEmail(email: String): UserRecord?
    fun findById(id: String): UserRecord?
    /** Replaces the user's self-editable profile fields; null when the user doesn't exist. */
    fun updateProfile(userId: String, displayName: String, birthdate: String?, adult: Boolean): UserRecord?
    /** Adds a role grant to an existing user (no-op if the identical grant is already held). */
    fun addRoleGrant(userId: String, grant: RoleGrant)
}

interface QuestionRepository {
    fun submit(authorId: String, authorName: String?, req: SubmitQuestionRequest): QuestionDto
    fun list(status: QuestionStatus?, chapter: Int?): List<QuestionDto>
    fun get(id: String): QuestionDto?
    fun setStatus(id: String, status: QuestionStatus): QuestionDto?
    fun vote(id: String, userId: String): QuestionDto?
}

// ---------------------------------------------------------------------------
// In-memory implementations for Phase 0 / local dev & tests.
// Swap for Exposed/Postgres in Phase 1 behind these same interfaces.
// ---------------------------------------------------------------------------

class InMemoryUserRepository : UserRepository {
    private val byId = ConcurrentHashMap<String, UserRecord>()
    private val idByEmail = ConcurrentHashMap<String, String>()

    override fun create(
        email: String, displayName: String, birthdate: String?, adult: Boolean, passwordHash: String, roles: List<RoleGrant>,
    ): UserRecord {
        val id = UUID.randomUUID().toString()
        val record = UserRecord(id, email.lowercase(), displayName, birthdate, adult, passwordHash, roles.toMutableList())
        byId[id] = record
        idByEmail[email.lowercase()] = id
        return record
    }

    override fun findByEmail(email: String): UserRecord? = idByEmail[email.lowercase()]?.let { byId[it] }
    override fun findById(id: String): UserRecord? = byId[id]

    override fun updateProfile(userId: String, displayName: String, birthdate: String?, adult: Boolean): UserRecord? =
        byId.computeIfPresent(userId) { _, record ->
            record.copy(displayName = displayName, birthdate = birthdate, adult = adult)
        }

    override fun addRoleGrant(userId: String, grant: RoleGrant) {
        byId[userId]?.roles?.let { roles -> synchronized(roles) { if (grant !in roles) roles.add(grant) } }
    }
}

class InMemoryQuestionRepository : QuestionRepository {
    private val byId = ConcurrentHashMap<String, QuestionDto>()
    private val voters = ConcurrentHashMap<String, MutableSet<String>>()

    override fun submit(authorId: String, authorName: String?, req: SubmitQuestionRequest): QuestionDto {
        val id = UUID.randomUUID().toString()
        val dto = QuestionDto(
            id = id,
            roundType = req.roundType,
            prompt = req.prompt,
            answer = req.answer,
            references = req.references,
            choices = req.choices,
            chapter = req.chapter,
            status = QuestionStatus.PENDING,
            authorId = authorId,
            authorName = authorName,
            votes = 0,
        )
        byId[id] = dto
        voters[id] = ConcurrentHashMap.newKeySet()
        return dto
    }

    override fun list(status: QuestionStatus?, chapter: Int?): List<QuestionDto> =
        byId.values
            .filter { status == null || it.status == status }
            .filter { chapter == null || it.chapter == chapter }
            .sortedByDescending { it.votes }

    override fun get(id: String): QuestionDto? = byId[id]

    override fun setStatus(id: String, status: QuestionStatus): QuestionDto? {
        val updated = byId[id]?.copy(status = status) ?: return null
        byId[id] = updated
        return updated
    }

    override fun vote(id: String, userId: String): QuestionDto? {
        val set = voters[id] ?: return null
        val current = byId[id] ?: return null
        val updated = if (set.add(userId)) current.copy(votes = current.votes + 1) else current
        byId[id] = updated
        return updated
    }
}
