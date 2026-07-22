package net.markdrew.biblebowl.server.data

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.net.URI
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.date
import org.jetbrains.exposed.v1.jdbc.Database

/**
 * Exposed table definitions — description-only mirrors of the Flyway-owned schema (see
 * `db/migration/`). The person-centric model (V2): every human is exactly one `people` row;
 * `participants` holds the per-(person, season) facts; `users` is auth only.
 */
object UsersTable : Table("users") {
    val id = varchar("id", 36)
    val email = varchar("email", 255).uniqueIndex() // login identity
    val displayName = varchar("display_name", 120) // used until a person is linked
    val passwordHash = varchar("password_hash", 512)
    // The person this account IS (see PeopleTable.managedByUserId for "manages"): null until
    // claimed or email-matched at signup. Declared without .references() to avoid the
    // UsersTable <-> PeopleTable object-initialization cycle; the DB enforces the FK.
    val personId = varchar("person_id", 36).nullable().uniqueIndex()
    override val primaryKey = PrimaryKey(id)
}

/**
 * Every human, exactly once — contestants, guests, coaches, volunteers. Identity facts live ONLY
 * here; people are global, not congregation-scoped (a person keeps their history when their
 * congregation changes). Per-season facts live on [ParticipantsTable].
 */
object PeopleTable : Table("people") {
    val id = varchar("id", 36)
    val name = varchar("name", 120)
    val birthdate = date("birthdate").nullable() // null = birthdate-less adult or seeded youth
    val isAdult = bool("is_adult").default(false) // explicit for birthdate-less rows
    val gender = varchar("gender", 6).nullable() // MALE/FEMALE (check constraint)
    // Seeded-youth provenance: the event year this person finishes grade 12, from the 2026
    // workbook's school grade. Non-null + null birthdate = seeded youth; birthdate wins once set.
    val graduationYear = integer("graduation_year").nullable()
    // Authoritative experience anchor. NOT an FK: it may predate any seasons row (pre-app history).
    val firstSeasonYear = integer("first_season_year").nullable()
    // Contact + signup matching; NOT unique (one parent's email appears on several kids).
    val email = varchar("email", 255).nullable()
    val contactAddress = varchar("contact_address", 200).default("")
    val contactCity = varchar("contact_city", 100).default("")
    val contactState = varchar("contact_state", 20).default("")
    val contactZip = varchar("contact_zip", 10).default("")
    val contactPhone = varchar("contact_phone", 30).default("")
    val contactPreference = varchar("contact_preference", 8).nullable()
    // The person's claim code (coach-shared): claiming it links an account as this person's manager.
    val claimCode = varchar("claim_code", 12).uniqueIndex()
    // The account that MANAGES this person: a parent, or the person's own account (self-claims
    // also set UsersTable.personId).
    val managedByUserId = varchar("managed_by_user_id", 36).references(UsersTable.id).nullable()
    override val primaryKey = PrimaryKey(id)
}

