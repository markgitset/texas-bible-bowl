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
 * caches, registration (congregations → registrations → teams → team_members), and event-ops
 * scoring (scores + score_releases).
 */
object UsersTable : Table("users") {
    val id = varchar("id", 36)
    val email = varchar("email", 255).uniqueIndex()
    val displayName = varchar("display_name", 120)
    val birthdate = varchar("birthdate", 10).nullable() // ISO-8601; null for adults & legacy accounts
    val isAdult = bool("is_adult").default(false)
    // Optional adult contact details (item 9, F3) — free-form, "" when not provided.
    val contactAddress = varchar("contact_address", 200).default("")
    val contactCity = varchar("contact_city", 100).default("")
    val contactState = varchar("contact_state", 20).default("")
    val contactZip = varchar("contact_zip", 10).default("")
    val contactPhone = varchar("contact_phone", 30).default("")
    val contactPreference = varchar("contact_preference", 8).nullable()
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
    /** Optional contact phone, free-form; "" when not provided. */
    val phone = varchar("phone", 30).default("")
    /** Unique two-letter congregation code, e.g. "FB"; "" until a coach chooses one. */
    val code = varchar("code", 2).default("")
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
    // The season event site (EventSiteDto.id) this congregation attends; null until chosen —
    // permanently null (and fine) for single-site seasons, which never surface the choice.
    val siteId = varchar("site_id", 36).nullable()
    val submittedAtEpochMs = long("submitted_at_epoch_ms").nullable()
    val paidAtEpochMs = long("paid_at_epoch_ms").nullable()
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

/**
 * A durable youth contestant (a *person*), scoped to a congregation and reused season over season —
 * the identity that persists even though team assignments do not (a roster row is per-season, this
 * is not). A team_members row is one season's *enrollment* of a contestant; the contestant is the
 * single source of truth for the stable facts (name, birthdate, gender, and the experience anchor
 * [firstSeasonYear]). Identity is `(congregation, lower(name), birthdate)`. Adults (individual
 * contestants) are not modelled here yet.
 */
object ContestantsTable : Table("contestants") {
    val id = varchar("id", 36)
    val congregationId = varchar("congregation_id", 36).references(CongregationsTable.id)
    val name = varchar("name", 120)
    val birthdate = varchar("birthdate", 10).nullable() // null for adults, legacy rows, and seeded youth
    val gender = varchar("gender", 6).nullable()
    val firstSeasonYear = varchar("first_season_year", 4).nullable()
    // The event year this person finishes grade 12, derived from the 2026 workbook's school grade
    // (item 17, F13) — the import has grades but no birthdates. Non-null + null birthdate = a
    // *seeded youth*: treated as youth, real birthdate collected at first enrollment. Stays set
    // afterward as historical provenance; birthdate wins once present.
    val graduationYear = integer("graduation_year").nullable()
    override val primaryKey = PrimaryKey(id)
    init {
        index(false, congregationId, name) // lookups by congregation + name for find-or-create
    }
}

/**
 * A coach email known from the 2026 workbook import (item 17, F13), waiting for its account: when
 * someone registers with this email, they're granted the congregation-scoped COACH role and the
 * row is consumed. No accounts are created by the import itself.
 */
object PendingCoachGrantsTable : Table("pending_coach_grants") {
    val email = varchar("email", 255) // stored lowercased
    val congregationId = varchar("congregation_id", 36).references(CongregationsTable.id)
    override val primaryKey = PrimaryKey(email, congregationId)
}

/**
 * One season's enrollment of a [ContestantsTable] contestant onto a team (≤4/team, enforced in the
 * repository). Carries only the per-season facts — the team, the shirt size (kids grow), the claim
 * code, and who claimed it; the person's identity (name/birthdate/gender/experience) lives on the
 * contestant. Division is always computed, never stored.
 */
object TeamMembersTable : Table("team_members") {
    val id = varchar("id", 36)
    // Nullable: a member with no team is "unassigned" — eligible but not yet placed (e.g. their
    // team was deleted). Assigning sets this; deleting a team clears it rather than removing the row.
    val teamId = varchar("team_id", 36).references(TeamsTable.id).nullable()
    // Direct link to the registration so a teamless member is still scoped and queryable without a team.
    val registrationId = varchar("registration_id", 36).references(RegistrationsTable.id)
    // The durable contestant (person) this per-season enrollment belongs to — the source of identity.
    val contestantId = varchar("contestant_id", 36).references(ContestantsTable.id).nullable()
    val shirtSize = varchar("shirt_size", 8)
    val claimCode = varchar("claim_code", 12).uniqueIndex()
    val ownerUserId = varchar("owner_user_id", 36).references(UsersTable.id).nullable()
    override val primaryKey = PrimaryKey(id)
}

/** Individual (adult) contestants — never on a team, attached straight to the registration. */
object IndividualsTable : Table("individual_contestants") {
    val id = varchar("id", 36)
    val registrationId = varchar("registration_id", 36).references(RegistrationsTable.id)
    // The durable adult contestant (birthdate-less) this per-season entry belongs to — the source of
    // the person's identity (name/gender). The entry itself carries only the per-season facts below.
    val contestantId = varchar("contestant_id", 36).references(ContestantsTable.id).nullable()
    val shirtSize = varchar("shirt_size", 8)
    val claimCode = varchar("claim_code", 12).uniqueIndex()
    val ownerUserId = varchar("owner_user_id", 36).references(UsersTable.id).nullable()
    // Willing to serve as a tribe leader — any adult can, contestant or not (per-season answer).
    val tribeLeader = bool("tribe_leader").default(false)
    override val primaryKey = PrimaryKey(id)
}

/**
 * A registered guest (most are volunteers) — attends and pays by age tier (9+ at the volunteer
 * fee, 3–8 at the child fee, under-3s free) but is not a contestant: no team, no division, no
 * claim code, no durable-contestant link. Attached straight to the per-season registration, like
 * individual contestants.
 */
object RegistrationGuestsTable : Table("registration_guests") {
    val id = varchar("id", 36)
    val registrationId = varchar("registration_id", 36).references(RegistrationsTable.id)
    val name = varchar("name", 120)
    // Null = no included t-shirt (under-3 guests).
    val shirtSize = varchar("shirt_size", 8).nullable()
    // ISO-8601 birthdate, collected for children (under 9) so the fee tier derives from age each
    // season; null = adult guest (age 9+).
    val birthdate = varchar("birthdate", 10).nullable()
    // Null only on guests created before gender was collected.
    val gender = varchar("gender", 8).nullable()
    // Volunteer positions (JSON array of strings from the season's list); age-9+ guests only.
    val positions = text("positions").default("[]")
    // Willing to serve as a tribe leader; age-9+ guests only.
    val tribeLeader = bool("tribe_leader").default(false)
    // Optional adult contact details (item 9, F3) — guests have no account, so email included.
    val contactAddress = varchar("contact_address", 200).default("")
    val contactCity = varchar("contact_city", 100).default("")
    val contactState = varchar("contact_state", 20).default("")
    val contactZip = varchar("contact_zip", 10).default("")
    val contactPhone = varchar("contact_phone", 30).default("")
    val contactEmail = varchar("contact_email", 255).default("")
    val contactPreference = varchar("contact_preference", 8).nullable()
    override val primaryKey = PrimaryKey(id)
}

/**
 * One entered score cell: a contestant's points in one round. [rosterEntryId] references either
 * a team_members or an individual_contestants row (no FK — the id spaces are disjoint UUIDs),
 * which also keys the score to a season, since rosters hang off a per-season registration.
 */
object ScoresTable : Table("scores") {
    val rosterEntryId = varchar("roster_entry_id", 36)
    val round = varchar("round", 20)
    val points = integer("points")
    val enteredByUserId = varchar("entered_by_user_id", 36).references(UsersTable.id)
    val enteredAtEpochMs = long("entered_at_epoch_ms")
    override val primaryKey = PrimaryKey(rosterEntryId, round)
}

/**
 * A cabin (or other sleeping quarters — RV site, duplex) defined per season by the registrar
 * (item 15, F9). [siteId] is a season EventSiteDto id on multi-site seasons; null on single-site
 * seasons, mirroring registrations.site_id.
 */
object CabinsTable : Table("cabins") {
    val id = varchar("id", 36)
    val seasonYear = varchar("season_year", 8)
    val siteId = varchar("site_id", 36).nullable()
    val name = varchar("name", 120)
    // Optional bed count, shown beside the derived occupancy; null = unknown.
    val capacity = integer("capacity").nullable()
    override val primaryKey = PrimaryKey(id)
    init {
        index(false, seasonYear)
    }
}

/**
 * One free-form assignment row on a cabin: a congregation × gender group (gender null = the whole
 * congregation), an ad-hoc label-only row (families/staff), or a group row with a label as a note.
 * Occupant counts are always derived from the season's registrations, never stored.
 */
object CabinAssignmentsTable : Table("cabin_assignments") {
    val id = varchar("id", 36)
    val cabinId = varchar("cabin_id", 36).references(CabinsTable.id)
    val congregationId = varchar("congregation_id", 36).references(CongregationsTable.id).nullable()
    val gender = varchar("gender", 6).nullable()
    val label = varchar("label", 200).default("")
    // Rows keep their creation order in the grid (like teams.sort_order).
    val sortOrder = integer("sort_order").default(0)
    override val primaryKey = PrimaryKey(id)
}

/** Per-congregation cabin check-out duty: the one adult responsible at departure (free-form name). */
object CheckoutDutiesTable : Table("checkout_duties") {
    val seasonYear = varchar("season_year", 8)
    val congregationId = varchar("congregation_id", 36).references(CongregationsTable.id)
    val adultName = varchar("adult_name", 120)
    override val primaryKey = PrimaryKey(seasonYear, congregationId)
}

/**
 * A tester's assigned per-site sequential ID (registration backlog item 13, F7). Assigned lazily
 * and append-only — a number never changes or is reused once given (nametags print early), so
 * removals leave gaps, like the 2026 workbook. [rosterEntryId] references a team_members or
 * individual_contestants row (no FK — disjoint UUID spaces), same convention as [ScoresTable].
 */
object TesterIdsTable : Table("tester_ids") {
    val seasonYear = varchar("season_year", 8)
    val rosterEntryId = varchar("roster_entry_id", 36)
    val testerId = integer("tester_id")
    val assignedAtEpochMs = long("assigned_at_epoch_ms")
    override val primaryKey = PrimaryKey(seasonYear, rosterEntryId)
    init {
        uniqueIndex(seasonYear, testerId)
    }
}

/** A season's score release — present while released; retracting deletes the row. */
object ScoreReleasesTable : Table("score_releases") {
    val seasonYear = varchar("season_year", 8)
    val releasedAtEpochMs = long("released_at_epoch_ms")
    val releasedByUserId = varchar("released_by_user_id", 36).references(UsersTable.id)
    override val primaryKey = PrimaryKey(seasonYear)
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
                CongregationsTable, RegistrationsTable, TeamsTable, ContestantsTable, TeamMembersTable,
                IndividualsTable, RegistrationGuestsTable, ScoresTable, ScoreReleasesTable,
                CabinsTable, CabinAssignmentsTable, CheckoutDutiesTable, TesterIdsTable,
                PendingCoachGrantsTable,
            )
            // SchemaUtils.create only creates missing *tables* — columns added after a table
            // first shipped need explicit (idempotent) ALTERs for existing databases.
            exec("ALTER TABLE congregations ADD COLUMN IF NOT EXISTS state VARCHAR(2) NOT NULL DEFAULT ''")
            exec("ALTER TABLE congregations ADD COLUMN IF NOT EXISTS mailing_address VARCHAR(200) NOT NULL DEFAULT ''")
            exec("ALTER TABLE congregations ADD COLUMN IF NOT EXISTS zip VARCHAR(10) NOT NULL DEFAULT ''")
            exec("ALTER TABLE congregations ADD COLUMN IF NOT EXISTS phone VARCHAR(30) NOT NULL DEFAULT ''")
            exec("ALTER TABLE congregations ADD COLUMN IF NOT EXISTS code VARCHAR(2) NOT NULL DEFAULT ''")
            // Congregation codes are unique — but only among the ones actually assigned; unassigned
            // congregations all share the "" default, so the uniqueness is a partial index.
            exec("CREATE UNIQUE INDEX IF NOT EXISTS congregations_code_key ON congregations (code) WHERE code <> ''")
            exec("ALTER TABLE registrations ADD COLUMN IF NOT EXISTS paid_at_epoch_ms BIGINT")
            // Workbook-seeded youth (item 17, F13) carry a graduation year instead of a birthdate.
            exec("ALTER TABLE contestants ADD COLUMN IF NOT EXISTS graduation_year INTEGER")
            // Multi-site seasons (2026-07): each registration pins to one of the season's event sites.
            exec("ALTER TABLE registrations ADD COLUMN IF NOT EXISTS site_id VARCHAR(36)")
            // Birthdates replaced self-reported grades (2026-07). Legacy grades are dropped, not
            // converted: affected users/roster entries fall back to "no division" until a birthdate
            // (or the adult flag) is set on the account/roster form.
            exec("ALTER TABLE users ADD COLUMN IF NOT EXISTS birthdate VARCHAR(10)")
            exec("ALTER TABLE users ADD COLUMN IF NOT EXISTS is_adult BOOLEAN NOT NULL DEFAULT FALSE")
            exec("ALTER TABLE users DROP COLUMN IF EXISTS grade")
            exec("ALTER TABLE team_members DROP COLUMN IF EXISTS grade")
            // Deleting a team frees its members (they become unassigned) rather than deleting them
            // (2026-07): give each member a direct registration link and let team_id go null.
            exec("ALTER TABLE team_members ADD COLUMN IF NOT EXISTS registration_id VARCHAR(36)")
            exec(
                "UPDATE team_members tm SET registration_id = t.registration_id " +
                    "FROM teams t WHERE t.id = tm.team_id AND tm.registration_id IS NULL"
            )
            exec("ALTER TABLE team_members ALTER COLUMN team_id DROP NOT NULL")
            // Durable contestants: each per-season roster row links to a durable contestant (added in
            // the phase-1 release, which also backfilled the contestants table from team_members).
            exec("ALTER TABLE team_members ADD COLUMN IF NOT EXISTS contestant_id VARCHAR(36)")
            // Phase 4 (2026-07): the contestant is now the single source of truth for a person's identity,
            // so the denormalized copies are dropped from the per-season enrollment row. Existing databases
            // are already fully linked (the phase-1 backfill ran on deploy); a fresh database starts empty,
            // so nothing needs migrating here — just drop the dead columns.
            exec("ALTER TABLE team_members DROP COLUMN IF EXISTS name")
            exec("ALTER TABLE team_members DROP COLUMN IF EXISTS birthdate")
            exec("ALTER TABLE team_members DROP COLUMN IF EXISTS gender")
            exec("ALTER TABLE team_members DROP COLUMN IF EXISTS first_season_year")
            // Durable adults: individual (adult) contestants link to a birthdate-less contestant (added
            // in the phase-5 release, which also backfilled contestants from individual_contestants).
            exec("ALTER TABLE individual_contestants ADD COLUMN IF NOT EXISTS contestant_id VARCHAR(36)")
            // Phase 6 (2026-07): the contestant is now the single source of truth for an adult's identity
            // too, so the denormalized copies are dropped from the per-season entry (mirrors phase 4 for
            // youth). Existing databases already carry adult identity on contestants (the phase-5 backfill
            // ran on deploy); a fresh database starts empty — just drop the dead columns.
            exec("ALTER TABLE individual_contestants DROP COLUMN IF EXISTS name")
            exec("ALTER TABLE individual_contestants DROP COLUMN IF EXISTS gender")
            // Guests gained an age tier (replacing the child boolean), gender, and an optional
            // shirt (2026-07, registration backlog F1): under-3s attend free with no included
            // shirt. The old boolean is converted in place, then dropped — guarded so the
            // backfill only runs while the legacy column still exists.
            exec("ALTER TABLE registration_guests ADD COLUMN IF NOT EXISTS gender VARCHAR(8)")
            exec("ALTER TABLE registration_guests ALTER COLUMN shirt_size DROP NOT NULL")
            // The transient age_tier column only materializes while legacy is_child data still
            // needs converting — F5's birthdate migration below then folds it into a birthdate.
            exec(
                """
                DO ${'$'}${'$'} BEGIN
                    IF EXISTS (SELECT 1 FROM information_schema.columns
                               WHERE table_name = 'registration_guests' AND column_name = 'is_child') THEN
                        ALTER TABLE registration_guests
                            ADD COLUMN IF NOT EXISTS age_tier VARCHAR(12) NOT NULL DEFAULT 'AGE_9_PLUS';
                        UPDATE registration_guests SET age_tier = 'AGE_3_TO_8' WHERE is_child;
                        ALTER TABLE registration_guests DROP COLUMN is_child;
                    END IF;
                END ${'$'}${'$'}
                """.trimIndent()
            )
            // Volunteer positions + tribe-leader willingness (2026-07, registration backlog F2):
            // positions on adult guests; any adult (guest or individual contestant) may lead a tribe.
            exec("ALTER TABLE registration_guests ADD COLUMN IF NOT EXISTS positions TEXT NOT NULL DEFAULT '[]'")
            exec("ALTER TABLE registration_guests ADD COLUMN IF NOT EXISTS tribe_leader BOOLEAN NOT NULL DEFAULT FALSE")
            exec("ALTER TABLE individual_contestants ADD COLUMN IF NOT EXISTS tribe_leader BOOLEAN NOT NULL DEFAULT FALSE")
            // Adult contact info + communication preference (2026-07, registration backlog F3):
            // optional per-adult contact on user accounts, and on guests (who have no account,
            // hence the extra email column there).
            for (col in listOf(
                "contact_address VARCHAR(200) NOT NULL DEFAULT ''",
                "contact_city VARCHAR(100) NOT NULL DEFAULT ''",
                "contact_state VARCHAR(20) NOT NULL DEFAULT ''",
                "contact_zip VARCHAR(10) NOT NULL DEFAULT ''",
                "contact_phone VARCHAR(30) NOT NULL DEFAULT ''",
                "contact_preference VARCHAR(8)",
            )) {
                exec("ALTER TABLE users ADD COLUMN IF NOT EXISTS $col")
                exec("ALTER TABLE registration_guests ADD COLUMN IF NOT EXISTS $col")
            }
            exec("ALTER TABLE registration_guests ADD COLUMN IF NOT EXISTS contact_email VARCHAR(255) NOT NULL DEFAULT ''")
            // Child guests now carry a birthdate and the fee tier derives from age (2026-07,
            // registration backlog F5) — the stored tier is dropped. Pre-birthdate child rows
            // (admin test data; the feature deploys dark) get an approximate birthdate that lands
            // in the same tier for the 2027 season, guarded so this runs only while the legacy
            // column still exists.
            exec("ALTER TABLE registration_guests ADD COLUMN IF NOT EXISTS birthdate VARCHAR(10)")
            exec(
                """
                DO ${'$'}${'$'} BEGIN
                    IF EXISTS (SELECT 1 FROM information_schema.columns
                               WHERE table_name = 'registration_guests' AND column_name = 'age_tier') THEN
                        UPDATE registration_guests SET birthdate = '2020-06-15'
                            WHERE birthdate IS NULL AND age_tier = 'AGE_3_TO_8';
                        UPDATE registration_guests SET birthdate = '2025-06-15'
                            WHERE birthdate IS NULL AND age_tier = 'UNDER_3';
                        ALTER TABLE registration_guests DROP COLUMN age_tier;
                    END IF;
                END ${'$'}${'$'}
                """.trimIndent()
            )
        }
        return db
    }
}
