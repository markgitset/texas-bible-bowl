# Texas Bible Bowl — GUI Redesign & Information Architecture

*Design document: UI vision, IA, and screen inventory. Deliberately **not** a build plan (per Mark);
a lightweight phasing sketch is in Appendix A.*

## Context

The current GUI grew feature-by-feature and shows it: `App.kt` auth-gates **everything** behind an
email+password screen, navigation is a hand-rolled `TabRow` with no URLs/deep-links/back-button on
web, and the flagship action (downloading study PDFs) hides in an 11-item dropdown. Meanwhile a
separate Hugo site (`/home/mark/ws/tbb-website` → texasbiblebowl.org) carries all public info and
63 hand-linked PDFs — two things to maintain that should be one. Upcoming features (coach
registration, event ops/grading) need a coherent home before they're built.

**Decisions locked with Mark (2026-07-11):**
- Hybrid rendering: public info stays real HTML (SEO/previews/screen readers); Compose app for everything interactive. One repo, one deploy.
- Web + Android are equal design targets; desktop keeps working but doesn't drive design.
- Auth mechanism deferred — design around abstract "signed-in user with permissions".
- Season parameters (dates, fees, book…) served by the server, edited via in-app admin screen.
- Domains: keep github.io + texasbiblebowl.org both temporarily; migrate later.
- Parents/score access: **owner-account model** — a contestant profile is a roster record owned by the account that created/claimed it; owner + team's coach see the scores. No PARENT role.
- Public results: **nothing public.** Results visible only to signed-in, scoped users; the public history page shows only admin-curated past champions.
- Download center includes **CSV/TSV exports** for Kahoot/Quizlet/Space import.
- Contestant accounts: **roster entries first, optional accounts** — coach enters names; a contestant/parent may later create an account and claim their entry via a coach-shared code.

## 1. Design thesis

Invert the current model: **TBB is one public website with an interactive core.** Signing in only
*adds* capabilities (contribute, coach, grade, administer) — it never gates study material. Goals in
priority order: (1) public-first, (2) minimize clicks to common actions (download text, take a
quiz, look something up), (3) one brand across both halves, (4) role features appear only for
holders (never disabled-but-visible).

## 2. Information architecture

### 2.1 Sitemap  — `[S]` static HTML (Hugo) · `[A]` Compose app route · role tags in parens; untagged = public

```
texasbiblebowl.org/                      [S] Home — hero, season banner (year/book/dates via params),
│                                            CTAs: "Study now" → /app#/study · "Register" · "How it works"
├── event/                               [S] how-it-works/ · rules/ · rounds/ (descriptions + sample Qs)
│                                            · curriculum/ (10-yr rotation) · why-esv/ · locations/
├── register/                            [S] evergreen prose + live pricing/deadlines; CTA → /app#/event/register
├── scholarships/ (+3 children)          [S]
├── about/ (story, leadership,           [S]
│           volunteers, in-the-news)
├── photos/ (+year galleries)            [S] JSON-driven galleries stay in Hugo
├── champions/                           [S] admin-curated past champions (the only public "results")
├── donate/ · contact/ · search/         [S] as today (PayPal, email, Fuse.js)
│
└── app/                                 [A] Compose wasm bundle, hash-routed
    ├── #/study                          [A] Study hub (default landing)
    │   ├── #/study/read/{chapter?}      [A] online reading view
    │   ├── #/study/indices/{names|numbers} [A]
    │   └── #/study/headings             [A] headings browser + self-check mode
    ├── #/quiz (+ /run, /results)        [A]
    ├── #/questions                      [A] browse community bank (public read)
    │   ├── #/questions/new              [A] (QUESTION_SUBMIT)
    │   └── #/questions/moderate         [A] (QUESTION_MODERATE)
    ├── #/downloads                      [A] download center (public)
    ├── #/event                          [A] season hub: dates, fees, schedule + role-aware cards
    │   ├── #/event/register             [A] (TEAM_MANAGE) team → roster → review → payment
    │   ├── #/event/my-scores            [A] (SCORE_VIEW_OWN; owner-account + coach scoping)
    │   ├── #/event/registration-desk    [A] (REGISTRATION_MANAGE)
    │   └── #/event/grading              [A] (SCORE_ENTER / SCORE_RELEASE)
    ├── #/admin                          [A] (ADMIN) season params, users/roles, registrations, releases
    ├── #/signin                         [A] abstract sign-in with return-to
    └── #/account                        [A] profile, claim-a-roster-entry, roles, sign out
```

### 2.2 Six areas → five app destinations + the static site