object RoleGrantsTable : Table("role_grants") {
    val id = varchar("id", 36)
    val userId = varchar("user_id", 36).references(UsersTable.id)
    val role = varchar("role", 32)
    val scopeType = varchar("scope_type", 32)
    val scopeId = varchar("scope_id", 36).nullable()
    override val primaryKey = PrimaryKey(id)
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
    val id = varchar("id", 36)
    val questionId = varchar("question_id", 36).references(QuestionsTable.id)
    val userId = varchar("user_id", 36).references(UsersTable.id)
    override val primaryKey = PrimaryKey(id)
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

/**
 * An event site, promoted out of the season payload JSON (V2) so registrations/cabins/tribes can
 * FK it. Ids keep the existing EventSiteDto slug convention. Single-site seasons have exactly one
 * row — no more "null site" special case; registrations auto-pin to the lone site.
 */
object SeasonSitesTable : Table("season_sites") {
    val id = varchar("id", 36)
    val seasonYear = integer("season_year").references(SeasonsTable.year)
    val name = varchar("name", 120)
    val address = varchar("address", 200).default("")
    // Sites keep the season editor's list order (SeasonDto.sites order is meaningful).
    val sortOrder = integer("sort_order").default(0)
    override val primaryKey = PrimaryKey(id)
}

/** One registration per (congregation, season); teams/participants hang off it, so they're per-season. */
object RegistrationsTable : Table("registrations") {
    val id = varchar("id", 36)
    val congregationId = varchar("congregation_id", 36).references(CongregationsTable.id)
    val seasonYear = integer("season_year").references(SeasonsTable.year)
    val status = varchar("status", 16)
    // The season site this congregation attends; null = not chosen yet (never "no sites" — a
    // single-site season auto-pins on registration creation).
    val siteId = varchar("site_id", 36).references(SeasonSitesTable.id).nullable()
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
 * One row per (person, season): the per-season facts for every attendee — replaces the old
 * `team_members`, `individual_contestants`, and `registration_guests`. Facets, not a role enum:
 * an adult contestant can also volunteer ([positions]) or coach ([isCoach]). Youth/adult,
 * division, and fee tier all stay DERIVED from the person's birthdate/isAdult.
 */
object ParticipantsTable : Table("participants") {
    val id = varchar("id", 36)
    val personId = varchar("person_id", 36).references(PeopleTable.id)
    val registrationId = varchar("registration_id", 36).references(RegistrationsTable.id)
    // Denormalized copy of the registration's season so the DB can enforce
    // UNIQUE (person_id, season_year); the repository keeps it consistent.
    val seasonYear = integer("season_year").references(SeasonsTable.year)
    val isContestant = bool("is_contestant").default(false)
    // Coach facet — replaces pending_coach_grants: at signup, email-match the person and grant
    // the congregation-scoped COACH role for each isCoach participation.
    val isCoach = bool("is_coach").default(false)
    // Youth contestants only; null = unassigned (or not a youth teamer). ON DELETE SET NULL.
    val teamId = varchar("team_id", 36).references(TeamsTable.id).nullable()
    // Per-season shirt-order snapshot (kids grow); null = no included shirt (under-3 guests).
    val shirtSize = varchar("shirt_size", 8).nullable()
    // Volunteer positions (JSON array of strings from the season's list); adults.
    val positions = text("positions").default("[]")
    val tribeLeader = bool("tribe_leader").default(false)
    // Null until assigned; allocated from SeasonsTable.nextTesterId (season-wide, append-only,
    // never reused). UNIQUE (season_year, tester_id) — DB-enforced now.
    val testerId = integer("tester_id").nullable()
    override val primaryKey = PrimaryKey(id)
    init {
        uniqueIndex(personId, seasonYear)
        uniqueIndex(seasonYear, testerId)
    }
}

/** One entered score cell: a participant's points in one round. */
object ScoresTable : Table("scores") {
    val id = varchar("id", 36)
    val participantId = varchar("participant_id", 36).references(ParticipantsTable.id)
    val round = varchar("round", 20)
    val points = integer("points")
    val enteredByUserId = varchar("entered_by_user_id", 36).references(UsersTable.id)
    val enteredAtEpochMs = long("entered_at_epoch_ms")
    override val primaryKey = PrimaryKey(id)
    init {
        uniqueIndex(participantId, round)
    }
}

/**
 * A cabin (or other sleeping quarters — RV site, duplex) defined per season by the registrar
 * (item 15, F9). Always pinned to a season site (single-site seasons have exactly one).
 */
object CabinsTable : Table("cabins") {
    val id = varchar("id", 36)
    val seasonYear = integer("season_year").references(SeasonsTable.year)
    val siteId = varchar("site_id", 36).references(SeasonSitesTable.id)
    val name = varchar("name", 120)
    // Optional bed count, shown beside the derived occupancy; null = unknown.
    val capacity = integer("capacity").nullable()
    override val primaryKey = PrimaryKey(id)
}

/**
 * One free-form assignment row on a cabin: a congregation × gender group, an ad-hoc label-only
 * row (families/staff), or a group row with a label as a note. Gender null = the row deliberately
 * spans genders (a whole congregation, or a mixed family row); MALE/FEMALE when set (check
 * constraint). Occupant counts are always derived from the season's registrations, never stored.
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

/** Per-congregation cabin check-out duty: the one adult (a person) responsible at departure. */
object CheckoutDutiesTable : Table("checkout_duties") {
    val id = varchar("id", 36)
    val seasonYear = integer("season_year").references(SeasonsTable.year)
    val congregationId = varchar("congregation_id", 36).references(CongregationsTable.id)
    val personId = varchar("person_id", 36).references(PeopleTable.id)
    override val primaryKey = PrimaryKey(id)
    init {
        uniqueIndex(seasonYear, congregationId)
    }
}

/**
 * A tribe defined per season by the registrar (item 16, F10; 2026 used color names). Always
 * pinned to a season site, like cabins.
 */
object TribesTable : Table("tribes") {
    val id = varchar("id", 36)
    val seasonYear = integer("season_year").references(SeasonsTable.year)
    val siteId = varchar("site_id", 36).references(SeasonSitesTable.id)
    val name = varchar("name", 120)
    override val primaryKey = PrimaryKey(id)
}

/** One assigned tribe leader — a registered attendee (participant), no longer a free-form name. */
object TribeLeadersTable : Table("tribe_leaders") {
    val id = varchar("id", 36)
    val tribeId = varchar("tribe_id", 36).references(TribesTable.id)
    val participantId = varchar("participant_id", 36).references(ParticipantsTable.id)
    // Leaders keep their assignment order (like cabin_assignments.sort_order).
    val sortOrder = integer("sort_order").default(0)
    override val primaryKey = PrimaryKey(id)
}

/** A season's score release — present while released; retracting deletes the row. */
object ScoreReleasesTable : Table("score_releases") {
    val seasonYear = integer("season_year").references(SeasonsTable.year)
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

/**
 * Connects a Hikari pool to Postgres after running Flyway migrations. Flyway owns the schema:
 * `db/migration/V1__baseline.sql` is the pre-Flyway as-deployed schema (existing databases are
 * baseline-stamped at V1 without executing it; fresh databases run it), and every change since is
 * a new versioned script. The Exposed table objects above are description-only mirrors.
 */
object DatabaseFactory {
    fun connect(settings: DbSettings = DbSettings.fromEnv()): Database {
        val config = HikariConfig().apply {
            jdbcUrl = settings.jdbcUrl
            settings.user?.let { username = it }
            settings.password?.let { this.password = it }
            maximumPoolSize = 5 // scale-to-zero friendly (Cloud Run / Fly auto-stop)
            isAutoCommit = false
        }
        val dataSource = HikariDataSource(config)
        Flyway.configure()
            .dataSource(dataSource)
            .baselineOnMigrate(true) // pre-Flyway databases: record V1 as applied, don't run it
            .load()
            .migrate()
        return Database.connect(dataSource)
    }
}
