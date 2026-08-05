package net.markdrew.biblebowl.server.data

import net.markdrew.biblebowl.api.ContactInfoDto
import net.markdrew.biblebowl.api.QuestionDto
import net.markdrew.biblebowl.api.QuestionStatus
import net.markdrew.biblebowl.api.Role
import net.markdrew.biblebowl.api.RoleGrant
import net.markdrew.biblebowl.api.ScopeType
import net.markdrew.biblebowl.api.SubmitQuestionRequest
import net.markdrew.biblebowl.model.Book
import net.markdrew.biblebowl.model.ChapterRange
import net.markdrew.biblebowl.model.ChapterRef
import net.markdrew.biblebowl.model.Round
import net.markdrew.biblebowl.model.bookFromRefs
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
    /** Optional contact details (item 9, F3); null when the user has never provided any. */
    val contact: ContactInfoDto? = null,
    /** The person this account IS (people.id), set by a self-claim or email match; null until then. */
    val personId: String? = null,
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
    /**
     * Replaces the user's self-editable profile fields ([contact] included — callers resolve
     * keep-vs-clear before calling); null when the user doesn't exist.
     */
    fun updateProfile(
        userId: String,
        displayName: String,
        birthdate: String?,
        adult: Boolean,
        contact: ContactInfoDto?,
    ): UserRecord?
    /** Replaces the stored password hash (forgot-password reset). A no-op if the user doesn't exist. */
    fun updatePassword(userId: String, passwordHash: String)
    /**
     * Links this account to the person it IS ([UserRecord.personId] / people.id) — the self side of
     * the claim model (a self-claim or a signup email match). Idempotent; a no-op if the account
     * doesn't exist.
     */
    fun linkPerson(userId: String, personId: String)
    /** Adds a role grant to an existing user (no-op if the identical grant is already held). */
    fun addRoleGrant(userId: String, grant: RoleGrant)
    /** Removes an exact grant (role + scopeType + scopeId). False if the user or grant wasn't found. */
    fun removeRoleGrant(userId: String, grant: RoleGrant): Boolean
    /** Case-insensitive substring search over email and display name. A blank query matches nothing. */
    fun search(query: String, limit: Int = 20): List<UserRecord>
    /**
     * Users holding a CONGREGATION-scoped COACH grant for any of [congregationIds], keyed by
     * congregation id. Returned records may carry only the matching grant in [UserRecord.roles]
     * (callers want contact info, not the full grant list).
     */
    fun coachesByCongregation(congregationIds: Collection<String>): Map<String, List<UserRecord>>
    /**
     * Records a coach email known from the workbook seed (item 17, F13): when this email later
     * registers, [consumePendingCoachGrants] hands out the congregation-scoped COACH role.
     * Idempotent per (email, congregation). Since V2 this is people/participants-backed: the
     * email becomes a person with an `isCoach` participation under the congregation's
     * [seasonYear] registration (the in-memory double keeps a simple map).
     */
    fun addPendingCoachGrant(email: String, congregationId: String, seasonYear: String)
    /** Congregation ids pending for [email] — removed (consumed) and returned; empty when none. */
    fun consumePendingCoachGrants(email: String): List<String>
    /** All not-yet-consumed pending coach grants, congregation ids keyed by email (seed summary). */
    fun pendingCoachGrants(): Map<String, List<String>>
}

/**
 * Which questions a query covers, expressed in canonical scripture coordinates (book + chapter), never
 * season-relative ones — a study set is queried as the OR of its chapter ranges. [matches] is the single
 * predicate both repository implementations share, so the Postgres SQL and the in-memory filter can't
 * drift apart.
 *
 * [includeBookWide] (default true) also admits a scoped book's `chapter IS NULL` rows — a whole-book
 * Acts question IS study material for anyone studying Acts 2.
 */
sealed interface QuestionScope {

    /** True when a question stored with ([bookCode], [chapter]) falls inside this scope. */
    fun matches(bookCode: String?, chapter: Int?): Boolean

    data object All : QuestionScope {
        override fun matches(bookCode: String?, chapter: Int?): Boolean = true
    }

    /** The OR of per-range (book, chapter BETWEEN) predicates — a study set, or a cumulative prefix. */
    data class Ranges(val ranges: List<ChapterRange>, val includeBookWide: Boolean = true) : QuestionScope {
        override fun matches(bookCode: String?, chapter: Int?): Boolean {
            val book = bookCode?.let { code -> Book.entries.firstOrNull { it.name == code } } ?: return false
            if (chapter == null) return includeBookWide && ranges.any { it.start.book == book }
            if (chapter < 1) return false
            return ranges.any { book.chapterRef(chapter) in it }
        }
    }

    /** One exact chapter (plus that book's book-wide questions when [includeBookWide]). */
    data class Chapter(val ref: ChapterRef, val includeBookWide: Boolean = true) : QuestionScope {
        /** This scope as a one-range [Ranges], so both implementations share one code path. */
        val asRanges: Ranges get() = Ranges(listOf(ref..ref), includeBookWide)
        override fun matches(bookCode: String?, chapter: Int?): Boolean = asRanges.matches(bookCode, chapter)
    }
}

