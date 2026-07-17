package net.markdrew.biblebowl.server.data

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import net.markdrew.biblebowl.api.QuestionDto
import net.markdrew.biblebowl.api.QuestionStatus
import net.markdrew.biblebowl.api.Role
import net.markdrew.biblebowl.api.RoleGrant
import net.markdrew.biblebowl.model.Round
import net.markdrew.biblebowl.api.ScopeType
import net.markdrew.biblebowl.api.SubmitQuestionRequest
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID

private val json = Json { ignoreUnknownKeys = true }
private val stringListSerializer = ListSerializer(String.serializer())

class PostgresUserRepository(private val db: Database) : UserRepository {

    override fun create(
        email: String, displayName: String, grade: Int?, passwordHash: String, roles: List<RoleGrant>,
    ): UserRecord = transaction(db) {
        val userId = UUID.randomUUID().toString()
        UsersTable.insert {
            it[id] = userId
            it[UsersTable.email] = email.lowercase()
            it[UsersTable.displayName] = displayName
            it[UsersTable.grade] = grade
            it[UsersTable.passwordHash] = passwordHash
        }
        roles.forEach { grant ->
            RoleGrantsTable.insert {
                it[RoleGrantsTable.userId] = userId
                it[role] = grant.role.name
                it[scopeType] = grant.scopeType.name
                it[scopeId] = grant.scopeId
            }
        }
        UserRecord(userId, email.lowercase(), displayName, grade, passwordHash, roles.toMutableList())
    }

    override fun findByEmail(email: String): UserRecord? = transaction(db) {
        UsersTable.selectAll().where { UsersTable.email eq email.lowercase() }.singleOrNull()?.toUserRecord()
    }

    override fun findById(id: String): UserRecord? = transaction(db) {
        UsersTable.selectAll().where { UsersTable.id eq id }.singleOrNull()?.toUserRecord()
    }

    override fun addRoleGrant(userId: String, grant: RoleGrant) {
        transaction(db) {
            RoleGrantsTable.insertIgnore {
                it[RoleGrantsTable.userId] = userId
                it[role] = grant.role.name
                it[scopeType] = grant.scopeType.name
                it[scopeId] = grant.scopeId
            }
        }
    }

    private fun ResultRow.toUserRecord(): UserRecord {
        val userId = this[UsersTable.id]
        val grants = RoleGrantsTable.selectAll().where { RoleGrantsTable.userId eq userId }.map {
            RoleGrant(
                role = Role.valueOf(it[RoleGrantsTable.role]),
                scopeType = ScopeType.valueOf(it[RoleGrantsTable.scopeType]),
                scopeId = it[RoleGrantsTable.scopeId],
            )
        }
        return UserRecord(
            id = userId,
            email = this[UsersTable.email],
            displayName = this[UsersTable.displayName],
            grade = this[UsersTable.grade],
            passwordHash = this[UsersTable.passwordHash],
            roles = grants.toMutableList(),
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
