package net.markdrew.biblebowl.server.data

import kotlinx.datetime.LocalDate
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import net.markdrew.biblebowl.api.ContactInfoDto
import net.markdrew.biblebowl.api.ContactPreference
import net.markdrew.biblebowl.api.QuestionDto
import net.markdrew.biblebowl.api.QuestionStatus
import net.markdrew.biblebowl.api.Role
import net.markdrew.biblebowl.api.RoleGrant
import net.markdrew.biblebowl.model.Round
import net.markdrew.biblebowl.api.ScopeType
import net.markdrew.biblebowl.api.SubmitQuestionRequest
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SqlExpressionBuilder.eq
import org.jetbrains.exposed.v1.core.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private val json = Json { ignoreUnknownKeys = true }
private val stringListSerializer = ListSerializer(String.serializer())

/** How long a [PostgresUserRepository.findById] cache entry stays valid without a write. */
private const val CACHE_TTL_MS = 30_000L

/**
 * Users are auth-only since V2: identity facts (birthdate, adult flag, contact info) live on the
 * linked person ([UsersTable.personId]) and are joined back into [UserRecord] here so callers see
 * the same shape as before. Coach seeding ("pending grants") is people/participants-backed: a
 * seeded coach is a person with an email plus an `isCoach` participation; signup email-matches
 * the person, links the account, and grants the congregation-scoped COACH role.
 */
class PostgresUserRepository(private val db: Database) : UserRepository {

    /**
     * [findById] cache: every authenticated request resolves its JWT subject here, costing 2 queries
     * (user + role grants) against a not-co-located database. Correct on the single-instance server
     * because every user/role mutation goes through this repository and invalidates; the short TTL
     * is a backstop for out-of-band edits (manual SQL). Entries are (expiresAtEpochMs, record).
     */
    private val cache = ConcurrentHashMap<String, Pair<Long, UserRecord>>()

    private fun cached(record: UserRecord): UserRecord {
        cache[record.id] = System.currentTimeMillis() + CACHE_TTL_MS to record
        return record
    }

    override fun create(
        email: String, displayName: String, birthdate: String?, adult: Boolean, passwordHash: String, roles: List<RoleGrant>,
    ): UserRecord = transaction(db) {
        val userId = UUID.randomUUID().toString()
        UsersTable.insert {
            it[id] = userId
            it[UsersTable.email] = email.lowercase()
            it[UsersTable.displayName] = displayName
            it[UsersTable.passwordHash] = passwordHash
        }
        // Adopt the matching person (seeded coach/volunteer) by email, else create a fresh one.
        // Adoption is adults-only in both directions: a child's person may carry a parent's email
        // as *contact*, and must never become the parent account's "is" link.
        val adopted = if (!adult) null else PeopleTable
            .join(UsersTable, JoinType.LEFT, onColumn = PeopleTable.id, otherColumn = UsersTable.personId)
            .selectAll()
            .where {
                (PeopleTable.email.lowerCase() eq email.lowercase()) and
                    PeopleTable.isAdult.eq(true) and UsersTable.id.isNull()
            }
            .firstOrNull()?.get(PeopleTable.id)
        val personId = adopted ?: UUID.randomUUID().toString()
        if (adopted == null) {
            PeopleTable.insert {
                it[id] = personId
                it[name] = displayName
                it[PeopleTable.birthdate] = birthdate?.let(LocalDate::parse)
                it[isAdult] = adult
                it[PeopleTable.email] = email.lowercase()
                it[claimCode] = freshPersonClaimCode()
                it[managedByUserId] = userId
            }
        } else {
            PeopleTable.update({ PeopleTable.id eq personId }) {
                it[managedByUserId] = userId
            }
        }
        UsersTable.update({ UsersTable.id eq userId }) { it[UsersTable.personId] = personId }
        roles.forEach { grant ->
            RoleGrantsTable.insert {
                it[id] = UUID.randomUUID().toString()
                it[RoleGrantsTable.userId] = userId
                it[role] = grant.role.name
                it[scopeType] = grant.scopeType.name
                it[scopeId] = grant.scopeId
            }
        }
        UserRecord(
            userId, email.lowercase(), displayName, birthdate, adult, passwordHash,
            roles.toMutableList(), personId = personId,
        )
    }.let(::cached)

    private fun usersWithPerson() =
        UsersTable.join(PeopleTable, JoinType.LEFT, onColumn = UsersTable.personId, otherColumn = PeopleTable.id)

