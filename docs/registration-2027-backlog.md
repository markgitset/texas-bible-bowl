# Registration backlog — corrections & features from the 2026 event workbook

*Working backlog, approved by Mark 2026-07-20. Source of truth for what to build in the
registration area next; items are refined and built **one at a time, in the order below**
(the spreadsheet import is deliberately last, per Mark). Check items off as they land.*

**Source:** the 2026 event-year registration workbook (`Registration.xlsx`, kept on Mark's
machine at `~/Downloads/Registration.xlsx` — it contains attendee PII including minors'
names, so it stays out of git). The workbook's 29 tabs ran the whole 2026 event: 223
attendees across two sites (Bandina: 203; White River Youth Camp: 20), of whom ~155 were
testers; per-congregation Google-Sheet intake; fee, shirt, count, nametag, ZipGrade,
housing, tribe, and check-out tabs downstream. Items below either **correct** the app where
the workbook contradicts its current model, or **add** what the workbook did that the app
can't yet. Labels C1–C5/F1–F13 match the analysis conversation (2026-07-20); the list is in
build order.

## Ordering rationale

Corrections first (they change the model the features build on), cheapest first. Then the
two structural features every downstream view needs — event locations and non-competing
attendees — then collection features, then money, then reports and event-ops in rough
order of event-day need. The seed import is last both by Mark's instruction and by
dependency: it wants guests, volunteers, and sites to exist before it can import them.

## Status

| # | Label | Item | Status |
|---|-------|------|--------|
| 1 | C4 | Adult 3XL shirt size | done |
| 2 | C5 | Congregation phone number | done |
| 3 | C3 | No Elementary teams (elementary may play up) | done |
| 4 | C2 | Individual standings in own bracket, not the team's | done |
| 5 | C1 | Combo (cross-congregation) teams | done |
| 6 | F6 | Event locations (multi-site seasons) | done |
| 7 | F1 | Guests & attendee types | done |
| 8 | F2 | Volunteer positions & tribe-leader willingness | done |
| 9 | F3 | Adult contact info & communication preference | done |
| 10 | F5 | Age-tiered fee schedule | done |
| 11 | F11 | Registration counts dashboard | done |
| 12 | F12 | Shirt-order report | done |
| 13 | F7 | Tester IDs + ZipGrade export | ID half done |
| 14 | F8 | Nametag PDF generation | done |
| 15 | F9 | Housing / cabin assignments | not started |
| 16 | F10 | Tribes & tribe leaders | not started |
| 17 | F13 | Seed the database from the 2026 workbook | not started |

---

## 1. (C4) Adult 3XL shirt size

The workbook's size list runs Youth S–L, Adult S–3XL; the app's `ShirtSize` enum stops at
Adult 2XL. Nobody ordered a 3XL in 2026, but it was offered.

- **Change:** add `AXXXL("Adult 3XL")` to `ShirtSize` in `shared-api` Dtos; UI pickers pick
  it up from the enum.
- **Dependencies:** none. Trivial opener.

## 2. (C5) Congregation phone number

The `Congregations` tab keeps a contact phone per congregation alongside the mailing
address the app already stores. `CongregationDto` has no phone field.

- **Change:** additive `phone: String = ""` on `CongregationDto` + create/update requests,
  column on the congregations table, field on the congregation form and registration desk.
- **Open question (resolve during refinement):** is the coach's account contact the real
  source of truth, making this redundant? Mark approved building it; keep it optional.
- **Dependencies:** none.

## 3. (C3) No Elementary teams (elementary may play up)

The workbook's team-category lookup has **no elementary option** — "Elementary (no teams)
[ENT]" — and every grade 3–6 contestant in 2026 was team-less. The app used to compute an
ELEMENTARY team division for an all-elementary roster, and team-less contestants were
invisible to scoring.

- **Rule as refined (Mark, 2026-07-20):** there are no Elementary *teams*, but an elementary
  contestant **may play up** onto a Junior or even Senior team — so team placement is NOT
  restricted by grade (the original correction idea); instead the team division computation
  changed.
