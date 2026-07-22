-- People/participants restructure (docs/schema-redesign-plan.md, 2026-07).
--
-- One `people` table for every human (identity facts appear only there; people are global,
-- not congregation-scoped) and one `participants` table for the per-(person, season) facts —
-- replacing contestants, team_members, individual_contestants, registration_guests,
-- pending_coach_grants, and tester_ids. Every by-convention reference becomes a real FK:
-- seasons.year is an INT PK, sites are rows (season_sites), scores/tribe_leaders reference
-- participants, checkout_duties references people.
--
-- Data migration runs in the same transaction. Key trick: new rows reuse old UUIDs wherever
-- possible (participants.id = the old team_members/individual_contestants row id — the two id
-- spaces are disjoint — and registration_guests ids become participant ids too), so scores and
-- tester ids migrate by join, not by lookup table.
--
-- Defensive notes (see V1 header): columns the pre-Flyway ALTER pile added to live databases
-- (team_members.registration_id, *.contestant_id, registrations.site_id) may lack FK/NOT NULL
-- there, so this script never assumes those constraints — it validates by join instead.

-- ---------------------------------------------------------------------------
-- 0. Stash old data in temp tables and drop the tables being rebuilt (so the
--    new tables get the canonical names and constraint names).
-- ---------------------------------------------------------------------------

CREATE TEMP TABLE seasons_v1 ON COMMIT DROP AS SELECT * FROM seasons;
CREATE TEMP TABLE registrations_v1 ON COMMIT DROP AS SELECT * FROM registrations;
CREATE TEMP TABLE teams_v1 ON COMMIT DROP AS SELECT * FROM teams;
CREATE TEMP TABLE contestants_v1 ON COMMIT DROP AS SELECT * FROM contestants;
CREATE TEMP TABLE team_members_v1 ON COMMIT DROP AS SELECT * FROM team_members;
CREATE TEMP TABLE individuals_v1 ON COMMIT DROP AS SELECT * FROM individual_contestants;
CREATE TEMP TABLE guests_v1 ON COMMIT DROP AS SELECT * FROM registration_guests;
CREATE TEMP TABLE pending_coach_v1 ON COMMIT DROP AS SELECT * FROM pending_coach_grants;
CREATE TEMP TABLE tester_ids_v1 ON COMMIT DROP AS SELECT * FROM tester_ids;
CREATE TEMP TABLE scores_v1 ON COMMIT DROP AS SELECT * FROM scores;
CREATE TEMP TABLE score_releases_v1 ON COMMIT DROP AS SELECT * FROM score_releases;
CREATE TEMP TABLE cabins_v1 ON COMMIT DROP AS SELECT * FROM cabins;
CREATE TEMP TABLE cabin_assignments_v1 ON COMMIT DROP AS SELECT * FROM cabin_assignments;
CREATE TEMP TABLE checkout_duties_v1 ON COMMIT DROP AS SELECT * FROM checkout_duties;
CREATE TEMP TABLE tribes_v1 ON COMMIT DROP AS SELECT * FROM tribes;
CREATE TEMP TABLE tribe_leaders_v1 ON COMMIT DROP AS SELECT * FROM tribe_leaders;

DROP TABLE tribe_leaders, tribes, checkout_duties, cabin_assignments, cabins,
    score_releases, scores, tester_ids, pending_coach_grants, registration_guests,
    individual_contestants, team_members, contestants, teams, registrations, seasons;

-- ---------------------------------------------------------------------------
-- 1. New schema
-- ---------------------------------------------------------------------------

CREATE TABLE seasons (
    year INTEGER NOT NULL,
    is_current BOOLEAN DEFAULT FALSE NOT NULL,
    next_tester_id INTEGER DEFAULT 1 NOT NULL,
    payload TEXT NOT NULL,
    CONSTRAINT seasons_pkey PRIMARY KEY (year)
);

CREATE TABLE season_sites (
    id VARCHAR(36) NOT NULL,
    season_year INTEGER NOT NULL,
    name VARCHAR(120) NOT NULL,
    address VARCHAR(200) DEFAULT '' NOT NULL,
    sort_order INTEGER DEFAULT 0 NOT NULL,
    CONSTRAINT season_sites_pkey PRIMARY KEY (id),
    CONSTRAINT fk_season_sites_season_year__year FOREIGN KEY (season_year)
        REFERENCES seasons(year) ON UPDATE RESTRICT ON DELETE RESTRICT
);
CREATE INDEX season_sites_season_year ON season_sites (season_year);

CREATE TABLE people (
    id VARCHAR(36) NOT NULL,
    name VARCHAR(120) NOT NULL,
    birthdate DATE,
    is_adult BOOLEAN DEFAULT FALSE NOT NULL,
    gender VARCHAR(6),
    graduation_year INTEGER,
    first_season_year INTEGER, -- experience anchor; NOT an FK (may predate any seasons row)
    email VARCHAR(255),        -- contact + signup matching; NOT unique (parents share)
    contact_address VARCHAR(200) DEFAULT '' NOT NULL,
    contact_city VARCHAR(100) DEFAULT '' NOT NULL,
    contact_state VARCHAR(20) DEFAULT '' NOT NULL,
    contact_zip VARCHAR(10) DEFAULT '' NOT NULL,
    contact_phone VARCHAR(30) DEFAULT '' NOT NULL,
    contact_preference VARCHAR(8),
    claim_code VARCHAR(12) NOT NULL,
    managed_by_user_id VARCHAR(36),
    CONSTRAINT people_pkey PRIMARY KEY (id),
    CONSTRAINT people_claim_code_unique UNIQUE (claim_code),
    CONSTRAINT people_gender_check CHECK (gender IS NULL OR gender IN ('MALE', 'FEMALE')),
    CONSTRAINT fk_people_managed_by_user_id__id FOREIGN KEY (managed_by_user_id)
        REFERENCES users(id) ON UPDATE RESTRICT ON DELETE RESTRICT
);
CREATE INDEX people_managed_by_user_id ON people (managed_by_user_id);
CREATE INDEX people_lower_name ON people (lower(name));
CREATE INDEX people_lower_email ON people (lower(email));