    override fun findByEmail(email: String): UserRecord? = transaction(db) {
        usersWithPerson().selectAll().where { UsersTable.email eq email.lowercase() }
            .singleOrNull()?.toUserRecord()
    }

    override fun findById(id: String): UserRecord? {
        cache[id]?.let { (expiresAt, record) -> if (expiresAt > System.currentTimeMillis()) return record }
        val record = transaction(db) {
            usersWithPerson().selectAll().where { UsersTable.id eq id }.singleOrNull()?.toUserRecord()
        }
        return record?.let(::cached) ?: run { cache.remove(id); null }
    }

    override fun updateProfile(
        userId: String,
        displayName: String,
        birthdate: String?,
        adult: Boolean,
        contact: ContactInfoDto?,
    ): UserRecord? =
        transaction(db) {
            val row = UsersTable.selectAll().where { UsersTable.id eq userId }.singleOrNull()
                ?: return@transaction null
            UsersTable.update({ UsersTable.id eq userId }) {
                it[UsersTable.displayName] = displayName
            }
            val personId = row[UsersTable.personId] ?: UUID.randomUUID().toString().also { newId ->
                PeopleTable.insert {
                    it[id] = newId
                    it[name] = displayName
                    it[PeopleTable.email] = row[UsersTable.email]
                    it[claimCode] = freshPersonClaimCode()
                    it[managedByUserId] = userId
                }
                UsersTable.update({ UsersTable.id eq userId }) { it[UsersTable.personId] = newId }
            }
            PeopleTable.update({ PeopleTable.id eq personId }) {
                it[name] = displayName
                it[PeopleTable.birthdate] = birthdate?.let(LocalDate::parse)
                it[isAdult] = adult
                it[contactAddress] = contact?.address?.trim() ?: ""
                it[contactCity] = contact?.city?.trim() ?: ""
                it[contactState] = contact?.state?.trim() ?: ""
                it[contactZip] = contact?.zip?.trim() ?: ""
                it[contactPhone] = contact?.phone?.trim() ?: ""
                it[contactPreference] = contact?.preference?.name
            }
            cache.remove(userId)
            usersWithPerson().selectAll().where { UsersTable.id eq userId }.singleOrNull()?.toUserRecord()
        }?.let(::cached)

    override fun linkPerson(userId: String, personId: String) {
        transaction(db) {
            UsersTable.update({ UsersTable.id eq userId }) { it[UsersTable.personId] = personId }
        }
        cache.remove(userId)
    }

    override fun addRoleGrant(userId: String, grant: RoleGrant) {
        transaction(db) {
            RoleGrantsTable.insertIgnore {
                it[id] = UUID.randomUUID().toString()
                it[RoleGrantsTable.userId] = userId
                it[role] = grant.role.name
                it[scopeType] = grant.scopeType.name
                it[scopeId] = grant.scopeId
            }
        }
        cache.remove(userId)
    }

    override fun removeRoleGrant(userId: String, grant: RoleGrant): Boolean = transaction(db) {
        // Deletes all matching rows: the unique index treats NULL scopeIds as distinct, so
        // null-scoped grants can exist duplicated; revoking cleans them all up.
        RoleGrantsTable.deleteWhere {
            (RoleGrantsTable.userId eq userId) and
                (role eq grant.role.name) and
                (scopeType eq grant.scopeType.name) and
                (grant.scopeId?.let { scopeId eq it } ?: scopeId.isNull())
        } > 0
    }.also { cache.remove(userId) }

    override fun search(query: String, limit: Int): List<UserRecord> {
        if (query.isBlank()) return emptyList()
        val q = "%${query.trim().lowercase()}%"
        return transaction(db) {
            usersWithPerson().selectAll()
                .where { (UsersTable.email.lowerCase() like q) or (UsersTable.displayName.lowerCase() like q) }
                .orderBy(UsersTable.email)
                .limit(limit)
                .map { it.toUserRecord() }
        }
    }

    override fun coachesByCongregation(congregationIds: Collection<String>): Map<String, List<UserRecord>> {
        if (congregationIds.isEmpty()) return emptyMap()
        return transaction(db) {
            (RoleGrantsTable innerJoin UsersTable)
                .join(PeopleTable, JoinType.LEFT, onColumn = UsersTable.personId, otherColumn = PeopleTable.id)
                .selectAll()
                .where {
                    (RoleGrantsTable.role eq Role.COACH.name) and
                        (RoleGrantsTable.scopeType eq ScopeType.CONGREGATION.name) and
                        (RoleGrantsTable.scopeId inList congregationIds)
                }
                .orderBy(UsersTable.email)
                .groupBy(
                    keySelector = { it[RoleGrantsTable.scopeId]!! },
                    valueTransform = {
                        it.toUserRecord(
                            // Only the matching coach grant — see the interface KDoc.
                            grants = mutableListOf(
                                RoleGrant(Role.COACH, ScopeType.CONGREGATION, it[RoleGrantsTable.scopeId]),
                            ),
                        )
                    },
                )
        }
    }

