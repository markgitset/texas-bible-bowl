package net.markdrew.biblebowl.server.data

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.net.URI
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

/**
 * Persistent cache of computed text-analysis layers (e.g. the word-list category resolution that drives
 * name/number highlighting) — the Postgres equivalent of bible-bowl's on-disk annotation sidecars. A row is
 * valid only while [textHash] and [defDigest] match the current study text and word-list/override content.
 */
object TextAnnotationsTable : Table("text_annotations") {
    val studySet = varchar("study_set", 64)
    val sourceKey = varchar("source_key", 64)
    val textHash = integer("text_hash")
    val defDigest = varchar("def_digest", 128)
    val body = text("body") // TSV: startOffset<TAB>endOffset<TAB>value per line
    override val primaryKey = PrimaryKey(studySet, sourceKey)
}

/** JDBC url + credentials, however they were supplied (a single PG URL or separate env vars). */
data class DbSettings(val jdbcUrl: String, val user: String?, val password: String?) {
    companion object {
        /**
         * Resolves connection settings from the environment.
         *
         * Accepts either a managed-Postgres connection string (`postgres://user:pass@host/db?sslmode=require`,
         * as Neon/Supabase/Heroku/Fly hand out) in `DATABASE_URL`, or a `jdbc:postgresql://…` url with the
         * credentials in `DATABASE_USER`/`DATABASE_PASSWORD`. Explicit `DATABASE_USER`/`DATABASE_PASSWORD`
         * always override any credentials embedded in the URL.
         */
        fun fromEnv(
            rawUrl: String? = System.getenv("DATABASE_URL"),
            envUser: String? = System.getenv("DATABASE_USER"),
            envPassword: String? = System.getenv("DATABASE_PASSWORD"),
        ): DbSettings {
            val url = rawUrl ?: "jdbc:postgresql://localhost:5432/biblebowl"
            return if (url.startsWith("postgres://") || url.startsWith("postgresql://")) {
                val uri = URI(url)
                val (embeddedUser, embeddedPassword) = uri.userInfo
                    ?.split(":", limit = 2)
                    ?.let { it[0] to it.getOrNull(1) }
                    ?: (null to null)
                val portPart = if (uri.port >= 0) ":${uri.port}" else ""
                val queryPart = uri.query?.let { "?$it" } ?: ""
                DbSettings(
                    jdbcUrl = "jdbc:postgresql://${uri.host}$portPart${uri.path}$queryPart",
                    user = envUser ?: embeddedUser,
                    password = envPassword ?: embeddedPassword,
                )
            } else {
                DbSettings(
                    jdbcUrl = url,
                    user = envUser ?: "biblebowl",
                    password = envPassword ?: "biblebowl-dev",
                )
            }
        }
    }
}

/** Connects a Hikari pool to Postgres and creates any missing tables. */
object DatabaseFactory {
    fun connect(settings: DbSettings = DbSettings.fromEnv()): Database {
        val config = HikariConfig().apply {
            jdbcUrl = settings.jdbcUrl
            settings.user?.let { username = it }
            settings.password?.let { this.password = it }
            maximumPoolSize = 5 // scale-to-zero friendly (Cloud Run / Fly auto-stop)
            isAutoCommit = false
        }
        val db = Database.connect(HikariDataSource(config))
        transaction(db) {
            SchemaUtils.create(
                UsersTable, RoleGrantsTable, QuestionsTable, QuestionVotesTable, EsvChaptersTable,
                TextAnnotationsTable,
            )
        }
        return db
    }
}
