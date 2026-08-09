# CLAUDE.md — working notes for this repo

All-platform (web + Android + iOS-later) Texas Bible Bowl study/competition app in
Kotlin. See `README.md` for the module table and the plan file under `~/.claude/plans/`
for the roadmap. This file is the operational cheat-sheet: how to build, test, verify,
and deploy, plus the non-obvious gotchas.

## Modules (actual, as built)
`:core` `:shared-api` `:generation` `:client` `:app` `:web` `:server` — see `settings.gradle.kts`.
- **`:core`** is KMP but has a **`jvmMain` source set** that holds *server-only* JVM
  code (the whole Bible-text render engine + NLP/analysis). Common code (VerseRef,
  StudyData, etc.) is in `commonMain`; anything touching the copied bible-bowl JVM
  engine lives in `core/src/jvmMain`. (The plan said this would go in `:generation`;
  in practice it landed in `core/jvmMain`.)
- **`:generation`** holds the pure-Kotlin Typst *markup builders* used by both client
  and server (PracticeTest, Flashcards, QuizEngine).
- **`:client`** holds `TbbApi` (package `net.markdrew.biblebowl.client`), the typed
  backend client shared by the Compose apps and the web app (jvm/android/js; Ktor engine
  per platform). Android passes its BuildConfig backend URL to `TbbApi(baseUrl)` in
  `MainActivity` — `:client` can't see `:app`'s generated BuildConfig.
- **`:app`** is Compose Multiplatform for **android + desktop only** (iOS later). The
  wasm web target was removed 2026-07 in favor of `:web`.
- **`:web`** is the web app: plain Kotlin/JS DOM (no Compose, no JS framework), styled
  with Bootstrap 5 + the Hugo site's `site/static/css/custom.css` (copied into the dist
  by a `jsProcessResources` hook in `web/build.gradle.kts` — single source of truth).
  Hash routes (`#study`, `#questions/new`, …) are identical to the old wasm app so site
  links keep working. Screens are objects with `render(container)` in `web/.../screens/`;
  `Session` holds the shared `TbbApi`/season/user with a localStorage JWT restored via
  `/auth/me` on boot. Downloads are plain `<a target="_blank">` links to the public
  `/generate/*` endpoints (server sends `Content-Disposition: attachment`). Bundle is
  ~0.8 MB js (the wasm app was ~8.6 MB).

## Reuse strategy (locked in by Mark)
**Copy bible-bowl JVM source into `core/jvmMain` when needed — do NOT depend on the
bible-bowl jar.** The earlier ports kept bible-bowl's exact package names
(`net.markdrew.biblebowl.*`), so an external jar would collide. Copy code + curated data
verbatim; port to `commonMain` only when a client (web/app) actually needs to run it. The
curated data (`core/src/jvmMain/resources/word-lists/*.txt`, `acts/category-overrides.tsv`)
is the app's own — safe to commit. ESV *text* is copyrighted and stays out of git /
server-side only.

**Exception — chupacabra is a real dependency now, not vendored.** The
`net.markdrew.chupacabra.core` range utilities (DisjointRangeMap/Set, etc.) used to be
copied into `core/jvmMain`; they now come from the published KMP library
`com.github.markgitset.chupacabra:chupacabra-core` (JitPack — see the `jitpack.io` repo in
`settings.gradle.kts`), declared `api` in `core/jvmMain` so `:server` still sees the types.
That library is built with **Kotlin 2.4.0** and **Java 25 bytecode**, which is *why* this
repo runs Kotlin 2.4.0 / Compose 1.11.1 on **Gradle 9.5 + AGP 8.13** and requires **JDK 25**.

**JDK 25 is mandatory to build.** `gradle/gradle-daemon-jvm.properties` pins the Gradle
daemon to JDK 25 (with foojay download URLs, so Gradle auto-provisions it if missing) —
the daemon runs on 25 even when you launch `./gradlew` from an older JDK. Nothing needs a
per-module toolchain; compile + test all run on 25. Only Gradle ≥9.1 can run on JDK 25, so
don't downgrade the wrapper below 9.x. Kotlin 2.4.0's KGP officially supports Gradle up to
9.5.0 / AGP up to 9.1.0 — stay within that. The prod server image (`server/Dockerfile`) is
`eclipse-temurin:25-jdk` (build) / `25-jre` (runtime); CI uses JDK 25 (`ci.yml` and the
deploy workflows).

