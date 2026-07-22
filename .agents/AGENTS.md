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
`eclipse-temurin:25-jdk` (build) / `25-jre` (runtime); CI uses JDK 25 (`ci.yml`/`pages.yml`).

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
Top-level destinations (`Routes.kt` per app): the Compose app has five — study, quiz,
questions, downloads, event; the web app has four — the event tab was removed from `:web`
2026-07 because the Hugo site already shows season info (the Android app has no site around
it, so its Event tab stays). Both also have signin, account, gated admin routes, and the full
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
summary with warnings. Grade-only seeded youth carry `contestants.graduation_year` and get
their real birthdate at first enrollment; seeded coach emails auto-grant COACH at signup.

## Verifying generated PDFs locally (no ESV token needed)
Typst is installed at `/home/mark/bin/typst` (v0.14.2); the server shells out to it.
To eyeball a PDF feature without the ESV token: write a throwaway jvmTest that builds
`StudyData` from a small hardcoded `Passage` fixture (see `EsvIndexer(...).indexBook(
sequenceOf(passage))` in `BibleTextTypstTest`), dump the Typst string to the scratchpad,
`typst compile x.typ x.pdf`, then Read the PDF. Delete the throwaway test afterward.
This renders the real pipeline without hitting Crossway.

## Deploy — what's automatic vs manual
- **Web (GitHub Pages):** auto on push to `main` via `.github/workflows/pages.yml` — ONE artifact:
  the Hugo site (`/site`) at the root and the Kotlin/JS app (`:web`) under `/app/`. CI bakes
  `GET /seasons/current` into `site/data/params.json` before `hugo build`; `site/assets/js/params.js`
  (inlined minified at the end of `<body>`) live-patches `[data-tbb-param]` spans — it applies a
  localStorage-cached season synchronously before first paint, then refreshes from the backend. Hugo binary: `/home/mark/bin/hugo`
  (v0.164.0 extended); local build: `hugo -s site --gc --minify -d <out>`.
  Live: https://markgitset.github.io/texas-bible-bowl/ (app at `/app/#study`)
- **Season params:** served by `GET /seasons/current` (public; PUT needs SEASON_MANAGE). Clients
  read them at launch (Compose: `LocalSeason`; web: `Session.season`) over the shared
  `FALLBACK_SEASON` baked into `:shared-api` — chapter counts and the season book are no longer
  hardcoded anywhere. Admin edits via #account → Season settings. The season also carries the
  feature-launch toggles `registrationEnabled`/`gradingEnabled` (default **off**): the
  registration and scoring areas deploy dark — hidden in the web UI and 403 `feature_disabled`
  on every server endpoint — until an admin flips them in Season settings. Global admins bypass
  both gates (links badged "hidden until launch") so dark features can be tested in prod.
- **CI (`ci.yml`):** runs tests + builds APK/web on push. **Does NOT deploy the backend.**
- **Backend (Fly.io):** NOT deployed by CI — a pushed backend change is not live until
  someone runs `fly deploy`. Claude MAY run it (Mark OK'd this 2026-07-13) using
  `/home/linuxbrew/.linuxbrew/bin/fly` (authenticated; the `~/.fly/bin` copy also works), but
  only after `:server:test` (and any other affected suites) are green, and never concurrently
  with one of Mark's deploys. Prod secrets (ESV token etc.) live in `fly secrets` and are never
  visible. Live: https://texas-bible-bowl.fly.dev — only claim "live" after hitting the endpoint.

## Conventions
- **No `Co-Authored-By: Claude` trailer** in commit messages (Mark's standing preference).
- Commit at each significant step (standing instruction), but **never push directly to
  `main`** (Mark, 2026-07-13): main is branch-protected (PRs + green `build-and-test`
  required) — work on a branch and open a PR; do not bypass the protection rules.
- The local dev admin (`admin@tbb.org` / `admin-secret-123`) is passed by
  `.claude/launch.json` **only when `DATABASE_URL` is unset** — the server's env-var admin
  seeding itself works in any mode (a fresh prod DB seeds its first admin from fly
  secrets; Mark wants that behavior kept).
- ESV license is a non-profit license: the ESV token, text cache, and all analysis
  caching stay **server-side only**.

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
useHeadingsForChapters&chapterEndLines&verseOnNewLine&highlight&underlineUniqueWords`
(highlight on by default). The footer stamps the season's event dates ("April 2–4, 2027"),
not the generation date; the cached-PDF stamp is salted with that date line.
