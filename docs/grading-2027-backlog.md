# Grading backlog — gaps identified from the 2026 official grading spreadsheets

*Working backlog, from the grading-spreadsheet analysis (2026-07-21). Source of truth for
what to build in the scoring/grading area next; items are refined and built **one at a
time, in the order below**. Check items off as they land.*

**Source:** the two 2026 "Official" grading workbooks (`Copy of 2026 - Bandina
Official.xlsx` and `2026 - Whit River Official.xlsx`, on Mark's machine in `~/Downloads` —
they contain contestant names including minors', so they stay out of git). Both are copies
of one Google Sheets template, one per site. How 2026 grading actually ran:

- Rounds 2–5 were scanned with **ZipGrade**; the CSV export (`Quiz Name, Student First
  Name, Student Last Name, Student ID, Earned Points`) was pasted into `R2`–`R5` sheets.
  Each site's workbook held the **full statewide export** (152 scans, both sites mixed);
  a VLOOKUP by tester ID pulled out that site's rows, and `IFERROR(…,0)` silently zeroed
  anyone with no scan.
- **Verse Find and Power Round were hand-entered** into an `Everyone PR VF` sheet with
  sanity-check columns: flags fire when an entered score equals the tester ID (wrong-column
  paste) or when VF == PR (double paste).
- An `Everyone` sheet joined roster + scores: individual total = R1–R5 + Power; team
  contribution = rounds 1–5 only; bracket decoded from the external-ref prefix
  (AD/EI/EE/JI/JE/SI/SE).
- Seven **Top 10** sheets QUERY each bracket **ordered ascending** — announcement order
  for the awards ceremony (10th read first) — with places typed by hand (ties manual).
  Team sheets add per-team totals with a TEXTJOIN'd member list.
- A legacy `Results` sheet is solid `#REF!` errors — template rot from a prior year.

The workbooks **validate the app's scoring model**: totals, the Power-Round exclusion from
team scores, Elementary's Power exemption, the seven division × experience brackets, and
own-bracket individual ranking all match what's already built. The app already beats the
sheets on tie handling (automatic competition ranking), release gating, and audit trails.
Items below add what the sheets did that the app can't yet, or correct the model where the
workbooks contradict it.

## Ordering rationale

The model correction first (it changes what "scan-graded" means for everything after).
Then the cheap desk improvement every later item leans on (tester IDs visible), then the
structural site scoping that the import and completeness views filter by. The ZipGrade
import is the payoff item and wants all three in place. Completeness checking matters most
right after an import lands scores. Sanity checks are small and slot in anywhere; the
ceremony view is last because it's needed last on event day.

## Status

| # | Label | Item | Status |
|---|-------|------|--------|
| 1 | G7 | Rounds 4–5 are scan-graded (model correction) | done |
| 2 | G3 | Tester IDs on the grading desk | done |
| 3 | G2 | Site-scoped grading (filter + release semantics) | done — per-site rankings + per-site release |
| 4 | G1 | ZipGrade score import | done |
| 5 | G4 | Grading-completeness view | done |
| 6 | G6 | Hand-entry sanity checks | done |
| 7 | G5 | Awards / ceremony view + PDF | done — desk/ceremony tool; public champions page stays curated |

---

## 1. (G7) Rounds 4–5 are scan-graded (model correction)

`Round.kt` marks only Fact Finder (R2) and Identification (R3) as `multipleChoice`
("scantron-graded"), but in 2026 **all four of R2–R5** came out of ZipGrade with earned
points — the Quotes and Events answer sheets are evidently bubbled too (answers are
chapter numbers). Only Verse Find (R1) and the Power Round were hand-graded.

- **Change:** reconcile the flag's semantics — likely a distinct `scanGraded` property
  (R2–R5) alongside whatever `multipleChoice` legitimately drives (question formats,
  practice-test generation), rather than widening `multipleChoice` itself. Audit call
  sites to see which meaning each one wants.
- **Why first:** the ZipGrade import (item 4) needs an authoritative "which rounds arrive
  by scan" list, and the completeness view (item 5) reports hand-entered rounds
  differently from scanned ones.
- **Dependencies:** none. Cheap opener.

## 2. (G3) Tester IDs on the grading desk

The physical papers being graded carry the tester ID (item F7 of the registration backlog
assigned them; nametags print them), but `ScoreRowDto` doesn't include it — the grading
desk can't show it, sort by it, or jump to it. For the hand-entered rounds the grader is
holding a stack sorted by ID.

- **Change:** carry the tester ID (and external ID) on grading-sheet rows; show an ID
  column; allow ID-order sorting and type-an-ID-to-jump focus on the desk. IDs already
  exist in `tester_ids` (assigned lazily, append-only) — this is plumbing + UI only.
- **Dependencies:** none (F7 is done). Feeds the import's reconciliation report (item 4)
  and the completeness view (item 5), which both speak in tester IDs.

## 3. (G2) Site-scoped grading (filter + release semantics)

2026's two sites graded completely independently — separate workbooks, separate award
ceremonies — but the app's grading desk, standings, and My Scores have no site awareness:
one statewide list, and one season-global release switch.

- **Change:** the same site filter the registration desk got (F6) on the grading desk and
  standings; graders at a site see their site's stack.
- **Resolved (Mark, 2026-07-23):** ranking is **per-site** — brackets key on
  (site, division, experience), matching 2026's independent per-site awards. Individuals
  rank at their own registration's site, teams at their host registration's. Statewide is
  deferred as a future second lens (per-site is the priority). Release is **per-site**
  too: each site carries its own release stamp (`score_releases.site_id`, "" = the
  season-wide/site-less/legacy stamp), and a contestant sees their scores once their own
  site — or the season-wide stamp — is released. So a finished small site's families
  aren't blocked by a still-grading big one. Shipped in the G2 PR.
- **Dependencies:** none hard (F6 sites exist); before item 4 so the import can scope its
  reconciliation to a site.

## 4. (G1) ZipGrade score import

The payoff item. The app exports the ZipGrade roster (F7) but has no way to get scores
*back in* — the grading desk means hand-typing ~150 testers × 4 rounds, exactly the
drudgery the workbooks' VLOOKUPs existed to avoid. ZipGrade remains the primary 2027
scan-grading path (decided during F7).

- **Change:** upload/paste the ZipGrade CSV export on the grading desk (SCORE_ENTER);
  server parses, maps `Quiz Name` → `Round`, matches rows by tester ID, and applies scores
  through the existing validated set path (audit trail included). Then show a
  **reconciliation report**, not a silent success:
  - scores applied (count per round);
  - **unknown tester IDs skipped without error** — the statewide export contains the
    other site's scans (136 of White River's 152 pasted rows weren't theirs), so unknown
    IDs are normal, but list them;
  - name mismatches: rows whose ID matched a roster entry but whose name doesn't (catches
    a mis-bubbled ID crediting the wrong contestant — the workbook had no defense here);
  - duplicate scans of the same (ID, round) — **resolved: last value wins** (matches the
    re-paste-a-fresh-export rhythm), listed for review;
  - out-of-range or unparseable rows.
  - **Name mismatches are applied by ID and flagged** (the bubbled ID is the match key),
    not skipped — a real score isn't lost to a nickname, but the grader sees the flag.