    override fun addPendingCoachGrant(email: String, congregationId: String, seasonYear: String): Unit =
        transaction(db) {
            val year = seasonYear.toIntOrNull() ?: return@transaction
            ensureSeasonRow(year)
            val lowered = email.lowercase()
            val personId = PeopleTable.selectAll()
                .where { PeopleTable.email.lowerCase() eq lowered }
                .firstOrNull()?.get(PeopleTable.id)
                ?: UUID.randomUUID().toString().also { newId ->
                    PeopleTable.insert {
                        it[id] = newId
                        it[name] = lowered.substringBefore('@')
                        it[isAdult] = true
                        it[PeopleTable.email] = lowered
                        it[claimCode] = freshPersonClaimCode()
                    }
                }
            val regId = RegistrationsTable.selectAll()
                .where {
                    (RegistrationsTable.congregationId eq congregationId) and
                        (RegistrationsTable.seasonYear eq year)
                }
                .firstOrNull()?.get(RegistrationsTable.id)
                ?: UUID.randomUUID().toString().also { newId ->
                    RegistrationsTable.insert {
                        it[id] = newId
                        it[RegistrationsTable.congregationId] = congregationId
                        it[RegistrationsTable.seasonYear] = year
                        it[status] = "DRAFT"
                        it[siteId] = resolveSeasonSite(year, null)
                        it[updatedAtEpochMs] = System.currentTimeMillis()
                    }
                }
            val existing = ParticipantsTable.selectAll()
                .where { (ParticipantsTable.personId eq personId) and (ParticipantsTable.seasonYear eq year) }
                .firstOrNull()
            if (existing == null) {
                ParticipantsTable.insert {
                    it[id] = UUID.randomUUID().toString()
                    it[ParticipantsTable.personId] = personId
                    it[registrationId] = regId
                    it[ParticipantsTable.seasonYear] = year
                    it[isContestant] = false
                    it[isCoach] = true
                }
            } else {
                ParticipantsTable.update({ ParticipantsTable.id eq existing[ParticipantsTable.id] }) {
                    it[isCoach] = true
                }
            }
        }

    override fun consumePendingCoachGrants(email: String): List<String> = transaction(db) {
        // Knowing the signup email is the credential (as with the old side table): every isCoach
        // participation of any person carrying this email yields that congregation's COACH role.
        (ParticipantsTable innerJoin PeopleTable)
            .join(
                RegistrationsTable, JoinType.INNER,
                onColumn = ParticipantsTable.registrationId, otherColumn = RegistrationsTable.id,
            )
            .selectAll()
            .where { (PeopleTable.email.lowerCase() eq email.lowercase()) and (ParticipantsTable.isCoach eq true) }
            .map { it[RegistrationsTable.congregationId] }
            .distinct()
    }

    override fun pendingCoachGrants(): Map<String, List<String>> = transaction(db) {
        // "Pending" = an isCoach participation whose person no account has linked yet.
        (ParticipantsTable innerJoin PeopleTable)
            .join(
                RegistrationsTable, JoinType.INNER,
                onColumn = ParticipantsTable.registrationId, otherColumn = RegistrationsTable.id,
            )
            .join(UsersTable, JoinType.LEFT, onColumn = PeopleTable.id, otherColumn = UsersTable.personId)
            .selectAll()
            .where { (ParticipantsTable.isCoach eq true) and UsersTable.id.isNull() }
            .mapNotNull { row ->
                row[PeopleTable.email]?.let { it to row[RegistrationsTable.congregationId] }
            }
            .distinct()
            .groupBy({ it.first }, { it.second })
    }

