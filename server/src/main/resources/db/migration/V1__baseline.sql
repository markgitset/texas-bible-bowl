-- Baseline: the schema as deployed at Flyway adoption (2026-07), i.e. exactly what
-- DatabaseFactory's SchemaUtils.create(...) + idempotent-ALTER pile produced on a fresh
-- database. An existing (prod) database is stamped at this version via baselineOnMigrate
-- and never executes this script; only fresh databases run it.
--
-- Known prod drift (harmless, superseded by V2): columns that were added to live
-- databases via the ALTER pile (team_members.registration_id, *.contestant_id,
-- registrations.site_id) lack the FK constraints / NOT NULL that this fresh-DB shape
-- declares. V2 must not assume those constraints exist.

CREATE TABLE users (
    id VARCHAR(36) NOT NULL,
    email VARCHAR(255) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    birthdate VARCHAR(10),
    is_adult BOOLEAN DEFAULT FALSE NOT NULL,
    contact_address VARCHAR(200) DEFAULT '' NOT NULL,
    contact_city VARCHAR(100) DEFAULT '' NOT NULL,
    contact_state VARCHAR(20) DEFAULT '' NOT NULL,
    contact_zip VARCHAR(10) DEFAULT '' NOT NULL,
    contact_phone VARCHAR(30) DEFAULT '' NOT NULL,
    contact_preference VARCHAR(8),
    password_hash VARCHAR(512) NOT NULL,
    CONSTRAINT users_pkey PRIMARY KEY (id),
    CONSTRAINT users_email_unique UNIQUE (email)
);

CREATE TABLE role_grants (
    user_id VARCHAR(36) NOT NULL,
    role VARCHAR(32) NOT NULL,
    scope_type VARCHAR(32) NOT NULL,
    scope_id VARCHAR(36),
    CONSTRAINT role_grants_user_id_role_scope_type_scope_id_unique
        UNIQUE (user_id, role, scope_type, scope_id),
    CONSTRAINT fk_role_grants_user_id__id FOREIGN KEY (user_id)
        REFERENCES users(id) ON UPDATE RESTRICT ON DELETE RESTRICT
);

CREATE TABLE questions (
    id VARCHAR(36) NOT NULL,
    round_type VARCHAR(40) NOT NULL,
    prompt TEXT NOT NULL,
    answer TEXT NOT NULL,
    refs TEXT DEFAULT '' NOT NULL,
    choices TEXT DEFAULT '[]' NOT NULL,
    chapter INTEGER,
    status VARCHAR(16) NOT NULL,
    author_id VARCHAR(36) NOT NULL,
    CONSTRAINT questions_pkey PRIMARY KEY (id),
    CONSTRAINT fk_questions_author_id__id FOREIGN KEY (author_id)
        REFERENCES users(id) ON UPDATE RESTRICT ON DELETE RESTRICT
);