| Area | Home |
|---|---|
| a. Public info | Static half |
| b. Downloadable content | App: **Downloads** |
| c. Interactive study | App: **Study** + **Quiz** |
| d. Community question bank | App: **Questions** |
| e. Registration | App: **Event** → Register |
| f. Event ops / results | App: **Event** → Grading / My Scores |

Admin is not top-level; it's reached from the account menu and role-aware Event-hub cards. Public
nav is identical for everyone, and the five app destinations fit an Android bottom bar exactly.

### 2.3 Who sees what

| Visitor | App nav | Extras surfaced |
|---|---|---|
| Anonymous | Study, Quiz, Questions, Downloads, Event | quiet "Sign in" icon only |
| Contestant/owner | same | submit/vote; Event hub gains **My Scores** |
| Coach | same | Event hub gains **Register my teams**, roster, team scores |
| Grader / Registrar | same | Event hub gains **Grading** / **Registration Desk** |
| Admin | same | Account menu + Event hub gain **Admin**; Questions gains Moderate tab |

## 3. Hybrid seam design

**Rule of thumb: prose and photos are static; anything that computes is Compose.** The Register
static page deliberately overlaps: evergreen prose + live pricing + one CTA into the app flow.

- **Keep Hugo, move it in-repo as `/site`.** The Hugo site already handles the hard static parts
  (JSON-driven photo galleries with 1000+ images, Fuse.js search, 30 consistent pages); rewriting
  that in Kotlin-generated HTML is cost without benefit. Restructure `details/` → `event/`; replace
  the hand-maintained downloads/practice-tests pages with redirect stubs → `/app#/downloads` (keep a
  slim "external study games" page for the Kahoot/CRAM links).
- **One header design, two implementations.** Hugo header partial and Compose top bar render the
  same logo (`tbb-logo.png` + white variant), same five labels, same navy/gold. Every static page
  links "Study App" → `/app#/study`; the app logo links back to `/`; Event hub deep-links to static
  Rules/Locations/Scholarships (Android opens them in a Custom Tab).
- **Season params via `params.js` with baked fallback** (not rebuild-on-change): server exposes
  public `GET /seasons/current` (the exact field set now in `hugo.toml [params]` lines 13–33 —
  year, book, dates, theme, deadlines, prices, scholarship amounts). CI curls it into
  `site/data/params.json` before `hugo build` so pages render real values statically; a tiny
  `params.js` patches `[data-tbb-param]` spans live. Admin edits are instantly live on both halves;
  a Fly cold start just means last-deploy values for a few seconds.

## 4. App navigation model

- **URL routing (biggest structural change):** replace `MainScreen.kt`'s index-based tabs with
  JetBrains **`navigation-compose` (KMP)** + typed route graph; bind browser history on wasm via
  `window.bindToNavigation(navController)` in `wasmJsMain/Main.kt`. Use **hash routes**
  (`/app#/quiz?round=FACT_FINDER&chapter=7`) — cleaner than relying on the existing 404.html SPA
  fallback; revisit path-style after domain migration. Gets back/forward, refresh-safe state, and
  shareable deep links on web; Android deep links from the same graph.
- **Adaptive scaffold**, one `AppScaffold`, keyed on Material 3 window width class:
  - *Wide (web/desktop):* persistent top bar — logo, five text-tab destinations, study-scope chip
    (§7.2), account icon. Reading surfaces stay `widthIn(max = 720.dp)`; data-dense surfaces
    (grading grid, registration desk, moderation) widen to ~1100dp.
  - *Narrow (Android/phone web):* bottom nav with the five destinations; top bar has context title,
    scope chip, account icon.
- **Sign-in is contextual, never a wall:** quiet account icon top-right; tapping a gated action
  while anonymous routes to `#/signin` with return-to, then straight back.

## 5. Screen inventory

### A. Public info (static)
Carried over from the Hugo site with the `event/` regrouping, live-param spans (Home, Register,
Scholarships), the new `champions/` page, and downloads pages replaced by redirect stubs. Prose
pages; no further spec needed.

### B. `#/downloads` — Download center (public)
Replaces the app's 11-item dropdown (`StudyScreen.kt`) *and* the site's 63-link page. One scrolling
page of **preset cards in five groups**; each card = one click to a sensible default + a small
"Customize" affordance (bottom sheet narrow / side sheet wide). Honors the global study-scope chip.
Cards show what you get ("PDF · ~40 pages · updated for {book} {year}") and a busy state during
Typst compile.