- **As built:** `TeamDto.division` is the highest member's division floored at Junior. Power
  Round scores count only toward individual, non-Elementary placement — never toward team
  totals — so an elementary contestant never takes it, even when playing up (round
  eligibility follows the contestant's own division, per item 4's bracket split). Unassigned
  (team-less) contestants now appear on the grading sheet and in standings as individuals in
  their own division and experience bracket — the normal home for elementary contestants.
- **Dependencies:** none, but done before combo teams (5) so team rules are settled once.

## 4. (C2) Individual standings in own bracket, not the team's

The app ranks a team member's *individual* scores in the **team's** division and experience
bracket (`ScoreRoutes.rowSeeds` gives every member the team's division + team's
inexperienced flag). The workbook contradicts this: a sophomore who was individually
Senior-Inexperienced sat on a Senior-Experienced team; a Junior-Experienced contestant sat
on a Senior-Experienced team — and in every downstream tab (ZipGrade external IDs, the
`Counts` category tallies) the **individual** category is the contestant's own, with only
the team round using the elevated team bracket.

- **Change:** split the two brackets per contestant: individual placement ranks within the
  contestant's *own* division + experience; team placement keeps the team's elevated
  bracket. Touches standings computation, `DivisionStandingsDto` grouping, My Scores
  placement decoration, and the grading sheet's division column (show both or the
  individual one).
- **Dependencies:** none; lands cleanly before combo teams change team identity.

## 5. (C1) Combo (cross-congregation) teams

Two of nineteen 2026 teams pooled congregations: *MRLC-Combo* (McDermott Road + League
City) and *MCLC-SE* (Memorial + League City). The app ties every team to a single
congregation's registration, so a small congregation can't field a partial team and
combine. Most structural correction.