CREATE TABLE question_votes (
    question_id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    CONSTRAINT question_votes_question_id_user_id_unique UNIQUE (question_id, user_id),
    CONSTRAINT fk_question_votes_question_id__id FOREIGN KEY (question_id)
        REFERENCES questions(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_question_votes_user_id__id FOREIGN KEY (user_id)
        REFERENCES users(id) ON UPDATE RESTRICT ON DELETE RESTRICT
);

CREATE TABLE esv_chapters (
    book_code VARCHAR(3) NOT NULL,
    chapter INTEGER NOT NULL,
    canonical VARCHAR(80) NOT NULL,
    body TEXT NOT NULL,
    CONSTRAINT pk_esv_chapters PRIMARY KEY (book_code, chapter)
);

CREATE TABLE text_annotations (
    study_set VARCHAR(64) NOT NULL,
    source_key VARCHAR(64) NOT NULL,
    text_hash INTEGER NOT NULL,
    def_digest VARCHAR(128) NOT NULL,
    body TEXT NOT NULL,
    CONSTRAINT pk_text_annotations PRIMARY KEY (study_set, source_key)
);

CREATE TABLE generated_pdfs (
    study_set VARCHAR(64) NOT NULL,
    file_name VARCHAR(160) NOT NULL,
    content_stamp INTEGER NOT NULL,
    created_at_epoch_ms BIGINT NOT NULL,
    body BYTEA NOT NULL,
    CONSTRAINT pk_generated_pdfs PRIMARY KEY (study_set, file_name)
);

CREATE TABLE seasons (
    event_year VARCHAR(8) NOT NULL,
    is_current BOOLEAN DEFAULT FALSE NOT NULL,
    payload TEXT NOT NULL,
    CONSTRAINT seasons_pkey PRIMARY KEY (event_year)
);

CREATE TABLE congregations (
    id VARCHAR(36) NOT NULL,
    name VARCHAR(160) NOT NULL,
    city VARCHAR(120) NOT NULL,
    state VARCHAR(2) DEFAULT '' NOT NULL,
    mailing_address VARCHAR(200) DEFAULT '' NOT NULL,
    zip VARCHAR(10) DEFAULT '' NOT NULL,
    phone VARCHAR(30) DEFAULT '' NOT NULL,
    code VARCHAR(2) DEFAULT '' NOT NULL,
    created_by_user_id VARCHAR(36) NOT NULL,
    created_at_epoch_ms BIGINT NOT NULL,
    CONSTRAINT congregations_pkey PRIMARY KEY (id),
    CONSTRAINT congregations_name_city_unique UNIQUE (name, city),
    CONSTRAINT fk_congregations_created_by_user_id__id FOREIGN KEY (created_by_user_id)
        REFERENCES users(id) ON UPDATE RESTRICT ON DELETE RESTRICT
);
CREATE UNIQUE INDEX congregations_code_key ON congregations (code) WHERE code <> '';

CREATE TABLE registrations (
    id VARCHAR(36) NOT NULL,
    congregation_id VARCHAR(36) NOT NULL,
    season_year VARCHAR(8) NOT NULL,
    status VARCHAR(16) NOT NULL,
    site_id VARCHAR(36),
    submitted_at_epoch_ms BIGINT,
    paid_at_epoch_ms BIGINT,
    updated_at_epoch_ms BIGINT NOT NULL,
    CONSTRAINT registrations_pkey PRIMARY KEY (id),
    CONSTRAINT registrations_congregation_id_season_year_unique UNIQUE (congregation_id, season_year),
    CONSTRAINT fk_registrations_congregation_id__id FOREIGN KEY (congregation_id)
        REFERENCES congregations(id) ON UPDATE RESTRICT ON DELETE RESTRICT
);

CREATE TABLE teams (
    id VARCHAR(36) NOT NULL,
    registration_id VARCHAR(36) NOT NULL,
    name VARCHAR(120) NOT NULL,
    sort_order INTEGER NOT NULL,
    CONSTRAINT teams_pkey PRIMARY KEY (id),
    CONSTRAINT teams_registration_id_name_unique UNIQUE (registration_id, name),
    CONSTRAINT fk_teams_registration_id__id FOREIGN KEY (registration_id)
        REFERENCES registrations(id) ON UPDATE RESTRICT ON DELETE RESTRICT
);

CREATE TABLE contestants (
    id VARCHAR(36) NOT NULL,
    congregation_id VARCHAR(36) NOT NULL,
    name VARCHAR(120) NOT NULL,
    birthdate VARCHAR(10),
    gender VARCHAR(6),
    first_season_year VARCHAR(4),
    graduation_year INTEGER,
    CONSTRAINT contestants_pkey PRIMARY KEY (id),
    CONSTRAINT fk_contestants_congregation_id__id FOREIGN KEY (congregation_id)
        REFERENCES congregations(id) ON UPDATE RESTRICT ON DELETE RESTRICT
);
CREATE INDEX contestants_congregation_id_name ON contestants (congregation_id, name);

CREATE TABLE pending_coach_grants (
    email VARCHAR(255) NOT NULL,
    congregation_id VARCHAR(36) NOT NULL,
    CONSTRAINT pk_pending_coach_grants PRIMARY KEY (email, congregation_id),
    CONSTRAINT fk_pending_coach_grants_congregation_id__id FOREIGN KEY (congregation_id)
        REFERENCES congregations(id) ON UPDATE RESTRICT ON DELETE RESTRICT
);

CREATE TABLE team_members (
    id VARCHAR(36) NOT NULL,
    team_id VARCHAR(36),
    registration_id VARCHAR(36) NOT NULL,
    contestant_id VARCHAR(36),
    shirt_size VARCHAR(8) NOT NULL,
    claim_code VARCHAR(12) NOT NULL,
    owner_user_id VARCHAR(36),
    CONSTRAINT team_members_pkey PRIMARY KEY (id),
    CONSTRAINT team_members_claim_code_unique UNIQUE (claim_code),
    CONSTRAINT fk_team_members_team_id__id FOREIGN KEY (team_id)
        REFERENCES teams(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_team_members_registration_id__id FOREIGN KEY (registration_id)
        REFERENCES registrations(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_team_members_contestant_id__id FOREIGN KEY (contestant_id)
        REFERENCES contestants(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_team_members_owner_user_id__id FOREIGN KEY (owner_user_id)
        REFERENCES users(id) ON UPDATE RESTRICT ON DELETE RESTRICT
);

CREATE TABLE individual_contestants (
    id VARCHAR(36) NOT NULL,
    registration_id VARCHAR(36) NOT NULL,
    contestant_id VARCHAR(36),
    shirt_size VARCHAR(8) NOT NULL,
    claim_code VARCHAR(12) NOT NULL,
    owner_user_id VARCHAR(36),
    tribe_leader BOOLEAN DEFAULT FALSE NOT NULL,
    CONSTRAINT individual_contestants_pkey PRIMARY KEY (id),
    CONSTRAINT individual_contestants_claim_code_unique UNIQUE (claim_code),
    CONSTRAINT fk_individual_contestants_registration_id__id FOREIGN KEY (registration_id)
        REFERENCES registrations(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_individual_contestants_contestant_id__id FOREIGN KEY (contestant_id)
        REFERENCES contestants(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_individual_contestants_owner_user_id__id FOREIGN KEY (owner_user_id)
        REFERENCES users(id) ON UPDATE RESTRICT ON DELETE RESTRICT
);

CREATE TABLE registration_guests (
    id VARCHAR(36) NOT NULL,
    registration_id VARCHAR(36) NOT NULL,
    name VARCHAR(120) NOT NULL,
    shirt_size VARCHAR(8),
    birthdate VARCHAR(10),
    gender VARCHAR(8),
    positions TEXT DEFAULT '[]' NOT NULL,
    tribe_leader BOOLEAN DEFAULT FALSE NOT NULL,
    contact_address VARCHAR(200) DEFAULT '' NOT NULL,
    contact_city VARCHAR(100) DEFAULT '' NOT NULL,
    contact_state VARCHAR(20) DEFAULT '' NOT NULL,
    contact_zip VARCHAR(10) DEFAULT '' NOT NULL,
    contact_phone VARCHAR(30) DEFAULT '' NOT NULL,
    contact_email VARCHAR(255) DEFAULT '' NOT NULL,
    contact_preference VARCHAR(8),
    CONSTRAINT registration_guests_pkey PRIMARY KEY (id),
    CONSTRAINT fk_registration_guests_registration_id__id FOREIGN KEY (registration_id)
        REFERENCES registrations(id) ON UPDATE RESTRICT ON DELETE RESTRICT
);

CREATE TABLE scores (
    roster_entry_id VARCHAR(36) NOT NULL,
    round VARCHAR(20) NOT NULL,
    points INTEGER NOT NULL,
    entered_by_user_id VARCHAR(36) NOT NULL,
    entered_at_epoch_ms BIGINT NOT NULL,
    CONSTRAINT pk_scores PRIMARY KEY (roster_entry_id, round),
    CONSTRAINT fk_scores_entered_by_user_id__id FOREIGN KEY (entered_by_user_id)
        REFERENCES users(id) ON UPDATE RESTRICT ON DELETE RESTRICT
);

CREATE TABLE score_releases (
    season_year VARCHAR(8) NOT NULL,
    released_at_epoch_ms BIGINT NOT NULL,
    released_by_user_id VARCHAR(36) NOT NULL,
    CONSTRAINT score_releases_pkey PRIMARY KEY (season_year),
    CONSTRAINT fk_score_releases_released_by_user_id__id FOREIGN KEY (released_by_user_id)
        REFERENCES users(id) ON UPDATE RESTRICT ON DELETE RESTRICT
);

CREATE TABLE cabins (
    id VARCHAR(36) NOT NULL,
    season_year VARCHAR(8) NOT NULL,
    site_id VARCHAR(36),
    name VARCHAR(120) NOT NULL,
    capacity INTEGER,
    CONSTRAINT cabins_pkey PRIMARY KEY (id)
);
CREATE INDEX cabins_season_year ON cabins (season_year);

CREATE TABLE cabin_assignments (
    id VARCHAR(36) NOT NULL,
    cabin_id VARCHAR(36) NOT NULL,
    congregation_id VARCHAR(36),
    gender VARCHAR(6),
    label VARCHAR(200) DEFAULT '' NOT NULL,
    sort_order INTEGER DEFAULT 0 NOT NULL,
    CONSTRAINT cabin_assignments_pkey PRIMARY KEY (id),
    CONSTRAINT fk_cabin_assignments_cabin_id__id FOREIGN KEY (cabin_id)
        REFERENCES cabins(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_cabin_assignments_congregation_id__id FOREIGN KEY (congregation_id)
        REFERENCES congregations(id) ON UPDATE RESTRICT ON DELETE RESTRICT
);

CREATE TABLE checkout_duties (
    season_year VARCHAR(8) NOT NULL,
    congregation_id VARCHAR(36) NOT NULL,
    adult_name VARCHAR(120) NOT NULL,
    CONSTRAINT pk_checkout_duties PRIMARY KEY (season_year, congregation_id),
    CONSTRAINT fk_checkout_duties_congregation_id__id FOREIGN KEY (congregation_id)
        REFERENCES congregations(id) ON UPDATE RESTRICT ON DELETE RESTRICT
);

CREATE TABLE tribes (
    id VARCHAR(36) NOT NULL,
    season_year VARCHAR(8) NOT NULL,
    site_id VARCHAR(36),
    name VARCHAR(120) NOT NULL,
    CONSTRAINT tribes_pkey PRIMARY KEY (id)
);
CREATE INDEX tribes_season_year ON tribes (season_year);

CREATE TABLE tribe_leaders (
    id VARCHAR(36) NOT NULL,
    tribe_id VARCHAR(36) NOT NULL,
    name VARCHAR(120) NOT NULL,
    sort_order INTEGER DEFAULT 0 NOT NULL,
    CONSTRAINT tribe_leaders_pkey PRIMARY KEY (id),
    CONSTRAINT fk_tribe_leaders_tribe_id__id FOREIGN KEY (tribe_id)
        REFERENCES tribes(id) ON UPDATE RESTRICT ON DELETE RESTRICT
);

CREATE TABLE tester_ids (
    season_year VARCHAR(8) NOT NULL,
    roster_entry_id VARCHAR(36) NOT NULL,
    tester_id INTEGER NOT NULL,
    assigned_at_epoch_ms BIGINT NOT NULL,
    CONSTRAINT pk_tester_ids PRIMARY KEY (season_year, roster_entry_id),
    CONSTRAINT tester_ids_season_year_tester_id_unique UNIQUE (season_year, tester_id)
);