    private fun ResultRow.toUserRecord(grants: MutableList<RoleGrant>? = null): UserRecord {
        val userId = this[UsersTable.id]
        val roleGrants = grants ?: RoleGrantsTable.selectAll().where { RoleGrantsTable.userId eq userId }.map {
            RoleGrant(
                role = Role.valueOf(it[RoleGrantsTable.role]),
                scopeType = ScopeType.valueOf(it[RoleGrantsTable.scopeType]),
                scopeId = it[RoleGrantsTable.scopeId],
            )
        }.toMutableList()
        val hasPerson = getOrNull(PeopleTable.id) != null
        return UserRecord(
            id = userId,
            email = this[UsersTable.email],
            displayName = this[UsersTable.displayName],
            birthdate = if (hasPerson) this[PeopleTable.birthdate]?.toString() else null,
            adult = if (hasPerson) this[PeopleTable.isAdult] else false,
            passwordHash = this[UsersTable.passwordHash],
            roles = roleGrants,
            personId = this[UsersTable.personId],
            contact = if (!hasPerson) null else ContactInfoDto(
                address = this[PeopleTable.contactAddress],
                city = this[PeopleTable.contactCity],
                state = this[PeopleTable.contactState],
                zip = this[PeopleTable.contactZip],
                phone = this[PeopleTable.contactPhone],
                preference = this[PeopleTable.contactPreference]?.let { ContactPreference.valueOf(it) },
            ).takeUnless { it.isEmpty() },
        )
    }
}

class PostgresQuestionRepository(private val db: Database) : QuestionRepository {

    override fun submit(authorId: String, authorName: String?, req: SubmitQuestionRequest): QuestionDto =
        transaction(db) {
            val questionId = UUID.randomUUID().toString()
            QuestionsTable.insert {
                it[id] = questionId
                it[roundType] = req.roundType.name
                it[prompt] = req.prompt
                it[answer] = req.answer
                it[references] = req.references.joinToString(",")
                it[choices] = json.encodeToString(stringListSerializer, req.choices)
                it[chapter] = req.chapter
                it[status] = QuestionStatus.PENDING.name
                it[QuestionsTable.authorId] = authorId
            }
            QuestionDto(
                id = questionId,
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
        }

    override fun list(status: QuestionStatus?, chapter: Int?): List<QuestionDto> = transaction(db) {
        QuestionsTable.selectAll()
            .apply {
                if (status != null && chapter != null) {
                    where { (QuestionsTable.status eq status.name) and (QuestionsTable.chapter eq chapter) }
                } else if (status != null) {
                    where { QuestionsTable.status eq status.name }
                } else if (chapter != null) {
                    where { QuestionsTable.chapter eq chapter }
                }
            }
            .map { it.toDto() }
            .sortedByDescending { it.votes }
    }

    override fun get(id: String): QuestionDto? = transaction(db) {
        QuestionsTable.selectAll().where { QuestionsTable.id eq id }.singleOrNull()?.toDto()
    }

    override fun setStatus(id: String, status: QuestionStatus): QuestionDto? = transaction(db) {
        val updated = QuestionsTable.update({ QuestionsTable.id eq id }) {
            it[QuestionsTable.status] = status.name
        }
        if (updated == 0) null else QuestionsTable.selectAll().where { QuestionsTable.id eq id }.single().toDto()
    }

    override fun vote(id: String, userId: String): QuestionDto? = transaction(db) {
        val exists = QuestionsTable.selectAll().where { QuestionsTable.id eq id }.any()
        if (!exists) return@transaction null
        QuestionVotesTable.insertIgnore {
            it[QuestionVotesTable.id] = UUID.randomUUID().toString()
            it[questionId] = id
            it[QuestionVotesTable.userId] = userId
        }
        QuestionsTable.selectAll().where { QuestionsTable.id eq id }.single().toDto()
    }

    private fun ResultRow.toDto(): QuestionDto {
        val qId = this[QuestionsTable.id]
        val voteCount = QuestionVotesTable.selectAll().where { QuestionVotesTable.questionId eq qId }.count()
        val authorId = this[QuestionsTable.authorId]
        val authorName = UsersTable.selectAll().where { UsersTable.id eq authorId }
            .singleOrNull()?.get(UsersTable.displayName)
        return QuestionDto(
            id = qId,
            roundType = Round.valueOf(this[QuestionsTable.roundType]),
            prompt = this[QuestionsTable.prompt],
            answer = this[QuestionsTable.answer],
            references = this[QuestionsTable.references].split(",").filter { it.isNotBlank() },
            choices = json.decodeFromString(stringListSerializer, this[QuestionsTable.choices]),
            chapter = this[QuestionsTable.chapter],
            status = QuestionStatus.valueOf(this[QuestionsTable.status]),
            authorId = authorId,
            authorName = authorName,
            votes = voteCount.toInt(),
        )
    }
}
