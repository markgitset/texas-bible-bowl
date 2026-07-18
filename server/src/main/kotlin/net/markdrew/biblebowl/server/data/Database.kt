package net.markdrew.biblebowl.server.data

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.net.URI
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Exposed table definitions: users, role grants, the crowd-sourced question bank, server-side
 * caches, and registration (congregations → registrations → teams → team_members). Scoring
 * tables land with the event-ops phase.
 */
object UsersTable : Table("users") {
    val id = varchar("id", 36)
    val email = varchar("email", 255).uniqueIndex()
    val displayName = varchar("display_name", 120)
    val birthdate = varchar("birthdate", 10).nullable() // ISO-8601; null for adults & legacy accounts
    val isAdult = bool("is_adult").default(false)
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

/** A congregation, created once by its first coach and reused season over season. */
object CongregationsTable : Table("congregations") {
    val id = varchar("id", 36)
    val name = varchar("name", 160)
    val city = varchar("city", 120)
    val state = varchar("state", 2).default("")
    val mailingAddress = varchar("mailing_address", 200).default("")
    val zip = varchar("zip", 10).default("")
    val createdByUserId = varchar("created_by_user_id", 36).references(UsersTable.id)
    val createdAtEpochMs = long("created_at_epoch_ms")
    override val primaryKey = PrimaryKey(id)
    init {
        uniqueIndex(name, city)
    }
}

/** One registration per (congregation, season year); teams/rosters hang off it, so they're per-season. */
object RegistrationsTable : Table("registrations") {
    val id = varchar("id", 36)
    val congregationId = varchar("congregation_id", 36).references(CongregationsTable.id)
    val seasonYear = varchar("season_year", 8)
    val status = varchar("status", 16)
    val submittedAtEpochMs = long("submitted_at_epoch_ms").nullable()
    val updatedAtEpochMs = long("updated_at_epoch_ms")
    override val primaryKey = PrimaryKey(id)
    init {
        uniqueIndex(congregationId, seasonYear)
    }
}

object TeamsTable : Table("teams") {
    val id = varchar("id", 36)
    val registrationId = varchar("registration_id", 36).references(RegistrationsTable.id)
    val name = varchar("name", 120)
    val sortOrder = integer("sort_order")
    override val primaryKey = PrimaryKey(id)
    init {
        uniqueIndex(registrationId, name)
    }
}

/** Roster entries (≤4 per team, enforced in the repository). Division is always computed, never stored. */
object TeamMembersTable : Table("team_members") {
    val id = varchar("id", 36)
    val teamId = varchar("team_id", 36).references(TeamsTable.id)
    val name = varchar("name", 120)
    val birthdate = varchar("birthdate", 10).nullable() // ISO-8601; null = adult-division contestant
    val shirtSize = varchar("shirt_size", 8)
    val claimCode = varchar("claim_code", 12).uniqueIndex()
    val ownerUserId = varchar("owner_user_id", 36).references(UsersTable.id).nullable()
    override val primaryKey = PrimaryKey(id)
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

/**
 * Cache of compiled season-text PDFs (the Typst compile is the expensive part of every /generate
 * request). Keyed by the canonical param-encoded filename (see shared-api's PdfFileNames), so the
 * key itself spells out how each PDF was generated. A row is valid only while [contentStamp]
 * matches the current study text + word-list digest; generation-code changes are invalidated
 * manually via `DELETE /generate/cache`.
 */
object GeneratedPdfsTable : Table("generated_pdfs") {
    val studySet = varchar("study_set", 64)
    val fileName = varchar("file_name", 160)
    val contentStamp = integer("content_stamp")
    val createdAtEpochMs = long("created_at_epoch_ms")
    val body = binary("body") // bytea
    override val primaryKey = PrimaryKey(studySet, fileName)
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
                TextAnnotationsTable, GeneratedPdfsTable, SeasonsTable,
                CongregationsTable, RegistrationsTable, TeamsTable, TeamMembersTable,
            )
            // SchemaUtils.create only creates missing *tables* — columns added after a table
            // first shipped need explicit (idempotent) ALTERs for existing databases.
            exec("ALTER TABLE congregations ADD COLUMN IF NOT EXISTS state VARCHAR(2) NOT NULL DEFAULT ''")
            exec("ALTER TABLE congregations ADD COLUMN IF NOT EXISTS mailing_address VARCHAR(200) NOT NULL DEFAULT ''")
            exec("ALTER TABLE congregations ADD COLUMN IF NOT EXISTS zip VARCHAR(10) NOT NULL DEFAULT ''")
            // Birthdates replaced self-reported grades (2026-07). Legacy grades are dropped, not
            // converted: affected users/roster entries fall back to "no division" until a birthdate
            // (or the adult flag) is set on the account/roster form.
            exec("ALTER TABLE users ADD COLUMN IF NOT EXISTS birthdate VARCHAR(10)")
            exec("ALTER TABLE users ADD COLUMN IF NOT EXISTS is_adult BOOLEAN NOT NULL DEFAULT FALSE")
            exec("ALTER TABLE users DROP COLUMN IF EXISTS grade")
            exec("ALTER TABLE team_members ADD COLUMN IF NOT EXISTS birthdate VARCHAR(10)")
            exec("ALTER TABLE team_members DROP COLUMN IF EXISTS grade")
        }
        return db
    }
}
