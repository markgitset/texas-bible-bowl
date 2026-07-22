# Schema redesign — people/participants restructure

Replaces the organically-grown registration schema with a person-centric model, and
replaces every by-convention reference (season-year strings, FK-less `roster_entry_id`
UUIDs, site ids into the season JSON) with a real foreign key. Timing: registration and
grading are still dark in prod (feature toggles off), so the data at risk is the seeded
2026 workbook plus admin test rows — this is the cheapest moment this change will ever be.

## Locked decisions (Mark, 2026-07-21)

1. **One `people` table for all humans** — contestants, guests, coaches, volunteers.
   Identity facts (name, gender, birthdate, contact) appear ONLY there. People are
   global, not congregation-scoped (fixes the person-loses-history-on-congregation-switch
   bug in today's `contestants`).
2. **One `participants` table for per-season facts** — one row per (person, season).
   Replaces `team_members`, `individual_contestants`, and `registration_guests`.
3. **Shirt size lives on `participants`** (per-season order snapshot — kids grow;
   prefill from the person's previous season). Accepted over the people-only placement.
4. **Experience is determined by `people.first_season_year`** (kept as an authoritative
   column for performance — NOT derived from min participation season). Not an FK: it may
   predate any `seasons` row (pre-app history).
5. **`seasons.year` is an `INT` PK** (easy `<`/`>=` comparisons), and every table that
   references a season does so with a real FK constraint.
6. **Every other table gets a typical single-column id PK** — we keep the existing
   varchar(36) UUID convention. Exceptions: the three cache tables keep their natural
   composite PKs (content-addressed; a surrogate adds nothing) and `score_releases`
   keeps `season_year` as its PK (it IS one-row-per-season).
7. **`cabin_assignments.gender` is NOT NULL, `MALE` / `FEMALE` only** — no `ALL`
   value and no null. Every assignment row is a single-gender group; "whole
   congregation" is expressed as two rows (one per gender), and ad-hoc label rows
   pick a gender too.

## Target schema

Unchanged: `questions`, `question_votes`, `role_grants` (all three gain a surrogate `id`
PK with the old PK/uniqueness kept as unique constraints), `congregations`,
`esv_chapters`, `text_annotations`, `generated_pdfs`.

Dropped: `contestants`, `team_members`, `individual_contestants`, `registration_guests`,
`pending_coach_grants`, `tester_ids` (all absorbed by `people` + `participants`).

New/changed tables (types are Postgres; all ids varchar(36) UUIDs unless noted):

```
seasons
  year INT PK                        -- was event_year varchar(8)
  is_current BOOL
  next_tester_id INT NOT NULL DEFAULT 1   -- season-wide append-only tester-id counter
                                          -- (never reused; NO per-site blocks — PR #63)
  payload TEXT                       -- SeasonDto JSON minus sites (now rows)

season_sites                         -- NEW: promoted out of the season JSON
  id PK                              -- keeps the existing EventSiteDto slug ids
  season_year INT FK -> seasons NOT NULL
  name varchar(120)
  -- single-site seasons have exactly one row (no more "null site" special case
  -- for cabins/tribes; registrations auto-pin to the lone site)

people                               -- NEW: every human, exactly once
  id PK
  name varchar(120) NOT NULL
  birthdate DATE                     -- typed DATE now; null = birthdate-less adult
  is_adult BOOL NOT NULL DEFAULT FALSE   -- explicit for birthdate-less rows
  gender varchar(6)                  -- MALE/FEMALE; check constraint
  graduation_year INT                -- seeded-youth provenance (birthdate wins)
  first_season_year INT              -- authoritative experience anchor; NOT an FK
  email varchar(255)                 -- contact + signup matching; NOT unique
                                     -- (one parent's email appears on several kids)
  contact_address/city/state/zip/phone, contact_preference  -- as today on users/guests
  claim_code varchar(12) NOT NULL UNIQUE
  managed_by_user_id FK -> users     -- "manages": parent, or self (see users.person_id)

users                                -- auth only now
  id PK
  email varchar(255) NOT NULL UNIQUE -- login
  password_hash varchar(512) NOT NULL
  display_name varchar(120)          -- used until a person is linked
  person_id FK -> people UNIQUE      -- "is": null until claimed/linked
  -- birthdate, is_adult, contact_* move to people

registrations
  id PK
  congregation_id FK NOT NULL        -- UNIQUE (congregation_id, season_year)
  season_year INT FK -> seasons NOT NULL
  site_id FK -> season_sites         -- null = not chosen yet (never "no sites")
  status, submitted/paid/updated timestamps as today

teams
  id PK
  registration_id FK NOT NULL        -- UNIQUE (registration_id, name)
  name, sort_order as today

participants                         -- NEW: one row per (person, season)
  id PK
  person_id FK -> people NOT NULL
  registration_id FK -> registrations NOT NULL
  season_year INT FK -> seasons NOT NULL   -- denormalized so the DB can enforce
                                           -- UNIQUE (person_id, season_year); repo
                                           -- keeps it consistent with registration
  is_contestant BOOL NOT NULL DEFAULT FALSE
  is_coach BOOL NOT NULL DEFAULT FALSE     -- replaces pending_coach_grants (below)
  team_id FK -> teams ON DELETE SET NULL   -- null = unassigned / not a youth teamer
  shirt_size varchar(8)              -- per-season snapshot; null = no shirt (under-3)
  positions TEXT NOT NULL DEFAULT '[]'     -- volunteer positions JSON; adults
  tribe_leader BOOL NOT NULL DEFAULT FALSE
  tester_id INT                      -- null until assigned; allocated from
                                     -- seasons.next_tester_id (season-wide sequence);
                                     -- UNIQUE (season_year, tester_id) — DB-enforced
                                     -- now, unlike the old cross-table convention
  -- facets, not a role enum: an adult contestant can also volunteer. Youth/adult,
  -- division, and fee tier all stay DERIVED from people.birthdate/is_adult.

scores
  id PK                              -- was PK (roster_entry_id, round)
  participant_id FK -> participants NOT NULL ON DELETE RESTRICT
  round varchar(20)                  -- UNIQUE (participant_id, round)
  points, entered_by_user_id FK, entered_at as today

score_releases
  season_year INT PK FK -> seasons   -- otherwise as today; released_by FK kept

cabins
  id PK, season_year INT FK NOT NULL, site_id FK -> season_sites NOT NULL,
  name, capacity

cabin_assignments
  id PK, cabin_id FK NOT NULL,
  congregation_id FK                 -- still nullable: null = ad-hoc label-only row
  gender varchar(6) NOT NULL         -- MALE/FEMALE only (check); was nullable —
                                     -- a whole congregation = one row per gender
  label, sort_order as today

checkout_duties
  id PK                              -- was PK (season_year, congregation_id)
  season_year INT FK NOT NULL        -- UNIQUE (season_year, congregation_id)
  congregation_id FK NOT NULL
  person_id FK -> people NOT NULL    -- was free-form adult_name

tribes
  id PK, season_year INT FK NOT NULL, site_id FK -> season_sites NOT NULL, name

tribe_leaders
  id PK, tribe_id FK NOT NULL,
  participant_id FK -> participants NOT NULL   -- was free-form name; leaders must
  sort_order                                   -- be registered attendees
```

FK indexes on every FK column. Check constraints on enum-ish columns
(`registrations.status`, `questions.status`, `scores.round`, genders).

### How pending_coach_grants dies

Seeded coaches become `people` rows (with email) plus a 2026 `participants` row with
`is_coach = true`. At signup, match `people.email` (case-insensitive) → link the person
→ if any participation has `is_coach`, grant the congregation-scoped COACH role for that
participation's congregation. Same behavior, no side table, and coach history becomes
queryable per season.

### Claim model

- `users.person_id` — this account IS this person (set when a user claims their own code,
  or at email-match on signup).
- `people.managed_by_user_id` — this account MANAGES this person (parent claiming a
  child; self-claims set both). Single column for now; becomes a link table only if
  multiple guardians per child ever matters.

## Migration mechanics

### Adopt Flyway (replaces the idempotent-ALTER pile)

- Add `flyway-core` + `flyway-database-postgresql` to `:server`; run `Flyway.migrate()`
  in `DatabaseFactory.connect` before Exposed sees the DB.
- `V1__baseline.sql` = the current as-deployed schema. Existing prod DB is stamped with
  `baselineOnMigrate` (V1 recorded, not executed); a fresh DB executes it.
- `V2__people_participants.sql` = the restructure below (DDL + data migration in one
  transactional script).
- Delete `SchemaUtils.create(...)` and the entire `exec("ALTER TABLE ...")` block from
  `DatabaseFactory` — Flyway owns the schema from here on. Exposed table objects are
  updated to mirror V2 and become description-only.

### V2 data migration (old → new)

Key trick: **new rows reuse old UUIDs wherever possible** so references migrate by
column rename, not by lookup table. `team_members` and `individual_contestants` ids are
disjoint, so `participants.id` = the old roster-row id, and `scores.roster_entry_id`
just becomes `scores.participant_id` with an FK added.

1. `seasons`: rebuild with INT `year` (cast of old `event_year`); extract sites from the
   payload JSON into `season_sites` (ids preserved — they're already slug ids), strip
   sites from the stored payload. Backfill `registrations.site_id` for single-site
   seasons.
2. `people` from `contestants`: global dedupe on `(lower(name), birthdate)` — the
   congregation scope is dropped; collisions across congregations merge into one person.
3. `people` from `registration_guests`: name/birthdate/gender/contact move to the person;
   dedupe against step 2.
4. `people` from `users`: match by `(lower(name), birthdate)` against existing people,
   else create; set `users.person_id`; move birthdate/is_adult/contact_* off `users`.
5. `participants` from `team_members` (`is_contestant=true`, keep team_id/shirt/claim),
   `individual_contestants` (`is_contestant=true`, team null, tribe_leader), and
   `registration_guests` (`is_contestant=false`, positions, tribe_leader, shirt).
   `owner_user_id` → `people.managed_by_user_id`; when the owner's own person row IS
   this person (name+birthdate match), also set that user's `person_id` (self-claim).
6. `tester_ids` → `participants.tester_id` (join on roster id); set each season's
   `next_tester_id` = max assigned + 1.
7. `scores`: rename `roster_entry_id` → `participant_id`, add surrogate id + FK.
8. `checkout_duties.adult_name` → `person_id`: match by name among the congregation's
   people; unmatched names create a minimal person (data is seed/test only).
9. `tribe_leaders.name` → `participant_id`: match by name among that season's
   participants; unmatched rows are dropped with a migration warning (leaders must be
   registered; current data is test-only).
10. `pending_coach_grants` → `people` (email, placeholder name from the email local
    part) + a 2026 `participants` row with `is_coach=true` under that congregation's
    2026 registration. The seed-import re-run (below) reconciles real names by email.
11. `cabin_assignments.gender`: split each null-gender row (whole-congregation or
    ad-hoc) into two rows, one `MALE` and one `FEMALE`, preserving label and sort
    order; then `SET NOT NULL` + check constraint. The housing UI's
    "whole congregation" action becomes a shortcut that adds both rows.
12. Drop the six absorbed tables.

### Seed importer

`tools/seed/convert_registration_xlsx.py` output format is unchanged; the server-side
`POST /admin/seed` ingester is rewritten against people/participants and stays
idempotent (match people by name+birthdate, coaches by email). Re-run it against prod
after deploy to reconcile placeholder coach names and enrich anything the migration
carried minimally.

**Known gap to fix in the rewrite: volunteers from the 2026 workbook were not imported
by the current seeding process.** The rewrite must bring them in as `people` +
non-contestant `participants` rows (with their positions), which the new model makes
natural — check whether the converter script even extracts them from the workbook, and
extend it if not.

## Phase plan (each phase = one PR, tests green throughout)

| # | PR | Contents |
|---|----|----------|
| 1 | Flyway adoption | flyway deps, `V1__baseline.sql`, baseline-on-migrate, delete the ALTER pile. No schema change. |
| 2 | Restructure, server-internal | `V2__people_participants.sql`, Exposed table defs, Postgres + in-memory repos rewritten. **Wire DTOs unchanged** — repos adapt participants back to the existing `TeamMemberDto`/`IndividualDto`/`GuestDto` shapes so `:app` and `:web` are untouched. `SeasonDto.eventYear` stays a wire string (DB int ↔ string at the edge). |
| 3 | Seed importer rewrite | new-schema ingester + re-run against local stack; fix the missing-volunteers import gap; docs update. |
| 4 | API evolution — registration | person-centric DTOs (person + participation), claim-a-person flow replacing claim-a-roster-row, `:web` + `:app` registration screens. |
| 5 | API evolution — event ops | scores/testers/housing/tribes endpoints on participant ids; `SeasonDto.eventYear: Int` cleanup (drops the `toIntOrNull()` scattering in shared-api). |
| 6 | Cleanup | remove phase-2 adapter mappings; registrar "merge people" tool (new backlog item — global person matching makes duplicates likelier and FK-everywhere makes merging trivial). |

Phases 4–6 can trail the restructure; 1–3 should land together in one deploy window.

## Deploy & rollback

- Before deploying phase 2: take a manual dump of the prod DB (`pg_dump` via fly proxy).
  V2 runs automatically at boot inside one transaction — on failure the app doesn't come
  up and the old image can be re-deployed against the untouched schema.
- After deploy: re-run the seed import (phase 3), spot-check `/admin/registrations` and
  standings against the pre-migration dump.
- The ESV/annotation/PDF caches are untouched; worst case they're regenerable.

## Risks & notes

- **Blast radius**: phase 2 touches every repository in `:server`; the DTO-adapter trick
  contains it there. Phases 4–5 are web-parity-scale client work.
- **Person dedupe**: global name+birthdate matching can merge two genuinely different
  kids (same name, same birthday, different congregations). Rare; the merge/split tool
  in phase 6 is the mitigation. Registration UX should confirm "is this returning
  person X?" rather than silently merging.
- **Tester ids**: the season-wide `UNIQUE (season_year, tester_id)` constraint is new —
  the old schema could only enforce this inside `tester_ids`. The `seasons.next_tester_id`
  counter (not `max()+1`) preserves the never-reuse-after-delete rule.
- **`participants.season_year` denormalization** exists solely so the DB can enforce
  one-participation-per-person-per-season; repos must keep it equal to the
  registration's season (single write path makes this easy).
- In-memory repos (no-`DATABASE_URL` dev mode) must mirror the new model in phase 2 so
  the dev-admin bootstrap and web preview flow keep working.