- **Quiz-name mapping:** match on the `"Round N:"` prefix, not display names — ZipGrade
  says "Round 4: Quotes" while the app says "Know the Chapter — Quotations". Round
  numbers are stable; names aren't.
- **Idempotent:** re-importing the same (or a corrected) export updates in place — the
  2026 workflow re-pasted the export as scanning progressed, and the import should support
  the same "pull a fresh export every hour" rhythm.
- **Dependencies:** 1 (which rounds are scan-graded), 2 (IDs visible for chasing down
  reconciliation items), 3 (site scoping).

## 5. (G4) Grading-completeness view

The sheets' `IFERROR → 0` silently zeroed anyone who missed a scan, and Bandina's Top 10
Adult includes registered adults with flat 0s — "never tested" vs. "scan lost" were
indistinguishable. The app stores null-vs-scored per cell, so it can do better.

- **Change:** a per-round × per-site checklist of who has no score yet — surfaced on the
  grading desk and as a pre-release warning ("release with 12 ungraded cells?"). Respect
  round eligibility (an Elementary contestant's missing Power Round isn't a gap) and show
  hand-entered rounds (VF, Power) separately from scanned ones, since their gaps are
  chased differently (find the paper vs. re-scan).
- **Dependencies:** 3 (per-site), 4 (most useful right after an import).

## 6. (G6) Hand-entry sanity checks

The workbook's `Everyone PR VF` sheet had validation columns for the two hand-entered
rounds: a flag when the entered score equals the row's tester ID (classic wrong-column
paste) and when a contestant's VF and Power entries are identical (double paste). The
app's desk validates range (0–max) only.

- **Change:** cheap client-side **warnings, not blocks**, on the grading desk for
  hand-entered rounds: score == tester ID, VF == Power for the same contestant, and any
  other patterns refinement surfaces (e.g. a whole column of identical values). The 2026
  flags also fired false positives on blank rows — warn only on filled cells.
- **Dependencies:** 2 (tester IDs on rows).

## 7. (G5) Awards / ceremony view + PDF

The seven Top-10 sheets exist to *run the ceremony*: top N per bracket in **reverse
announcement order** (10th place read first, champion last), plus team results with the
member list spelled out. The app's standings screen is a full descending tally with no
member names on team rows and nothing printable.

- **Change:** a ceremony view over the existing standings data — top-N per bracket
  (2026 used 10; make it a knob), a reverse-order toggle, and team member lists on team
  rows (the DTO needs the members; the workbook TEXTJOINed them). Plus a Typst PDF export
  like the other print artifacts, one page stack per site, so the emcee holds paper.
  Authenticated route (minors' names), SCORE_VIEW_ALL.
- **Open question:** whether any of this feeds the public champions page on the Hugo site,
  or stays a desk/ceremony tool with the public page staying curated by hand.
- **Dependencies:** 3 (per-site pages). Last because it's needed last on event day.

---

*Not building (from the same analysis):* the congregation-code/external-ref decoding and
`Lookup` sheet (the app's data model already knows congregations, teams, and brackets
natively), hand-typed places (competition ranking is automatic and tie-correct), and the
dead `#REF!` `Results` sheet (template rot — the argument for the app, not a feature).
