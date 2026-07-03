package net.markdrew.biblebowl.server.data

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Exposed table definitions for the MVP: users, role grants, and the crowd-sourced question bank.
 * Registration/teams/scoring tables land with their phases.
 */
object UsersTable : Table("users") {
    val id = varchar("id", 36)
    val email = varchar("email", 255).uniqueIndex()
    val displayName = varchar("display_name", 120)
    val grade = integer("grade").nullable()
    val passwordHash = varchar("password_hash", 512)
    override val primaryKey = PrimaryKey(id)
}

object RoleGrantsTable : Table("role_grants") {
    val userId = varchar("user_id", 36).references(UsersTable.id)
    val role = varchar("role", 32)
    val scopeType = varchar("scope_type", 32)
    val scopeId = varchar("scope_id", 36).nullable()
    init {
        uniqueIndex(userId, role, scopeType, scopeId)
    }
}

object QuestionsTable : Table("questions") {
    val id = varchar("id", 36)
    val roundType = varchar("round_type", 40)
    val prompt = text("prompt")
    val answer = text("answer")
    /** Comma-separated serialized VerseRefs, e.g. "ACT2:38" (parseable by core VerseRef). */
    val references = text("refs").default("")
    /** JSON-encoded list of multiple-choice options (empty for non-MC rounds). */
    val choices = text("choices").default("[]")
    val chapter = integer("chapter").nullable()
    val status = varchar("status", 16)
    val authorId = varchar("author_id", 36).references(UsersTable.id)
    override val primaryKey = PrimaryKey(id)
}

object QuestionVotesTable : Table("question_votes") {
    val questionId = varchar("question_id", 36).references(QuestionsTable.id)
    val userId = varchar("user_id", 36).references(UsersTable.id)
    init {
        uniqueIndex(questionId, userId)
    }
}

/** Cached ESV chapter text (licensed; server-side only). Key = (book code, chapter). */
object EsvChaptersTable : Table("esv_chapters") {
    val bookCode = varchar("book_code", 3)
    val chapter = integer("chapter")
    val canonical = varchar("canonical", 80)
    val body = text("body")
    override val primaryKey = PrimaryKey(bookCode, chapter)
}

/** Connects a Hikari pool to Postgres and creates any missing tables. */
object DatabaseFactory {
    fun connect(
        url: String = System.getenv("DATABASE_URL") ?: "jdbc:postgresql://localhost:5432/biblebowl",
        user: String = System.getenv("DATABASE_USER") ?: "biblebowl",
        password: String = System.getenv("DATABASE_PASSWORD") ?: "biblebowl-dev",
    ): Database {
        val config = HikariConfig().apply {
            jdbcUrl = url
            username = user
            this.password = password
            maximumPoolSize = 5 // Cloud Run scale-to-zero friendly
            isAutoCommit = false
        }
        val db = Database.connect(HikariDataSource(config))
        transaction(db) {
            SchemaUtils.create(UsersTable, RoleGrantsTable, QuestionsTable, QuestionVotesTable, EsvChaptersTable)
        }
        return db
    }
}