## Build & test — task names differ per module
Gradle task names are **not** uniform across modules. Use these:
- Core (JVM): `./gradlew :core:jvmTest` (compile: `:core:compileKotlinJvm`)
- Server: `./gradlew :server:test` (compile: `:server:compileKotlin`)
- App: `:app:compileKotlinDesktop`, `:app:compileDebugKotlinAndroid`, `:app:desktopTest`
  (there is **no** `:app:compileKotlinJvm` / `:app:build`-style single task).
- Client: `./gradlew :client:jvmTest` (the TbbApi wire/error tests).
- Web: `./gradlew :web:jsBrowserDistribution` (compile: `:web:compileKotlinJs`) →
  `web/build/dist/js/productionExecutable`.
- Full CI locally: `./gradlew :core:jvmTest :shared-api:jvmTest :generation:jvmTest
  :client:jvmTest :server:test :app:desktopTest` then `:app:assembleDebug` and
  `:web:jsBrowserDistribution` (mirrors `.github/workflows/ci.yml`).

**Yarn-lock gotcha:** adding/removing a Kotlin/JS module (or npm-visible deps) fails the
build with "Lock file was changed" — run `./gradlew kotlinUpgradeYarnLock` and commit
`kotlin-js-store/yarn.lock`.

## App navigation (Compose apps + web app)
Top-level destinations (`Routes.kt` per app): the Compose app has four — study, quiz,
questions, event — where the Study tab is the Study & Practice OVERVIEW: one card per
study-focus section ("Start here" on The Text; `StudyOverviewScreen`), each opening that
section's own screen at `study/<slug>` (`StudySectionScreen`, both in `StudyScreens.kt`);
`downloads` survives as a deep-link alias rendering the overview. This matches the web app's
study area (2026-07): `#study`, the same overview card grid, plus one page per study-focus
section at `#study/<slug>` (web's DownloadsScreen renders both). BOTH apps have a
`StudySection` enum in their `Routes.kt` with identical slugs/route strings — keep the two
enums, hugo.toml's menu children, and sitemap.html's hand-written study list in sync. The web
navbar's "Study & Practice" entry is a dropdown like Event/Scholarships/About whose children
are the section pages; the Compose app reaches sections via the overview cards (plain pushes on
the Study stack, so Back returns to the overview). Quiz, questions, and the browsers are
reached from the section screens/pages and carry Home › Study & Practice breadcrumbs (web). `#downloads` and unknown hashes redirect to `#study`; the
`/study-resources/*` Hugo pages are redirects into the app. The event tab was removed from
`:web` 2026-07 because the Hugo site already shows season info (the Android app has no site
around it, so its Event tab stays). Both also have signin, account, gated admin routes, and the full
registration/event-ops route set (`event/register`, `event/grading`, `event/standings`,
`event/my-scores`, `admin/registrations`, `admin/counts`, `admin/housing`, `admin/tribes`,
`admin/testers`, `admin/users`) with identical route strings and gating — the Compose app
reached web parity 2026-07 (PRs #59–#62; entry points are the Event tab's cards and the
Account screen's Event-staff links, standing in for the web navbar's NavMenu). No auth
wall — GET routes are
public server-side; JWT only on submit/vote/moderate; permission-gated routes render the
sign-in screen in place (never disabled-but-visible affordances). The Compose app uses
JetBrains navigation-compose with an adaptive scaffold in `App.kt`; the web app uses a
`hashchange` router in `web/.../Shell.kt` with a Bootstrap navbar (unknown hash → study
hub). To eyeball the web app: `./gradlew :web:jsBrowserDistribution`, then `preview_start`
the `web-dist` + `backend` configs in `.claude/launch.json` (backend is in-memory without
`DATABASE_URL` and bootstraps a dev admin, admin@tbb.org / admin-secret-123; ESV endpoints
503 without the token — expected). Stop the local backend before `:app:desktopTest`: a
live :8080 un-skips `EndToEndFlowTest`, which expects the Postgres stack.

## Seeding from the 2026 workbook (item 17, F13)
Two stages, PII never in git: `python3 tools/seed/convert_registration_xlsx.py` reads
`~/Downloads/Registration.xlsx` and writes `~/Downloads/tbb-seed-2026.json`; then
`POST /admin/seed` (global admin JWT) ingests it — idempotent, safe to re-run, returns a
summary with warnings. Grade-only seeded youth carry a `people.graduation_year` and get
their real birthdate at first enrollment; seeded coach emails become an `isCoach` participation
(no `pending_coach_grants` table since the people/participants restructure) that auto-grants COACH
at signup by email match. Volunteers are non-tester attendees with Positions → non-contestant
participants carrying those positions; the converter counts volunteers and warns on unrecognized
Attendee Types or unread volunteer-looking tabs, so a run that misses volunteers is obvious. After
editing the converter run its stdlib self-test: `python3 tools/seed/convert_registration_xlsx_test.py`.
The converter's output JSON format is unchanged, so re-running the whole flow against prod after the
restructure deploy reconciles placeholder coach names and enriches minimally-migrated rows.

## Verifying generated PDFs locally (no ESV token needed)
Typst is installed at `/home/mark/bin/typst` (v0.14.2); the server shells out to it.
To eyeball a PDF feature without the ESV token: write a throwaway jvmTest that builds
`StudyData` from a small hardcoded `Passage` fixture (see `EsvIndexer(...).indexBook(
sequenceOf(passage))` in `BibleTextTypstTest`), dump the Typst string to the scratchpad,
`typst compile x.typ x.pdf`, then Read the PDF. Delete the throwaway test afterward.
This renders the real pipeline without hitting Crossway.

## Deploy — trunk-based: main auto-deploys STAGING; prod is a manual promotion
- **Big picture (since 2026-08-08):** every merge to `main` auto-deploys the full **staging**
  stack (`deploy-staging.yml`). **Nothing deploys prod automatically.** Prod (backend +
  frontend together) ships via Actions → **"Deploy to production"** (`deploy-production.yml`):
  manual dispatch **from `main`** (optionally pinning a SHA in the `ref` input; default `main`
  HEAD), gated on Mark approving the `production` environment **once**, `:server:test` re-run,
  then Fly backend + Fly frontend deploy from that exact commit, health-checked and tagged
  `prod-YYYYMMDD-HHMM` — so `git tag -l 'prod-*'` is the deploy history and the latest tag is
  what's live. Promote only commits staging already ran. Fly deploy tokens live as
  environment-scoped GitHub secrets — **`production` holds `FLY_BACKEND_DEPLOY_TOKEN` and is
  the only reviewer-gated environment; `production-web` holds `FLY_WEB_DEPLOY_TOKEN` with no
  reviewers** (GitHub gates per gated job, so a second gated environment would mean a second
  approval click; the `frontend` job still can't start early because it `needs: backend`).
  Both are restricted to the `main` branch. Don't "simplify" this by moving a Fly token to a
  repo-level secret: the repo is public and has none, and the web token can publish anything
  to texasbiblebowl.org. See `docs/staging.md`.
- **Web (prod frontend):** static Fly app **`texas-bible-bowl-web`** (nginx, one always-warm
  machine, www→apex 301; same pattern as staging-web so staging rehearses prod), deployed
  ONLY by the production promotion above via `tools/deploy-web.sh prod` — ONE tree: the
  Hugo site (`/site`, `baseURL=https://texasbiblebowl.org/`) at the root and the Kotlin/JS
  app (`:web`) under `/app/`. GitHub Pages is no longer deployed to (domain cutover:
  `docs/domain-migration.md`). The build bakes
  `GET /seasons/current` into `site/data/params.json` before `hugo build`; `site/assets/js/params.js`
  (inlined minified at the end of `<body>`) live-patches `[data-tbb-param]` spans AND re-renders the
  Event > Curriculum schedule (`renderCurriculum`, mirroring the `curriculum-schedule` shortcode's
  rotation math + markup off `data/curriculum.yaml` + `#curriculum-data`) — it applies a
  localStorage-cached season synchronously before first paint, then refreshes from the backend. Hugo binary: `/home/mark/bin/hugo`
  (v0.164.0 extended); local build: `hugo -s site --gc --minify -d <out>`.
  Live: https://texasbiblebowl.org (app at `/app/#study`) — the DNS cutover landed
  2026-08-08, so the domain now serves this stack, not the old Pages snapshot. `www` 301s to
  the apex and the backend also answers at https://api.texasbiblebowl.org. The new host
  always answers at https://texas-bible-bowl-web.fly.dev for smoke tests.
- **Season params:** served by `GET /seasons/current` (public; PUT needs SEASON_MANAGE). Clients
  read them at launch (Compose: `LocalSeason`; web: `Session.season`) over the shared
  `FALLBACK_SEASON` baked into `:shared-api` — chapter counts and the season book are no longer
  hardcoded anywhere. Admin edits via #account → Season settings. The season also carries the
  feature-launch toggles `registrationEnabled`/`gradingEnabled` (default **off**): the
  registration and scoring areas deploy dark — hidden in the web UI and 403 `feature_disabled`
  on every server endpoint — until an admin flips them in Season settings. Global admins bypass
  both gates (links badged "hidden until launch") so dark features can be tested in prod.
- **CI (`ci.yml`):** runs tests + builds APK/web on push. Deploys nothing itself (staging
  deploys are the separate `deploy-staging.yml` workflow).
- **Backend, prod (Fly.io):** deployed by the production promotion workflow (above). Claude
  MAY still deploy directly (Mark OK'd 2026-07-13) using `/home/linuxbrew/.linuxbrew/bin/fly`
  (authenticated; the `~/.fly/bin` copy also works) — but the promotion workflow is the
  normal path; direct `fly deploy` is for emergencies, only after `:server:test` (and any
  other affected suites) are green, and never concurrently with one of Mark's deploys. Prod
  secrets (ESV token etc.) live in `fly secrets` and are never visible.
  Live: https://texas-bible-bowl.fly.dev — only claim "live" after hitting the endpoint.
- **Staging (Fly.io):** a full separate stack — backend `texas-bible-bowl-staging` (its own
  secrets; `DATABASE_URL` is a **Neon branch of prod**) plus static frontend
  `texas-bible-bowl-staging-web` (nginx serving the Hugo site + web app at the staging
  baseURL, `X-Robots-Tag: noindex`). Auto-deployed on every merge to `main`
  (`deploy-staging.yml`); `tools/deploy-staging.sh [backend|web|all]` still works manually
  from any branch under test. Merging a Flyway migration migrates the Neon staging branch
  immediately — reset the branch from parent BEFORE merging when a rehearsal matters.
  Runbook (branch create/reset, migration rehearsal, secrets): `docs/staging.md`.

## Conventions
- **Sync with `main` before planning, not just before coding** (Mark, 2026-07-31): the very
  first step of any task — including exploration and plan-writing — is `git fetch origin
  main` and working from (or rebasing onto) `origin/main`. A plan drawn from a stale
  checkout is wrong before it starts: this repo moves fast, and planning against old code
  means designing things main already has and conflicted rebases later (e.g. re-porting a
  parser main already had).
  Worktrees inherit their base from the primary checkout, so `git -C /home/mark/ws/texas-bible-bowl
  pull` **before** spawning one — otherwise the branch starts behind and the sync has to happen
  mid-task (it did on 2026-08-09: main had moved onto five of the files under edit).
- **No `Co-Authored-By: Claude` trailer** in commit messages (Mark's standing preference).
- Commit at each significant step (standing instruction), but **never push directly to
  `main`** (Mark, 2026-07-13): main is branch-protected (PRs + green `build-and-test`
  required) — work on a branch and open a PR; do not bypass the protection rules.
- **After a PR merges, collect the local branch: `tools/prune-merged-branches.sh --apply`,
  run from the primary worktree.** PRs are squash-merged, which rewrites the SHA, so
  `gh pr merge --delete-branch` drops only the remote branch and `git branch -d` can't prove
  the local one is merged. Nothing else collects them — 120 had piled up by 2026-08-09. The
  merging session can't do it itself (it's standing on the branch), hence "later, from main".
  Dry run by default; it keeps anything it can't prove landed.
- **Only one worktree can run the local backend**: `:8080` is baked into the web app's
  `defaultBaseUrl()`, the desktop `DEFAULT_BASE_URL`, and `EndToEndFlowTest`'s probe, so
  `.claude/launch.json` deliberately gives the `backend` config no `autoPort` (the static-file
  configs have it and are safe to run in parallel). Stop one before starting another.
- The local dev admin (`admin@tbb.org` / `admin-secret-123`) is passed by
  `.claude/launch.json` **only when `DATABASE_URL` is unset** — the server's env-var admin
  seeding itself works in any mode (a fresh prod DB seeds its first admin from fly
  secrets; Mark wants that behavior kept).
- ESV license is a non-profit license: the ESV token, text cache, and all analysis
  caching stay **server-side only**.
- **Password-reset email** (`POST /auth/forgot-password` → 6-digit code): outbound SMTP is
  configured entirely by env vars (`SMTP_HOST`/`SMTP_USERNAME`/`SMTP_PASSWORD`, optional
  `SMTP_PORT`=587 and `SMTP_FROM`=username — fly secrets in prod). Unset → `LogOnlyEmailService`
  logs the code instead of sending, so the flow deploys dark and local dev reads codes from the
  server log.

## Text-PDF render pipeline (where the covered-text feature lives)
`core/jvmMain/.../generate/text/`: `AnnotatedDoc` (DisjointRangeMap annotation layers) →
`BibleTextWalker.walk(doc, studyData, options, handler)` → `TypstBibleTextWriter`'s
`bibleTextTypst(...)` returns a Typst string (server compiles it). Structural layers come
free from `studyData.toAnnotatedDoc(BOOK,CHAPTER,HEADING,VERSE,POETRY,PARAGRAPH,
LEADING_FOOTNOTE,FOOTNOTE)`. Feature layers are added on top:
- **REGEX** (highlighting) — from the category resolution (`AnnotationStore`/`WordList`/
  curated overrides), Postgres-cached in `text_annotations`. `Highlighting.kt` +
  `tbbHighlightPalette()`.
- **UNIQUE_WORD** (underline hapaxes) — from `oneTimeWords(studyData)` (pure
  `StudyData.wordIndex`, no NLP), gated on `TextOptions.underlineUniqueWords`.
- **Small-caps** — handled inline in `emitText` (`LORD` → `#smallcaps[Lord]`); no layer.

Endpoint: `GET /generate/bible-text.pdf?fontSize&twoColumns&justified&chapterBreaksPage&
useHeadingsForChapters&chapterEndLines&verseOnNewLine&highlight&underlineUniqueWords&
chapterHeadingSize&sectionHeadingSize`
(highlight on by default). The footer stamps the season's event dates ("April 2–4, 2027"),
not the generation date; the cached-PDF stamp is salted with that date line **and**
`LayoutRevisions.BIBLE_TEXT` (see below).

**Bump the generator's `LayoutRevisions` entry whenever you change how a PDF is drawn.**
Generated PDFs are cached under `StudyDataService.contentStamp()` — the study text plus the
word-list digest — which moves when the *material* changes but not when the *rendering* does. So
a layout change is invisible to the cache: clients keep getting the PDF an earlier build
compiled, and redeploying won't dislodge it (that's how the chapter-grouped headings sheet
failed to reach staging). `LayoutRevisions` in `GenerateRoutes.kt` holds one constant per Typst
generator, folded into the stamp of every PDF it produces; `respondIndexPdf`/`respondCachedPdf`
require it, so a new endpoint can't skip it. A bump costs one recompile per study set — when in
doubt, bump. The escape hatch for an already-stale cache is `DELETE /generate/cache`
(SEASON_MANAGE).

**Heading/footnote sizes are body-relative, never absolute.** Footnotes are left entirely to
Typst (0.85em); chapter/section headings come from `HeadingSize` (`:shared-api`) — named chips
(Same as text/Small/Medium/Large) mapping to multipliers that `TypstBibleTextWriter` multiplies
by `fontSize` and emits as points. Points, not `em`: inside `heading(...)` Typst has already
applied its 1.4em/1.2em, so an `em` would compound instead of replace. The two heading controls
are independent and either may be larger — a section heading bigger than the chapter heading is
a supported preference, not a bug. See `docs/text-generation-backlog.md`.