| Group | Cards (primary → endpoint) | Customize |
|---|---|---|
| Study text | **Highlighted study text** (flagship) → `bible-text.pdf` | font size, 2-col, justified, page/chapter, plain, unique-word underline |
| Flashcards | All-rounds deck · Headings deck → `flashcards.pdf` / `heading-flashcards.pdf` | round, chapter |
| Indices | Names · Numbers → `names/numbers-index.pdf` | — |
| Practice tests | One card per round (R1–R5) → `practice-test.pdf` | limit, seed ("same test again"), exact vs through-chapter |
| **Exports** | **Kahoot spreadsheet** (xlsx template) · **Quizlet/Space TSV** — question bank + headings as import-ready files (new endpoints) | round, chapter, source |

### C. Interactive study (public)
- **`#/study` — hub & default landing.** Compact cards: Continue reading (last position), Indices,
  Headings, "This season: {book}" header. One tap to everything.
- **`#/study/read/{chapter}` — reading view (new).** ESV text via existing `/bible/{book}/{chapter}`
  + `/study/headings`: verse numbers, heading landmarks, optional name/number highlight toggle
  (server's annotation categories), prev/next, headings drawer for jumping. Remembers position.
- **`#/study/indices/{names|numbers}`.** Current `IndexScreen` behavior (search, alpha/frequency);
  references deep-link into the reading view; PDF button delegates to the download center.
- **`#/study/headings`.** Headings list (R5 material) with a flip-to-test self-check mode — the
  interactive twin of the heading-flashcards PDF.
- **`#/quiz`** — setup defaults to last-used settings + global scope; one tap "Start". **`/run`**
  stepper, **`/results`** with per-question review and "retry missed". (Existing `QuizEngine`.)

### D. Community question bank
- **`#/questions` — browse (public read).** Today's approved-question list, promoted: filter chips
  (round, chapter), search, tap-to-reveal. Vote renders only when signed in; anonymous tap prompts
  contextual sign-in. Requires making `GET /questions?status=APPROVED` public.
- **`#/questions/new`** (QUESTION_SUBMIT). Current `ContributeScreen` form + live preview card.
- **`#/questions/moderate`** (QUESTION_MODERATE). Current `ModerateScreen`; keyboard-friendly wide.

### E. Registration
- **`#/event/register` — coach flow** (TEAM_MANAGE). 4-step linear flow, progress rail, resumable,
  editable until deadline: (1) **Congregation** claim/confirm → (2) **Teams** (division computed via
  `Division.forGrade`, highest-member rule shown inline) → (3) **Roster** (≤4/team; name, grade,
  shirt size; running price total from season params; each entry gets a **claim code** the coach can
  share so a contestant/parent account can later claim it) → (4) **Review & submit** — payment
  instructions pre-filled (mail-a-check now; online payment drops into this step later).
- **`#/event/registration-desk`** (REGISTRATION_MANAGE). Table of congregations/teams: status,
  payment-received toggle, contestant-code assignment, event-day check-in, CSV export.

### F. Event ops & results
- **`#/event` — season hub.** Public: dates, location links, schedule, fee table (live params),
  deadline countdown. Role-aware cards appended per §2.3.
- **`#/event/grading`** (SCORE_ENTER). Pick round → roster grid by contestant code → fast numeric
  tab-through entry → per-round verify (double-entry / max-points check) → division tally.
  **Release** (SCORE_RELEASE) publishes into the scoped views; nothing visible pre-release.
- **`#/event/my-scores`** (SCORE_VIEW_OWN, owner-account scoping). Own released scores by round +
  placement. The roster entry's **owner account** and the **team's coach** see it; coaches get a
  per-member list view. Graders/admin see all (SCORE_VIEW_ALL).
- **No public results in the app.** History for the public = the static, admin-curated
  `champions/` page.

### G. Admin (ADMIN)
- **`#/admin/season`** — CRUD for the `/seasons/current` payload + "start next season" (new record,
  prior becomes history). Saves are live immediately on both halves (§3).
- **`#/admin/users`** — search users; grant/revoke `RoleGrant`s with scope pickers (`ScopeType`).
- **`#/admin/registrations`** — registration-desk surface, unscoped.
- **`#/admin/releases`** — score release control per event/year.
- Moderation reuses `#/questions/moderate`.

### H. Account
**`#/signin`** (abstract, return-to). **`#/account`** — profile, **claim a roster entry** (enter
coach-shared code → account becomes the entry's owner), roles held, sign out.

## 6. Design system

**Adopt the site's brand into Compose; retire the app-only palette** (`Theme.kt`):
- **Colors:** primary navy `#1a3a5c` (replaces indigo `#3B4A78`), secondary gold `#c9952a`
  (replaces `#B88A2E`); keep the paper background `#FAF8F4` + ink text — suits a reading-heavy app.
- **Type:** **Fraunces** display/headlines, **Inter** body/UI — bundled as compose-resources fonts,
  wired into `Typography` (currently defaults). Slightly larger body for ESV reading view.
- **Components:** Material 3. Standardize: elevated cards (questions/downloads/hub), filter chips
  (chapter/round), segmented buttons (names|numbers, alpha|frequency), one primary button per card,
  bottom/side sheets for "Customize".
- **Dark mode:** keep in-app (re-tint `darkColorScheme` to navy/gold — night studying is real).
  Static half stays light-only; acceptable asymmetry.

## 7. Click-efficiency principles

1. **Defaults over configuration** — every generator works with zero choices; options behind one "Customize".
2. **One global study scope** — persistent "through chapter N / all" chip in the app bar (localStorage web / DataStore Android), shared by quiz, downloads, indices, reading. Set once per season.
3. **No dropdowns for primary actions** — the 11-item menu becomes scannable cards.
4. **Public-first** — zero auth clicks on the study path; sign-in appears only at a gated action, with return-to.
5. **Deep links as a feature** — every useful state has a URL; coaches share links, not instructions.
6. **Remember everything cheap** — last quiz settings, reading position, index tab.
7. **Two-click ceiling** — any leaf screen ≤2 interactions from app landing.
8. **Role-aware surfacing** — holders see extra cards/tabs in place; non-holders see nothing.

## 8. Key technical enablers (not a build plan)

- **Routing:** `org.jetbrains.androidx.navigation:navigation-compose` (KMP) + `material3-adaptive`;
  typed graph replaces tab state in `app/src/commonMain/.../screens/MainScreen.kt`;
  `window.bindToNavigation()` in `app/src/wasmJsMain/.../Main.kt`; auth-gate removal in `App.kt`.
- **Public read endpoints:** move read-only routes out of the `authenticate {}` blocks inside the
  route files (`QuestionRoutes.kt` approved-only when anonymous; `BibleRoutes.kt`, `StudyRoutes.kt`,
  `GenerateRoutes.kt`). Keep JWT on submit/vote/moderate + all new registration/score/admin routes.
  Add mild rate limiting on `/generate/*` (Typst is CPU-bound) — recommended over requiring sign-in.
- **Season params:** new `seasons` table + `SeasonRoutes.kt` (`GET /seasons/current` public; `PUT`
  gated by existing `Permission.SEASON_MANAGE`, Rbac.kt:69). Replaces hardcoded `ACTS_CHAPTERS = 28`
  in `StudyScreen.kt` and the `"season" to "Acts"` literal in `Application.kt`'s `/health`.
- **New domain tables** (schema only, for E/F): `congregations`, `teams`, `team_members` (with
  owner-account id + claim code), `registrations`, `events`, `scores` — extending
  `server/.../data/`; DTOs in `shared-api/.../Dtos.kt`. Role/permission enums need **no changes**.
- **Exports:** new `/generate/questions.xlsx` (Kahoot template) + `.tsv` (Quizlet/Space) endpoints
  reusing the question repo + headings.
- **CI/deploy:** one Pages workflow — curl `/seasons/current` → `site/data/params.json` →
  `hugo build` → `wasmJsBrowserDistribution` → copy into `public/app/` → single artifact.
  `window.TBB_BACKEND_URL` injection stays.
- **Theme/fonts:** rework `app/.../ui/Theme.kt`; mirror tokens in the Hugo custom-property CSS.

## 9. Remaining open questions (low-stakes, decide later)

1. Exact rate-limit policy for anonymous `/generate/*` (per-IP bucket? daily cap?).
2. Whether `champions/` history is authored as static Markdown or admin-edited via the seasons admin (start static).
3. Path-style URLs post-domain-migration (needs host rewrites; hash routes fine indefinitely).

## Deliverable & verification

This document **is** the deliverable for this session (Mark chose "plan only, no build order").
On approval: optionally commit a copy into the repo (e.g., `docs/gui-redesign.md`) so it's
versioned alongside the code it will guide. No code changes in this session.

When implementation starts (future sessions), each phase verifies as: `./gradlew :app:desktopTest
:server:test` + `:app:wasmJsBrowserDistribution`, then eyeball routes/deep-links in the browser
(preview tools), and `hugo server` for the static half.

## Appendix A — suggested phasing (lightweight)

1. **Public-first core:** open read-only routes, routing + adaptive scaffold, navy/gold rebrand — app usable anonymously with current features.
2. **Download center + reading view** (the two highest-traffic wins) + CSV/xlsx exports.
3. **Hybrid merge:** Hugo into `/site`, unified Pages deploy, shared header, `params.js`, seasons endpoint + admin season screen.
4. **Registration:** schema + coach flow + registration desk + claim codes.
5. **Event ops:** grading, tally, release, My Scores with owner/coach scoping; curated champions page.
6. **Domain migration** of texasbiblebowl.org; retire the old site repo.