ALTER TABLE users ADD COLUMN person_id VARCHAR(36);
ALTER TABLE users ADD CONSTRAINT users_person_id_unique UNIQUE (person_id);
ALTER TABLE users ADD CONSTRAINT fk_users_person_id__id FOREIGN KEY (person_id)
    REFERENCES people(id) ON UPDATE RESTRICT ON DELETE RESTRICT;

CREATE TABLE registrations (
    id VARCHAR(36) NOT NULL,
    congregation_id VARCHAR(36) NOT NULL,
    season_year INTEGER NOT NULL,
    status VARCHAR(16) NOT NULL,
    site_id VARCHAR(36), -- null = not chosen yet (single-site seasons auto-pin)
    submitted_at_epoch_ms BIGINT,
    paid_at_epoch_ms BIGINT,
    updated_at_epoch_ms BIGINT NOT NULL,
    CONSTRAINT registrations_pkey PRIMARY KEY (id),
    CONSTRAINT registrations_congregation_id_season_year_unique UNIQUE (congregation_id, season_year),
    CONSTRAINT registrations_status_check CHECK (status IN ('DRAFT', 'SUBMITTED')),
    CONSTRAINT fk_registrations_congregation_id__id FOREIGN KEY (congregation_id)
        REFERENCES congregations(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_registrations_season_year__year FOREIGN KEY (season_year)
        REFERENCES seasons(year) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_registrations_site_id__id FOREIGN KEY (site_id)
        REFERENCES season_sites(id) ON UPDATE RESTRICT ON DELETE RESTRICT
);
CREATE INDEX registrations_season_year ON registrations (season_year);
CREATE INDEX registrations_site_id ON registrations (site_id);

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

CREATE TABLE participants (
    id VARCHAR(36) NOT NULL,
    person_id VARCHAR(36) NOT NULL,
    registration_id VARCHAR(36) NOT NULL,
    -- Denormalized copy of the registration's season so the DB can enforce
    -- one-participation-per-person-per-season; the repository keeps it consistent.
    season_year INTEGER NOT NULL,
    is_contestant BOOLEAN DEFAULT FALSE NOT NULL,
    is_coach BOOLEAN DEFAULT FALSE NOT NULL,
    team_id VARCHAR(36),
    shirt_size VARCHAR(8), -- null = no included shirt (under-3 guests)
    positions TEXT DEFAULT '[]' NOT NULL,
    tribe_leader BOOLEAN DEFAULT FALSE NOT NULL,
    tester_id INTEGER, -- allocated from seasons.next_tester_id; never reused
    CONSTRAINT participants_pkey PRIMARY KEY (id),
    CONSTRAINT participants_person_id_season_year_unique UNIQUE (person_id, season_year),
    CONSTRAINT participants_season_year_tester_id_unique UNIQUE (season_year, tester_id),
    CONSTRAINT fk_participants_person_id__id FOREIGN KEY (person_id)
        REFERENCES people(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_participants_registration_id__id FOREIGN KEY (registration_id)
        REFERENCES registrations(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_participants_season_year__year FOREIGN KEY (season_year)
        REFERENCES seasons(year) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_participants_team_id__id FOREIGN KEY (team_id)
        REFERENCES teams(id) ON UPDATE RESTRICT ON DELETE SET NULL
);
CREATE INDEX participants_person_id ON participants (person_id);
CREATE INDEX participants_registration_id ON participants (registration_id);
CREATE INDEX participants_team_id ON participants (team_id);

CREATE TABLE scores (
    id VARCHAR(36) NOT NULL,
    participant_id VARCHAR(36) NOT NULL,
    round VARCHAR(20) NOT NULL,
    points INTEGER NOT NULL,
    entered_by_user_id VARCHAR(36) NOT NULL,
    entered_at_epoch_ms BIGINT NOT NULL,
    CONSTRAINT scores_pkey PRIMARY KEY (id),
    CONSTRAINT scores_participant_id_round_unique UNIQUE (participant_id, round),
    CONSTRAINT scores_round_check CHECK (round IN
        ('FIND_THE_VERSE', 'FACT_FINDER', 'IDENTIFICATION', 'QUOTES', 'EVENTS', 'POWER')),
    CONSTRAINT fk_scores_participant_id__id FOREIGN KEY (participant_id)
        REFERENCES participants(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_scores_entered_by_user_id__id FOREIGN KEY (entered_by_user_id)
        REFERENCES users(id) ON UPDATE RESTRICT ON DELETE RESTRICT
);
CREATE INDEX scores_entered_by_user_id ON scores (entered_by_user_id);

CREATE TABLE score_releases (
    season_year INTEGER NOT NULL,
    released_at_epoch_ms BIGINT NOT NULL,
    released_by_user_id VARCHAR(36) NOT NULL,
    CONSTRAINT score_releases_pkey PRIMARY KEY (season_year),
    CONSTRAINT fk_score_releases_season_year__year FOREIGN KEY (season_year)
        REFERENCES seasons(year) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_score_releases_released_by_user_id__id FOREIGN KEY (released_by_user_id)
        REFERENCES users(id) ON UPDATE RESTRICT ON DELETE RESTRICT
);

CREATE TABLE cabins (
    id VARCHAR(36) NOT NULL,
    season_year INTEGER NOT NULL,
    site_id VARCHAR(36) NOT NULL,
    name VARCHAR(120) NOT NULL,
    capacity INTEGER,
    CONSTRAINT cabins_pkey PRIMARY KEY (id),
    CONSTRAINT fk_cabins_season_year__year FOREIGN KEY (season_year)
        REFERENCES seasons(year) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_cabins_site_id__id FOREIGN KEY (site_id)
        REFERENCES season_sites(id) ON UPDATE RESTRICT ON DELETE RESTRICT
);
CREATE INDEX cabins_season_year ON cabins (season_year);
CREATE INDEX cabins_site_id ON cabins (site_id);

CREATE TABLE cabin_assignments (
    id VARCHAR(36) NOT NULL,
    cabin_id VARCHAR(36) NOT NULL,
    congregation_id VARCHAR(36),
    -- Null = the row deliberately spans genders (whole congregation, family rows).
    gender VARCHAR(6),
    label VARCHAR(200) DEFAULT '' NOT NULL,
    sort_order INTEGER DEFAULT 0 NOT NULL,
    CONSTRAINT cabin_assignments_pkey PRIMARY KEY (id),
    CONSTRAINT cabin_assignments_gender_check CHECK (gender IS NULL OR gender IN ('MALE', 'FEMALE')),
    CONSTRAINT fk_cabin_assignments_cabin_id__id FOREIGN KEY (cabin_id)
        REFERENCES cabins(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_cabin_assignments_congregation_id__id FOREIGN KEY (congregation_id)
        REFERENCES congregations(id) ON UPDATE RESTRICT ON DELETE RESTRICT
);
CREATE INDEX cabin_assignments_cabin_id ON cabin_assignments (cabin_id);
CREATE INDEX cabin_assignments_congregation_id ON cabin_assignments (congregation_id);

CREATE TABLE checkout_duties (
    id VARCHAR(36) NOT NULL,
    season_year INTEGER NOT NULL,
    congregation_id VARCHAR(36) NOT NULL,
    person_id VARCHAR(36) NOT NULL, -- was free-form adult_name
    CONSTRAINT checkout_duties_pkey PRIMARY KEY (id),
    CONSTRAINT checkout_duties_season_year_congregation_id_unique UNIQUE (season_year, congregation_id),
    CONSTRAINT fk_checkout_duties_season_year__year FOREIGN KEY (season_year)
        REFERENCES seasons(year) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_checkout_duties_congregation_id__id FOREIGN KEY (congregation_id)
        REFERENCES congregations(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_checkout_duties_person_id__id FOREIGN KEY (person_id)
        REFERENCES people(id) ON UPDATE RESTRICT ON DELETE RESTRICT
);
CREATE INDEX checkout_duties_person_id ON checkout_duties (person_id);

CREATE TABLE tribes (
    id VARCHAR(36) NOT NULL,
    season_year INTEGER NOT NULL,
    site_id VARCHAR(36) NOT NULL,
    name VARCHAR(120) NOT NULL,
    CONSTRAINT tribes_pkey PRIMARY KEY (id),
    CONSTRAINT fk_tribes_season_year__year FOREIGN KEY (season_year)
        REFERENCES seasons(year) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_tribes_site_id__id FOREIGN KEY (site_id)
        REFERENCES season_sites(id) ON UPDATE RESTRICT ON DELETE RESTRICT
);
CREATE INDEX tribes_season_year ON tribes (season_year);
CREATE INDEX tribes_site_id ON tribes (site_id);

CREATE TABLE tribe_leaders (
    id VARCHAR(36) NOT NULL,
    tribe_id VARCHAR(36) NOT NULL,
    participant_id VARCHAR(36) NOT NULL, -- was free-form name; leaders must be registered
    sort_order INTEGER DEFAULT 0 NOT NULL,
    CONSTRAINT tribe_leaders_pkey PRIMARY KEY (id),
    CONSTRAINT fk_tribe_leaders_tribe_id__id FOREIGN KEY (tribe_id)
        REFERENCES tribes(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_tribe_leaders_participant_id__id FOREIGN KEY (participant_id)
        REFERENCES participants(id) ON UPDATE RESTRICT ON DELETE RESTRICT
);
CREATE INDEX tribe_leaders_tribe_id ON tribe_leaders (tribe_id);
CREATE INDEX tribe_leaders_participant_id ON tribe_leaders (participant_id);

-- Surrogate id PKs for the previously PK-less association tables (old uniqueness kept).
ALTER TABLE role_grants ADD COLUMN id VARCHAR(36);
UPDATE role_grants SET id = gen_random_uuid()::text;
ALTER TABLE role_grants ALTER COLUMN id SET NOT NULL;
ALTER TABLE role_grants ADD CONSTRAINT role_grants_pkey PRIMARY KEY (id);
CREATE INDEX role_grants_user_id ON role_grants (user_id);

ALTER TABLE question_votes ADD COLUMN id VARCHAR(36);
UPDATE question_votes SET id = gen_random_uuid()::text;
ALTER TABLE question_votes ALTER COLUMN id SET NOT NULL;
ALTER TABLE question_votes ADD CONSTRAINT question_votes_pkey PRIMARY KEY (id);

-- Check constraint on the question workflow status (enum-ish column).
ALTER TABLE questions ADD CONSTRAINT questions_status_check
    CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED'));

-- ---------------------------------------------------------------------------
-- 2. Data migration
-- ---------------------------------------------------------------------------

DO $$
DECLARE
    v_row RECORD;
    v_person_id VARCHAR;
    v_participant_id VARCHAR;
    v_reg RECORD;
    v_site_id VARCHAR;
    v_count INTEGER;
BEGIN
    -- 2a. seasons: INT year; strip sites from the payload. Stub rows are added for any season
    -- year referenced by data (registrations/cabins/tribes/scores/testers) that never had a
    -- seasons row — the old varchar columns had no FK, the new INT ones do.
    INSERT INTO seasons (year, is_current, next_tester_id, payload)
    SELECT event_year::int, is_current, 1, (payload::jsonb - 'sites')::text
    FROM seasons_v1
    WHERE event_year ~ '^[0-9]+$';

    INSERT INTO seasons (year, is_current, next_tester_id, payload)
    SELECT DISTINCT y::int, false, 1,
        ('{"eventYear":"' || y || '","eventDateRange":"TBD","eventTheme":"TBD",' ||
         '"eventScripture":"TBD","bookCode":"ACT","chapterCount":28,' ||
         '"scholarshipAmount":"TBD","scholarshipDeadline":"TBD"}')
    FROM (
        SELECT season_year AS y FROM registrations_v1
        UNION SELECT season_year FROM cabins_v1
        UNION SELECT season_year FROM tribes_v1
        UNION SELECT season_year FROM score_releases_v1
        UNION SELECT season_year FROM tester_ids_v1
        UNION SELECT season_year FROM checkout_duties_v1
    ) refs
    WHERE y ~ '^[0-9]+$' AND y::int NOT IN (SELECT year FROM seasons);

    -- 2b. season_sites from the old payload JSON (ids preserved — they're already slug ids).
    INSERT INTO season_sites (id, season_year, name, address, sort_order)
    SELECT site->>'id', s.event_year::int, site->>'name', coalesce(site->>'address', ''),
           ord - 1
    FROM seasons_v1 s,
         jsonb_array_elements(coalesce(s.payload::jsonb->'sites', '[]'::jsonb))
             WITH ORDINALITY AS t(site, ord)
    WHERE s.event_year ~ '^[0-9]+$' AND site->>'id' IS NOT NULL;

    -- Seasons that have site-pinned data (cabins/tribes, or registrations with a site id that
    -- matches nothing) but no matching site row get a synthetic site so NOT NULL FKs can hold.
    FOR v_row IN
        SELECT DISTINCT r.season_year::int AS year
        FROM (
            SELECT season_year, site_id FROM cabins_v1
            UNION SELECT season_year, site_id FROM tribes_v1
        ) r
        WHERE r.season_year ~ '^[0-9]+$'
          AND NOT EXISTS (SELECT 1 FROM season_sites ss WHERE ss.season_year = r.season_year::int)
    LOOP
        INSERT INTO season_sites (id, season_year, name, address, sort_order)
        VALUES ('main-site-' || v_row.year, v_row.year, 'Main Site', '', 0);
        RAISE NOTICE 'V2: season % had site-pinned housing/tribes but no sites — created synthetic site', v_row.year;
    END LOOP;

    -- 2c. registrations: cast season, resolve site ids (an id that matches none of the season's
    -- sites reverts to null = "not chosen", the pre-V2 semantics; single-site seasons auto-pin).
    INSERT INTO registrations (id, congregation_id, season_year, status, site_id,
                               submitted_at_epoch_ms, paid_at_epoch_ms, updated_at_epoch_ms)
    SELECT r.id, r.congregation_id, r.season_year::int, r.status,
        coalesce(
            (SELECT ss.id FROM season_sites ss
             WHERE ss.id = r.site_id AND ss.season_year = r.season_year::int),
            (SELECT ss.id FROM season_sites ss
             WHERE ss.season_year = r.season_year::int
             GROUP BY ss.id HAVING (SELECT count(*) FROM season_sites x
                                    WHERE x.season_year = r.season_year::int) = 1)
        ),
        r.submitted_at_epoch_ms, r.paid_at_epoch_ms, r.updated_at_epoch_ms
    FROM registrations_v1 r
    WHERE r.season_year ~ '^[0-9]+$';

    INSERT INTO teams (id, registration_id, name, sort_order)
    SELECT t.id, t.registration_id, t.name, t.sort_order
    FROM teams_v1 t
    WHERE EXISTS (SELECT 1 FROM registrations r WHERE r.id = t.registration_id);

    -- 2d. people from contestants: global dedupe on (lower(name), birthdate), with seeded-youth
    -- (null birthdate + graduation year) and adults (null birthdate, no graduation year) kept
    -- apart. Two contestants may only merge if they never enroll in the same season (otherwise
    -- the merged person would double-enroll and violate UNIQUE (person_id, season_year)) — such
    -- collisions stay separate people for the phase-6 merge tool.
    CREATE TEMP TABLE contestant_person ON COMMIT DROP AS
        SELECT id AS contestant_id, NULL::varchar AS person_id,
               lower(trim(name)) AS k_name,
               coalesce(birthdate, CASE WHEN graduation_year IS NOT NULL THEN 'seeded' ELSE 'adult' END) AS k_bd
        FROM contestants_v1;

    CREATE TEMP TABLE contestant_seasons ON COMMIT DROP AS
        SELECT tm.contestant_id, r.season_year::int AS season_year
        FROM team_members_v1 tm JOIN registrations_v1 r ON r.id = tm.registration_id
        WHERE tm.contestant_id IS NOT NULL AND r.season_year ~ '^[0-9]+$'
        UNION
        SELECT i.contestant_id, r.season_year::int
        FROM individuals_v1 i JOIN registrations_v1 r ON r.id = i.registration_id
        WHERE i.contestant_id IS NOT NULL AND r.season_year ~ '^[0-9]+$';

    FOR v_row IN
        SELECT cp.contestant_id, cp.k_name, cp.k_bd FROM contestant_person cp
        ORDER BY cp.k_name, cp.k_bd, cp.contestant_id
    LOOP
        -- Reuse an earlier person in the same identity group whose seasons don't collide.
        SELECT cp2.person_id INTO v_person_id
        FROM (SELECT DISTINCT person_id FROM contestant_person cp2
              WHERE cp2.k_name = v_row.k_name AND cp2.k_bd = v_row.k_bd
                AND cp2.person_id IS NOT NULL) cp2
        WHERE NOT EXISTS (
            SELECT 1 FROM contestant_seasons s1
            JOIN contestant_person cpx ON cpx.contestant_id = s1.contestant_id
            JOIN contestant_seasons s2 ON s2.contestant_id = v_row.contestant_id
                AND s2.season_year = s1.season_year
            WHERE cpx.person_id = cp2.person_id
        )
        LIMIT 1;
        UPDATE contestant_person
        SET person_id = coalesce(v_person_id, contestant_id)
        WHERE contestant_id = v_row.contestant_id;
        v_person_id := NULL;
    END LOOP;

    -- One people row per person bucket, merging attributes across the bucket's contestants.
    -- The claim code comes from any of the person's roster entries (they stay valid codes);
    -- codes for entry-less people are generated below.
    FOR v_row IN
        SELECT cp.person_id,
               (array_agg(c.name ORDER BY c.id))[1] AS name,
               max(c.birthdate) AS birthdate,
               max(c.gender) AS gender,
               max(c.graduation_year) AS graduation_year,
               min(c.first_season_year) FILTER (WHERE c.first_season_year ~ '^[0-9]+$') AS first_season_year
        FROM contestant_person cp JOIN contestants_v1 c ON c.id = cp.contestant_id
        GROUP BY cp.person_id
    LOOP
        INSERT INTO people (id, name, birthdate, is_adult, gender, graduation_year,
                            first_season_year, claim_code, managed_by_user_id)
        VALUES (
            v_row.person_id, v_row.name, v_row.birthdate::date,
            v_row.birthdate IS NULL AND v_row.graduation_year IS NULL,
            v_row.gender, v_row.graduation_year, v_row.first_season_year::int,
            coalesce(
                (SELECT tm.claim_code FROM team_members_v1 tm
                 JOIN contestant_person cp ON cp.contestant_id = tm.contestant_id
                 WHERE cp.person_id = v_row.person_id ORDER BY tm.claim_code LIMIT 1),
                (SELECT i.claim_code FROM individuals_v1 i
                 JOIN contestant_person cp ON cp.contestant_id = i.contestant_id
                 WHERE cp.person_id = v_row.person_id ORDER BY i.claim_code LIMIT 1),
                upper(substr(md5(random()::text || v_row.person_id), 1, 8))
            ),
            (SELECT tm.owner_user_id FROM team_members_v1 tm
             JOIN contestant_person cp ON cp.contestant_id = tm.contestant_id
             WHERE cp.person_id = v_row.person_id AND tm.owner_user_id IS NOT NULL LIMIT 1)
        );
    END LOOP;
    UPDATE people p SET managed_by_user_id = (
        SELECT i.owner_user_id FROM individuals_v1 i
        JOIN contestant_person cp ON cp.contestant_id = i.contestant_id
        WHERE cp.person_id = p.id AND i.owner_user_id IS NOT NULL LIMIT 1
    ) WHERE p.managed_by_user_id IS NULL;

    -- 2e. participants from team_members + individual_contestants (ids preserved).
    INSERT INTO participants (id, person_id, registration_id, season_year, is_contestant,
                              team_id, shirt_size)
    SELECT tm.id, cp.person_id, tm.registration_id, r.season_year, true,
           CASE WHEN EXISTS (SELECT 1 FROM teams t WHERE t.id = tm.team_id) THEN tm.team_id END,
           tm.shirt_size
    FROM team_members_v1 tm
    JOIN contestant_person cp ON cp.contestant_id = tm.contestant_id
    JOIN registrations r ON r.id = tm.registration_id;

    SELECT count(*) INTO v_count FROM team_members_v1 tm
    WHERE tm.contestant_id IS NULL
       OR NOT EXISTS (SELECT 1 FROM registrations r WHERE r.id = tm.registration_id);
    IF v_count > 0 THEN
        RAISE NOTICE 'V2: dropped % team_members rows with no contestant link or registration', v_count;
    END IF;

    FOR v_row IN
        SELECT i.*, cp.person_id AS mapped_person, r.season_year AS season_int
        FROM individuals_v1 i
        JOIN contestant_person cp ON cp.contestant_id = i.contestant_id
        JOIN registrations r ON r.id = i.registration_id
    LOOP
        IF EXISTS (SELECT 1 FROM participants p
                   WHERE p.person_id = v_row.mapped_person AND p.season_year = v_row.season_int) THEN
            UPDATE participants SET tribe_leader = tribe_leader OR v_row.tribe_leader
            WHERE person_id = v_row.mapped_person AND season_year = v_row.season_int;
            RAISE NOTICE 'V2: individual entry % merged into an existing same-season participation', v_row.id;
        ELSE
            INSERT INTO participants (id, person_id, registration_id, season_year, is_contestant,
                                      shirt_size, tribe_leader)
            VALUES (v_row.id, v_row.mapped_person, v_row.registration_id, v_row.season_int, true,
                    v_row.shirt_size, v_row.tribe_leader);
        END IF;
    END LOOP;

    -- 2f. guests become people + non-contestant participants. A guest matches an existing person
    -- by (lower(name), birthdate) only when that person has no participation in the guest's
    -- season (else they stay a separate person — phase-6 merge tool territory).
    FOR v_row IN
        SELECT g.*, r.season_year AS season_int
        FROM guests_v1 g JOIN registrations r ON r.id = g.registration_id
        ORDER BY g.name, g.id
    LOOP
        SELECT p.id INTO v_person_id
        FROM people p
        WHERE lower(p.name) = lower(trim(v_row.name))
          AND (p.birthdate IS NOT DISTINCT FROM v_row.birthdate::date)
          AND NOT EXISTS (SELECT 1 FROM participants pa
                          WHERE pa.person_id = p.id AND pa.season_year = v_row.season_int)
        LIMIT 1;
        IF v_person_id IS NULL THEN
            v_person_id := gen_random_uuid()::text;
            INSERT INTO people (id, name, birthdate, is_adult, gender, email,
                                contact_address, contact_city, contact_state, contact_zip,
                                contact_phone, contact_preference, claim_code)
            VALUES (v_person_id, trim(v_row.name), v_row.birthdate::date,
                    v_row.birthdate IS NULL,
                    CASE WHEN v_row.gender IN ('MALE', 'FEMALE') THEN v_row.gender END,
                    nullif(v_row.contact_email, ''),
                    v_row.contact_address, v_row.contact_city, v_row.contact_state,
                    v_row.contact_zip, v_row.contact_phone, v_row.contact_preference,
                    upper(substr(md5(random()::text || v_row.id), 1, 8)));
        ELSE
            -- Fill identity/contact blanks from the guest row; curated values win.
            UPDATE people SET
                email = coalesce(email, nullif(v_row.contact_email, '')),
                contact_address = CASE WHEN contact_address = '' THEN v_row.contact_address ELSE contact_address END,
                contact_city = CASE WHEN contact_city = '' THEN v_row.contact_city ELSE contact_city END,
                contact_state = CASE WHEN contact_state = '' THEN v_row.contact_state ELSE contact_state END,
                contact_zip = CASE WHEN contact_zip = '' THEN v_row.contact_zip ELSE contact_zip END,
                contact_phone = CASE WHEN contact_phone = '' THEN v_row.contact_phone ELSE contact_phone END,
                contact_preference = coalesce(contact_preference, v_row.contact_preference)
            WHERE id = v_person_id;
        END IF;
        INSERT INTO participants (id, person_id, registration_id, season_year, is_contestant,
                                  shirt_size, positions, tribe_leader)
        VALUES (v_row.id, v_person_id, v_row.registration_id, v_row.season_int, false,
                v_row.shirt_size, v_row.positions, v_row.tribe_leader);
        v_person_id := NULL;
    END LOOP;

    -- 2g. users → people: match by (lower(display_name), birthdate) among unlinked people, else
    -- create; move birthdate/is_adult/contact off users; owners of their own person self-claim.
    FOR v_row IN SELECT * FROM users ORDER BY email
    LOOP
        SELECT p.id INTO v_person_id
        FROM people p
        WHERE lower(p.name) = lower(trim(v_row.display_name))
          AND (p.birthdate IS NOT DISTINCT FROM v_row.birthdate::date)
          AND NOT EXISTS (SELECT 1 FROM users u2 WHERE u2.person_id = p.id)
        LIMIT 1;
        IF v_person_id IS NULL THEN
            v_person_id := gen_random_uuid()::text;
            INSERT INTO people (id, name, birthdate, is_adult, email,
                                contact_address, contact_city, contact_state, contact_zip,
                                contact_phone, contact_preference, claim_code)
            VALUES (v_person_id, trim(v_row.display_name), v_row.birthdate::date, v_row.is_adult,
                    v_row.email, v_row.contact_address, v_row.contact_city, v_row.contact_state,
                    v_row.contact_zip, v_row.contact_phone, v_row.contact_preference,
                    upper(substr(md5(random()::text || v_row.id), 1, 8)));
        ELSE
            UPDATE people SET
                email = coalesce(email, v_row.email),
                is_adult = is_adult OR (v_row.is_adult AND birthdate IS NULL AND graduation_year IS NULL),
                contact_address = CASE WHEN contact_address = '' THEN v_row.contact_address ELSE contact_address END,
                contact_city = CASE WHEN contact_city = '' THEN v_row.contact_city ELSE contact_city END,
                contact_state = CASE WHEN contact_state = '' THEN v_row.contact_state ELSE contact_state END,
                contact_zip = CASE WHEN contact_zip = '' THEN v_row.contact_zip ELSE contact_zip END,
                contact_phone = CASE WHEN contact_phone = '' THEN v_row.contact_phone ELSE contact_phone END,
                contact_preference = coalesce(contact_preference, v_row.contact_preference)
            WHERE id = v_person_id;
        END IF;
        UPDATE users SET person_id = v_person_id WHERE id = v_row.id;
        -- When this user manages a person who IS them (matched above), self-claim completes.
        UPDATE people SET managed_by_user_id = v_row.id
        WHERE id = v_person_id AND managed_by_user_id IS NULL;
        v_person_id := NULL;
    END LOOP;

    -- 2h. pending_coach_grants → people (email-matched or placeholder) + an is_coach
    -- participation under the congregation's earliest (seed-season) registration.
    FOR v_row IN SELECT * FROM pending_coach_v1 ORDER BY email
    LOOP
        SELECT r.id, r.season_year INTO v_reg
        FROM registrations r
        WHERE r.congregation_id = v_row.congregation_id
        ORDER BY r.season_year LIMIT 1;
        IF v_reg.id IS NULL THEN
            RAISE NOTICE 'V2: pending coach grant % has no registration for its congregation — dropped', v_row.email;
            CONTINUE;
        END IF;
        SELECT p.id INTO v_person_id
        FROM people p
        WHERE lower(p.email) = lower(v_row.email)
          AND NOT EXISTS (SELECT 1 FROM participants pa
                          WHERE pa.person_id = p.id AND pa.season_year = v_reg.season_year
                            AND pa.registration_id <> v_reg.id)
        LIMIT 1;
        IF v_person_id IS NULL THEN
            v_person_id := gen_random_uuid()::text;
            INSERT INTO people (id, name, is_adult, email, claim_code)
            VALUES (v_person_id, split_part(v_row.email, '@', 1), true, lower(v_row.email),
                    upper(substr(md5(random()::text || v_row.email || v_row.congregation_id), 1, 8)));
        END IF;
        IF EXISTS (SELECT 1 FROM participants pa
                   WHERE pa.person_id = v_person_id AND pa.season_year = v_reg.season_year) THEN
            UPDATE participants SET is_coach = true
            WHERE person_id = v_person_id AND season_year = v_reg.season_year;
        ELSE
            INSERT INTO participants (id, person_id, registration_id, season_year,
                                      is_contestant, is_coach)
            VALUES (gen_random_uuid()::text, v_person_id, v_reg.id, v_reg.season_year, false, true);
        END IF;
        v_person_id := NULL;
    END LOOP;

    -- 2i. tester ids onto participants; each season's counter = max assigned + 1.
    UPDATE participants p SET tester_id = t.tester_id
    FROM tester_ids_v1 t
    WHERE t.roster_entry_id = p.id
      AND t.season_year ~ '^[0-9]+$' AND t.season_year::int = p.season_year;

    SELECT count(*) INTO v_count FROM tester_ids_v1 t
    WHERE NOT EXISTS (SELECT 1 FROM participants p
                      WHERE p.id = t.roster_entry_id
                        AND t.season_year ~ '^[0-9]+$' AND p.season_year = t.season_year::int);
    IF v_count > 0 THEN
        RAISE NOTICE 'V2: dropped % tester ids whose roster entry did not migrate', v_count;
    END IF;

    UPDATE seasons s SET next_tester_id = coalesce(
        (SELECT max(p.tester_id) + 1 FROM participants p WHERE p.season_year = s.year), 1);

    -- 2j. scores: surrogate id; roster_entry_id becomes participant_id (same UUIDs). Orphans
    -- (scores whose roster entry no longer exists) are dropped with a notice.
    INSERT INTO scores (id, participant_id, round, points, entered_by_user_id, entered_at_epoch_ms)
    SELECT gen_random_uuid()::text, s.roster_entry_id, s.round, s.points,
           s.entered_by_user_id, s.entered_at_epoch_ms
    FROM scores_v1 s
    WHERE EXISTS (SELECT 1 FROM participants p WHERE p.id = s.roster_entry_id)
      AND s.round IN ('FIND_THE_VERSE', 'FACT_FINDER', 'IDENTIFICATION', 'QUOTES', 'EVENTS', 'POWER');

    SELECT count(*) INTO v_count FROM scores_v1 s
    WHERE NOT EXISTS (SELECT 1 FROM participants p WHERE p.id = s.roster_entry_id);
    IF v_count > 0 THEN
        RAISE NOTICE 'V2: dropped % orphan score cells (deleted roster entries)', v_count;
    END IF;

    INSERT INTO score_releases (season_year, released_at_epoch_ms, released_by_user_id)
    SELECT season_year::int, released_at_epoch_ms, released_by_user_id
    FROM score_releases_v1
    WHERE season_year ~ '^[0-9]+$';

    -- 2k. cabins/tribes: resolve each row to a real site (old exact id, else the season's lone
    -- site, else the season's first site with a notice).
    FOR v_row IN SELECT * FROM cabins_v1 WHERE season_year ~ '^[0-9]+$'
    LOOP
        SELECT ss.id INTO v_site_id FROM season_sites ss
        WHERE ss.id = v_row.site_id AND ss.season_year = v_row.season_year::int;
        IF v_site_id IS NULL THEN
            SELECT ss.id INTO v_site_id FROM season_sites ss
            WHERE ss.season_year = v_row.season_year::int
            ORDER BY ss.sort_order LIMIT 1;
            IF (SELECT count(*) FROM season_sites ss WHERE ss.season_year = v_row.season_year::int) > 1 THEN
                RAISE NOTICE 'V2: cabin "%" had no resolvable site — pinned to the season''s first site', v_row.name;
            END IF;
        END IF;
        INSERT INTO cabins (id, season_year, site_id, name, capacity)
        VALUES (v_row.id, v_row.season_year::int, v_site_id, v_row.name, v_row.capacity);
        v_site_id := NULL;
    END LOOP;

    INSERT INTO cabin_assignments (id, cabin_id, congregation_id, gender, label, sort_order)
    SELECT a.id, a.cabin_id, a.congregation_id,
           CASE WHEN a.gender IN ('MALE', 'FEMALE') THEN a.gender END,
           a.label, a.sort_order
    FROM cabin_assignments_v1 a
    WHERE EXISTS (SELECT 1 FROM cabins c WHERE c.id = a.cabin_id);

    FOR v_row IN SELECT * FROM tribes_v1 WHERE season_year ~ '^[0-9]+$'
    LOOP
        SELECT ss.id INTO v_site_id FROM season_sites ss
        WHERE ss.id = v_row.site_id AND ss.season_year = v_row.season_year::int;
        IF v_site_id IS NULL THEN
            SELECT ss.id INTO v_site_id FROM season_sites ss
            WHERE ss.season_year = v_row.season_year::int
            ORDER BY ss.sort_order LIMIT 1;
            IF (SELECT count(*) FROM season_sites ss WHERE ss.season_year = v_row.season_year::int) > 1 THEN
                RAISE NOTICE 'V2: tribe "%" had no resolvable site — pinned to the season''s first site', v_row.name;
            END IF;
        END IF;
        INSERT INTO tribes (id, season_year, site_id, name)
        VALUES (v_row.id, v_row.season_year::int, v_site_id, v_row.name);
        v_site_id := NULL;
    END LOOP;

    -- 2l. checkout duties: adult_name resolves to a person among the congregation's people
    -- (anyone with a participation there, any season); unmatched names create a minimal person.
    FOR v_row IN SELECT * FROM checkout_duties_v1 WHERE season_year ~ '^[0-9]+$'
    LOOP
        SELECT p.id INTO v_person_id
        FROM people p
        WHERE lower(p.name) = lower(trim(v_row.adult_name))
          AND EXISTS (SELECT 1 FROM participants pa
                      JOIN registrations r ON r.id = pa.registration_id
                      WHERE pa.person_id = p.id AND r.congregation_id = v_row.congregation_id)
        LIMIT 1;
        IF v_person_id IS NULL THEN
            v_person_id := gen_random_uuid()::text;
            INSERT INTO people (id, name, is_adult, claim_code)
            VALUES (v_person_id, trim(v_row.adult_name), true,
                    upper(substr(md5(random()::text || v_row.congregation_id || v_row.adult_name), 1, 8)));
        END IF;
        INSERT INTO checkout_duties (id, season_year, congregation_id, person_id)
        VALUES (gen_random_uuid()::text, v_row.season_year::int, v_row.congregation_id, v_person_id);
        v_person_id := NULL;
    END LOOP;

    -- 2m. tribe leaders: free-form names resolve to that season's participants; unmatched rows
    -- are dropped (leaders must be registered attendees now).
    FOR v_row IN
        SELECT tl.*, t.season_year AS tribe_season FROM tribe_leaders_v1 tl
        JOIN tribes t ON t.id = tl.tribe_id
    LOOP
        SELECT pa.id INTO v_participant_id
        FROM participants pa JOIN people p ON p.id = pa.person_id
        WHERE pa.season_year = v_row.tribe_season AND lower(p.name) = lower(trim(v_row.name))
        LIMIT 1;
        IF v_participant_id IS NULL THEN
            RAISE NOTICE 'V2: tribe leader "%" is not a registered attendee — dropped', v_row.name;
        ELSE
            INSERT INTO tribe_leaders (id, tribe_id, participant_id, sort_order)
            VALUES (v_row.id, v_row.tribe_id, v_participant_id, v_row.sort_order);
        END IF;
        v_participant_id := NULL;
    END LOOP;
END $$;

-- ---------------------------------------------------------------------------
-- 3. users becomes auth-only: identity/contact now live on the linked person.
-- ---------------------------------------------------------------------------

ALTER TABLE users
    DROP COLUMN birthdate,
    DROP COLUMN is_adult,
    DROP COLUMN contact_address,
    DROP COLUMN contact_city,
    DROP COLUMN contact_state,
    DROP COLUMN contact_zip,
    DROP COLUMN contact_phone,
    DROP COLUMN contact_preference;