interface QuestionRepository {
    /** Inserts a PENDING question scoped to [book] (the caller resolves it; see core bookFromRefs). */
    fun submit(authorId: String, authorName: String?, req: SubmitQuestionRequest, book: Book): QuestionDto
    /**
     * Questions in [scope], optionally filtered to [status]/[roundType] and capped at [limit], ordered
     * by votes descending. Round and limit are pushed down (not filtered by the caller) so a
     * 40-question practice test never materializes the whole bank.
     */
    fun list(
        status: QuestionStatus?,
        scope: QuestionScope = QuestionScope.All,
        roundType: Round? = null,
        limit: Int? = null,
    ): List<QuestionDto>
    fun get(id: String): QuestionDto?
    fun setStatus(id: String, status: QuestionStatus): QuestionDto?
    fun vote(id: String, userId: String): QuestionDto?
    /** Number of questions authored by [authorId], in any status. Cheap idempotency guard for seeding. */
    fun countByAuthor(authorId: String): Long
    /**
     * Bulk-inserts [requests] as already-APPROVED questions authored by [authorId], atomically where the
     * backing store allows (a crash must not leave a partial seed that [countByAuthor] then treats as
     * complete). Each request must carry a [SubmitQuestionRequest.bookCode] or references it can be
     * inferred from. Returns the number inserted.
     */
    fun seedApproved(authorId: String, authorName: String?, requests: List<SubmitQuestionRequest>): Int
}

/** The book a seed/submit request is scoped to: explicit [SubmitQuestionRequest.bookCode] first, then refs. */
internal fun SubmitQuestionRequest.resolvedBook(): Book = requireNotNull(
    bookCode?.let { code -> Book.entries.firstOrNull { it.name == code } } ?: bookFromRefs(references)
) { "Question has no resolvable book (bookCode='$bookCode', references=$references): $prompt" }

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

    override fun updateProfile(
        userId: String,
        displayName: String,
        birthdate: String?,
        adult: Boolean,
        contact: ContactInfoDto?,
    ): UserRecord? =
        byId.computeIfPresent(userId) { _, record ->
            record.copy(displayName = displayName, birthdate = birthdate, adult = adult, contact = contact)
        }

    override fun updatePassword(userId: String, passwordHash: String) {
        byId.computeIfPresent(userId) { _, record -> record.copy(passwordHash = passwordHash) }
    }

    override fun linkPerson(userId: String, personId: String) {
        byId.computeIfPresent(userId) { _, record -> record.copy(personId = personId) }
    }

    override fun addRoleGrant(userId: String, grant: RoleGrant) {
        byId[userId]?.roles?.let { roles -> synchronized(roles) { if (grant !in roles) roles.add(grant) } }
    }

    override fun removeRoleGrant(userId: String, grant: RoleGrant): Boolean {
        val roles = byId[userId]?.roles ?: return false
        return synchronized(roles) { roles.removeAll { it == grant } }
    }

    override fun search(query: String, limit: Int): List<UserRecord> {
        if (query.isBlank()) return emptyList()
        return byId.values
            .filter { it.email.contains(query, ignoreCase = true) || it.displayName.contains(query, ignoreCase = true) }
            .sortedBy { it.email }
            .take(limit)
    }

    override fun coachesByCongregation(congregationIds: Collection<String>): Map<String, List<UserRecord>> {
        val ids = congregationIds.toSet()
        return byId.values
            .flatMap { user ->
                synchronized(user.roles) {
                    user.roles.filter {
                        it.role == Role.COACH && it.scopeType == ScopeType.CONGREGATION && it.scopeId in ids
                    }.map { it.scopeId!! to user }
                }
            }
            .groupBy({ it.first }, { it.second })
    }

    private val pendingCoach = ConcurrentHashMap<String, MutableSet<String>>() // email -> congregation ids

    override fun addPendingCoachGrant(email: String, congregationId: String, seasonYear: String) {
        pendingCoach.getOrPut(email.lowercase()) { ConcurrentHashMap.newKeySet() }.add(congregationId)
    }

    override fun consumePendingCoachGrants(email: String): List<String> =
        pendingCoach.remove(email.lowercase())?.toList() ?: emptyList()

    override fun pendingCoachGrants(): Map<String, List<String>> =
        pendingCoach.mapValues { it.value.toList() }
}

class InMemoryQuestionRepository : QuestionRepository {
    private val byId = ConcurrentHashMap<String, QuestionDto>()
    private val voters = ConcurrentHashMap<String, MutableSet<String>>()

    override fun submit(authorId: String, authorName: String?, req: SubmitQuestionRequest, book: Book): QuestionDto {
        val id = UUID.randomUUID().toString()
        val dto = QuestionDto(
            id = id,
            roundType = req.roundType,
            prompt = req.prompt,
            answer = req.answer,
            references = req.references,
            choices = req.choices,
            bookCode = book.name,
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

    override fun list(
        status: QuestionStatus?,
        scope: QuestionScope,
        roundType: Round?,
        limit: Int?,
    ): List<QuestionDto> =
        byId.values
            .filter { status == null || it.status == status }
            .filter { scope.matches(it.bookCode, it.chapter) }
            .filter { roundType == null || it.roundType == roundType }
            .sortedByDescending { it.votes }
            .let { if (limit != null) it.take(limit) else it }

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

    override fun countByAuthor(authorId: String): Long =
        byId.values.count { it.authorId == authorId }.toLong()

    override fun seedApproved(authorId: String, authorName: String?, requests: List<SubmitQuestionRequest>): Int {
        requests.forEach { req ->
            val id = UUID.randomUUID().toString()
            byId[id] = QuestionDto(
                id = id,
                roundType = req.roundType,
                prompt = req.prompt,
                answer = req.answer,
                references = req.references,
                choices = req.choices,
                bookCode = req.resolvedBook().name,
                chapter = req.chapter,
                status = QuestionStatus.APPROVED,
                authorId = authorId,
                authorName = authorName,
                votes = 0,
            )
            voters[id] = ConcurrentHashMap.newKeySet()
        }
        return requests.size
    }
}