- **Change (as built, 2026-07):** a team keeps its home congregation's registration; a
  `team_members` row may point at another congregation's same-season team (no schema
  change — the member already carries its own `registration_id`). Cross-congregation
  placement is **registrar-mediated** (event-wide REGISTRATION_MANAGE; either side's coach
  gets 403) from the desk's unassigned picker; the home coach can always pull their member
  back. Each congregation still pays for/edits its own contestants (`contestantCount`
  counts home + away members, never visiting ones); visiting members display with their
  own congregation everywhere (roster cards, desk, grading sheet, standings — a combo
  team's standings row lists every member congregation); ≤4 cap and bracket rules
  unchanged. Home registrations carry an `awayMembers` list so nobody vanishes from their
  own books.
- **Dependencies:** after 3 and 4 so team rules/brackets are already correct.

## 6. (F6) Event locations (multi-site seasons)

2026 ran two sites — Bandina and White River Youth Camp — and *every* downstream artifact
(counts, testers, teams, nametags, ZipGrade, shirts, costs, housing) exists per site. Each
congregation attends exactly one site (Baker Heights was the lone White River congregation).
The app has no notion of a site.

- **Change:** season defines its site(s) (name, maybe address); each congregation's
  registration pins to one site (default when the season has a single site — keep the
  single-site path frictionless). Downstream views (11–16) filter by site.
- **As built:** `SeasonDto.sites` (`EventSiteDto(id, name, address)`) edited in Season settings —
  a site's id is generated once and survives renames, so fixing a name doesn't unpin anyone.
  `RegistrationDto.siteId` pins the registration: chosen on the register flow's congregation step
  (radio card, rendered only when the season has ≥2 sites), required before submit
  (409 `site_required`), and settable by a registrar from the desk's detail row. The desk gains a
  Site column plus a site filter that the table, summary, and CSV all follow. Single-site seasons
  never surface any of it: `siteFor` resolves a lone site regardless of what's stored.
- **Dependencies:** none hard, but must precede items 11–16, which are all per-site.

## 7. (F1) Guests & attendee types

The biggest structural gap: 68 of 223 attendees in 2026 were **not** testers. The workbook
types every attendee as any combination of Coach / Tester / Guest ("select all that
apply"), and guests include small children (fee tiers go down to "under 3"). The app
registers only contestants and adult individuals.

- **Change:** let a coach register non-competing attendees alongside contestants. A guest
  needs name, birthdate or age tier, gender, shirt size — no division, no team, no claim
  code. Attendee type falls out of the data (has roster entry → tester; coach account →
  coach; else guest) rather than being a stored multi-select, if refinement bears that out.
  Desk and counts include guests.
- **Dependencies:** after 6 (guests belong to a site via their congregation). Feeds 8, 10,
  11, 12, 15, 17.
- **As built (2026-07):** the base guest flow predated this item (PR #37: guests register,
  pay, and show on the desk). F1 added the rest: `GuestAgeTier` (Age 9+ / Age 3–8 / Under 3
  — the 2026 fee brackets by age, replacing the adult/child boolean, since a 9–17-year-old
  non-competing sibling pays the 9+ fee), under-3s free with no included shirt (nullable
  shirt size), and required gender per guest. Attendee type stays derived, not stored.
  **Types overlap** (Mark, 2026-07-21): the same person can be both a coach and a
  contestant — e.g. an adult individual contestant who also coaches — matching the
  workbook's "select all that apply". Anything deriving attendee types (the counts
  dashboard of item 11 especially) must treat Coach/Tester/Guest as a set per person,
  never as exclusive categories, and must not double-bill or double-count such a person.
  Built in parallel with item 6 at Mark's direction (F6 merged first) — guests inherit
  their site from the congregation's registration pin, so the two compose with no extra
  linkage.

## 8. (F2) Volunteer positions & tribe-leader willingness

The workbook collects positions per adult (2026 list: Sports Assistant, Test Monitor, Test
Grader, Kitchen Helper — season-configurable list preferred) plus a "willing to be a Tribe
Leader" flag, then works from a `Volunteers` tab grouped by position.

- **Change:** multi-select positions + tribe-leader checkbox on adult attendees (coaches
  and guests); admin view grouped by position per site.
- **Rule as refined (Mark, 2026-07-21):** any adult can be a tribe leader, but only
  non-contestant adults (adult guests) can hold volunteer positions (grader etc.).
- **As built (2026-07):** `SeasonDto.volunteerPositions` (season-configurable in Season
  settings, comma-separated; defaults to the 2026 list) drives position checkboxes +
  tribe-leader checkbox on age-9+ guests in the coach flow; child-tier guests get both
  cleared server-side, and off-list positions are rejected. Individual (adult) contestants
  carry only `tribeLeaderWilling`. The desk gains a Volunteers section (grouped by
  position, plus willing tribe leaders from guests and adult individuals) that respects
  the site filter, and badges positions/tribe-leader in the roster detail.
- **Dependencies:** 7 (adult guests exist). Feeds 16 (tribe leaders drawn from the willing).

## 9. (F3) Adult contact info & communication preference

The workbook collects address/city/state/zip/phone/email and a preferred-contact-method
column for coaches and some adults.

- **Change:** optional contact fields + communication preference on adult attendees.
- **Open question (resolved, Mark 2026-07-21):** collect the **full workbook set** —
  address/city/state/zip/phone/email + preferred method — not just a minimum.
- **As built (2026-07):** shared `ContactInfoDto` (+ `ContactPreference`: email / phone
  call / text), everything optional and free-form. Coaches (and any adult account) edit
  theirs on the **account profile** (#account, durable across seasons; email comes from
  the account so it isn't re-collected; `PUT /auth/me` leaves contact unchanged when the
  field is omitted, so the not-yet-updated Compose app can't wipe it). Adult (9+) guests
  get the same fields **plus email** (no account) on a collapsible per-guest Contact panel
  on the register screen. Registrars see both on the desk detail: a contact summary line
  under each guest and a "Coach contact info" block per congregation.
- **Dependencies:** 7.

## 10. (F5) Age-tiered fee schedule

2026 fees: Age 9+ → $85, Age 3–8 → $65, under 3 → free — applied to **every** attendee
(testers, coaches, guests), with per-congregation and per-site totals on the `Cost` tab
($18,710 total in 2026). The app currently computes a per-contestant total only.

- **Change:** season-configurable fee tiers by age; totals cover all attendees; register
  screen and desk show the per-congregation invoice; per-site roll-up for admins. Existing
  `paidAt` desk flow unchanged.
- **As built (with Mark's refinement):** child guests carry a **birthdate**, not a stored
  tier — `AgeTier` is derived (`ageTierFor`) from age on the season's grade-cutoff date, so
  a returning child's bracket advances on its own (supersedes item 7's stored enum; the
  legacy `age_tier` column is folded into an approximate birthdate and dropped). *Every*
  attendee is billed by tier via `registrationFeeLines`: an 8-year-old grade-3 contestant
  pays the child fee, exactly like the workbook. Prices stay the three season fields
  (contestant 9+/volunteer 9+/ages 3–8; under-3s free, no shirt); the 3/9 boundaries are
  named constants. The register flow shows a live tier + fee hint beside the birthdate,
  tier-aware fee math on every review row, and a per-tier total breakdown; the desk derives
  tiers for display and its site filter (item 6) already gives the per-site roll-up.
  `paidAt` flow untouched.
- **Dependencies:** 7 (guests must exist to be billed).

## 11. (F11) Registration counts dashboard

Replaces the `Counts` tab: totals and per-site / per-congregation breakdowns by attendee
type, gender, grade/age group, division + experience category, plus a graduating-seniors
list (derivable from grade 12 — no stored flag).

- **Dependencies:** 6, 7 (and 4 for correct category tallies).
- **As built (2026-07):** a read-only `#admin/counts` web screen (nav: "Registration Counts",
  same event-wide REGISTRATION_MANAGE gate and site filter as the desk, cross-linked both
  ways), computed client-side from the same `GET /admin/registrations` payload — no new
  endpoint. The flattening lives in `shared-api` (`Attendees.kt`: `deskAttendees` →
  `AttendeeRow`, one row per registered person by the `contestantCount` convention, visiting
  combo-team members counted at home) for reuse by downstream event-ops reports (item 12's
  shirt report, built first, follows the same convention via its own `shirtSizes` rule).
  Sections: headline totals (coach accounts shown *beside* the attendee total, never summed —
  types overlap), a by-congregation matrix with division columns + CSV export, gender,
  testers by grade / guests by age tier, division × experience for testers and for teams,
  and the graduating-seniors list (grade 12, derived). Testers count in their *own* division
  and bracket (item 4), and an 8-year-old contestant tallies in the child age tier (item 10).

## 12. (F12) Shirt-order report

Replaces the `Congregations` shirt-order matrix: size × congregation per site, grand
totals by size. Likely one view + CSV/PDF export over the same data as item 11.

- **Dependencies:** 6, 7 (guests get shirts too; 2026 ordered 220 shirts for 223 people).
- **As built (2026-07):** built ahead of items 10–11 at Mark's direction (its dependencies 6
  and 7 were already done). `RegistrationDto.shirtSizes` in `shared-api` is the single
  counting rule — home team members + individuals + unassigned + away (combo) members +
  shirted guests; visiting members count under their *own* congregation and under-3 guests
  get no shirt (the 220-of-223 gap). The desk gains a "Shirt order" section (congregation ×
  size matrix, grand totals by size, under-3 no-shirt note) that respects the site filter —
  pick a site to get that site's vendor order — plus a CSV of the same matrix. Pure web
  view over the existing desk response; no server change, so nothing new to feature-gate.
  PDF export skipped: the CSV is what a shirt order actually needs.

## 13. (F7) Tester IDs + ZipGrade export

The workbook assigns each tester a sequential ZipGrade ID per site plus an external ID of
the form `{IndCat}-{CongregationCode}-{TeamCat or Team}-{n}` and a "class" = congregation
code, exported to ZipGrade for scan grading.

- **Change:** auto-assign stable per-site tester IDs; generate the external ID; ZipGrade
  CSV export per site.
- **Open question (resolve during refinement):** the app now has its own grading desk — is
  ZipGrade still in the 2027 loop, or is only the human-readable tester ID (nametags,
  seating) worth keeping? Mark approved the item; confirm the export half before building
  it.
- **Dependencies:** 6; 5 (team identity settled); IDs feed 14.
- **ID half as built (2026-07, pulled forward with item 14 at Mark's direction):**
  `RosterEntryDto.testerId` — sequential per site (like the workbook's per-site ZipGrade
  sequences), assigned lazily the first time a registrar generates nametags and stable
  thereafter: late registrants extend the sequence, nobody is ever renumbered. New IDs
  number in (congregation, name) order; a combo member numbers with their own congregation
  at its site (`missingTesterIds` in `shared-api`). Shown on the desk roster detail
  ("tester #7"). **Still open:** the external ID (`{IndCat}-{CongregationCode}-…`) and the
  ZipGrade CSV export — resolve the open question first.

## 14. (F8) Nametag PDF generation

Replaces four nametag tabs: printable nametags per site — name, congregation, tester ID
for testers, optionally a photo. Build on the existing Typst pipeline (`:generation`
markup builders, server-side `typst` compile) like the other PDF endpoints.

- **Dependencies:** 13 (tester IDs), 7 (guests get nametags too).
- **As built (2026-07):** `GET /admin/registrations/nametags.pdf?siteId=` — authenticated
  (names include minors', so deliberately NOT a public `/generate` link) and gated like the
  rest of the desk (event-wide REGISTRATION_MANAGE + registration feature flag). 4×3in
  badges, six per letter page with dashed cut guides (`nametagsTypst` in `:generation`);
  each badge shows event+site heading, name, congregation, role at bottom-left (division
  for testers, Volunteer/Guest for guests), and the tester ID big at bottom-right. Each
  site starts a fresh page-stack; the desk's "Nametags PDF" button honors the site filter
  and refreshes the desk so newly assigned tester IDs appear. Photos skipped — the app
  stores none.

## 15. (F9) Housing / cabin assignments

Replaces the `Housing Assignments` and `Check out assignments` tabs: define cabins per
site; assign congregation × gender groups to cabins (2026 pattern: e.g. one congregation's
boys → one cabin, girls → another; some cabins shared across congregations); ad-hoc rows
for families/staff (RV sites, duplexes); plus a per-congregation cabin **check-out duty**
roster (one adult per congregation).

- **Scope note:** event-ops rather than registration proper; approved, but keep it a thin
  admin tool — free-form assignment grid, not an optimizer.
- **Dependencies:** 6, 7 (needs guests + genders per site).

## 16. (F10) Tribes & tribe leaders

Replaces the `Tribe leader assignment` tab: define tribes per site (2026: color names, two
leaders each), assign leaders drawing from adults who flagged willingness (item 8).

- **Dependencies:** 8. Same thin-admin-tool scope note as 15.

## 17. (F13) Seed the database from the 2026 workbook — LAST

One-time import so 2027 registration starts warm: every 2026 family becomes a returning
candidate.

- **Imports:** the 9 congregations with addresses/phones and their two-letter codes (BH,
  MR, LC, ME, MW, NB, WB, WO, WO…); durable contestants (name, gender, last shirt size,
  experience derived from Ind Category, congregation); coach associations; guests and
  volunteer positions (items 7–8); site attended (item 6).
- **Birthdate wrinkle (decided):** the workbook has school grades but no birthdates, and
  the app derives division from birthdate. Per discussion, prefer **grade-only seeded
  contestants whose birthdate is filled in the first time a coach enrolls them** over
  synthesizing fake birthdates. Refine the exact model (nullable birthdate + seeded 2026
  grade on the durable contestant) when this item starts.
- **Mechanics:** offline/admin-only ingestion (the workbook contains minors' PII — it
  never enters git; the import reads Mark's local copy). Idempotent so it can re-run.
- **Dependencies:** everything above it that defines what's importable (6, 7, 8; 2 for
  phones). Deliberately last, per Mark.

---

*Not building (from the same analysis):* the per-congregation Google-Sheet intake pipeline
(the app's coach registration replaces it), the `Coaches`/lookup tabs (already app concepts
or enums), and the static nametag-validation tabs (spreadsheet plumbing).
