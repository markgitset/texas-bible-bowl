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
| 5 | C1 | Combo (cross-congregation) teams | not started |
| 6 | F6 | Event locations (multi-site seasons) | done |
| 7 | F1 | Guests & attendee types | not started |
| 8 | F2 | Volunteer positions & tribe-leader willingness | not started |
| 9 | F3 | Adult contact info & communication preference | not started |
| 10 | F5 | Age-tiered fee schedule | not started |
| 11 | F11 | Registration counts dashboard | not started |
| 12 | F12 | Shirt-order report | not started |
| 13 | F7 | Tester IDs + ZipGrade export | not started |
| 14 | F8 | Nametag PDF generation | not started |
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

- **Change (shape to refine):** either let a team accept members from another
  congregation's registration, or lift teams to season level with a "home" congregation.
  Constraints to preserve: each congregation still pays for (and edits) its own
  contestants; the desk and standings show a sensible congregation per member (the
  workbook shows each member under their own congregation); ≤4 cap and bracket rules
  unchanged. Cross-congregation assignment likely needs coach-to-coach or registrar
  mediation — refine the permission story before building.
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

## 8. (F2) Volunteer positions & tribe-leader willingness

The workbook collects positions per adult (2026 list: Sports Assistant, Test Monitor, Test
Grader, Kitchen Helper — season-configurable list preferred) plus a "willing to be a Tribe
Leader" flag, then works from a `Volunteers` tab grouped by position.

- **Change:** multi-select positions + tribe-leader checkbox on adult attendees (coaches
  and guests); admin view grouped by position per site.
- **Dependencies:** 7 (adult guests exist). Feeds 16 (tribe leaders drawn from the willing).

## 9. (F3) Adult contact info & communication preference

The workbook collects address/city/state/zip/phone/email and a preferred-contact-method
column for coaches and some adults.

- **Change:** optional contact fields + communication preference on adult attendees.
- **Open question (resolve during refinement):** how much is actually used vs the coach's
  account contact? Collect the minimum that event ops really consumed in 2026.
- **Dependencies:** 7.

## 10. (F5) Age-tiered fee schedule

2026 fees: Age 9+ → $85, Age 3–8 → $65, under 3 → free — applied to **every** attendee
(testers, coaches, guests), with per-congregation and per-site totals on the `Cost` tab
($18,710 total in 2026). The app currently computes a per-contestant total only.

- **Change:** season-configurable fee tiers by age; totals cover all attendees; register
  screen and desk show the per-congregation invoice; per-site roll-up for admins. Existing
  `paidAt` desk flow unchanged.
- **Dependencies:** 7 (guests must exist to be billed).

## 11. (F11) Registration counts dashboard

Replaces the `Counts` tab: totals and per-site / per-congregation breakdowns by attendee
type, gender, grade/age group, division + experience category, plus a graduating-seniors
list (derivable from grade 12 — no stored flag).

- **Dependencies:** 6, 7 (and 4 for correct category tallies).

## 12. (F12) Shirt-order report

Replaces the `Congregations` shirt-order matrix: size × congregation per site, grand
totals by size. Likely one view + CSV/PDF export over the same data as item 11.

- **Dependencies:** 6, 7 (guests get shirts too; 2026 ordered 220 shirts for 223 people).

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

## 14. (F8) Nametag PDF generation

Replaces four nametag tabs: printable nametags per site — name, congregation, tester ID
for testers, optionally a photo. Build on the existing Typst pipeline (`:generation`
markup builders, server-side `typst` compile) like the other PDF endpoints.

- **Dependencies:** 13 (tester IDs), 7 (guests get nametags too).

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
