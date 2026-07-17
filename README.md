# Texas Bible Bowl — All-Platform App

An all-platform (web + Android + iPhone) study & competition companion for
[Texas Bible Bowl](https://www.texasbiblebowl.org). Built almost entirely in
Kotlin: **Kotlin Multiplatform + Compose Multiplatform** UI over a **Ktor +
PostgreSQL** backend, sharing domain logic ported from
[`bible-bowl`](https://github.com/markgitset/bible-bowl), with range utilities
from [`chupacabra`](https://github.com/markgitset/chupacabra) (a JitPack
dependency).

> Current season book: **Acts (ESV)**, 2026–27. Season parameters (book,
> chapter count, event dates, prices) are served by `GET /seasons/current` and
> editable in-app by admins — clients hardcode nothing beyond a baked-in
> fallback.

## Modules

| Module        | Kind                    | Purpose |
|---------------|-------------------------|---------|
| `:core`       | KMP (jvm, android, js)  | Common domain (`VerseRef`, `VerseRange`, `Book`, `StudyData`) in `commonMain`; the server-only Bible-text render + NLP/analysis engine (copied from `bible-bowl`, plus curated word lists) in `jvmMain`. |
| `:shared-api` | KMP (jvm, android, js)  | RBAC (`Role`/`Permission`), `Division`/`Round`, season params (`SeasonDto` + fallback), and serializable DTOs shared by clients **and** server. |
| `:generation` | KMP (jvm, android, js)  | Study-material generators shared by clients and server: `QuizEngine`, Typst markup builders (practice tests, flashcards). |
| `:client`     | KMP (jvm, android, js)  | `TbbApi` — the typed backend client shared by the Compose apps and the web app. |
| `:server`     | Ktor (JVM)              | JWT auth, RBAC-guarded question bank, ESV proxy + chapter cache, study endpoints, Typst PDF generation. |
| `:app`        | Compose MP (android, desktop) | Native app UI. iOS target slots in once a macOS host is available. |
| `:web`        | Kotlin/JS (browser)     | The web app: plain-DOM screens styled with the Hugo site's CSS, deployed under `/app/` on Pages. |

Not a Gradle module: `site/` is the public **Hugo** site, published at the Pages
root with the web app under `/app/`.

## Roles (RBAC)

`contestant` (default) · `coach` · `registrar` · `grader` · `admin` — stackable,
some scoped to a congregation/team/event. Enforced server-side; the UI reveals
features from the same permission set.

## Run it

Prereqs: **JDK 25** (Gradle auto-provisions it — `gradle/gradle-daemon-jvm.properties`
pins the daemon toolchain and downloads a JDK if missing) and **Typst** on the
`PATH` (the server shells out to it for the PDF endpoints). No Android SDK
needed for web/desktop.

```bash
# Run everything CI runs (task names are per-module — there is no useful root `test`)
./gradlew :core:jvmTest :shared-api:jvmTest :generation:jvmTest :client:jvmTest :server:test :app:desktopTest

# Ktor backend on :8080 — in-memory store when DATABASE_URL is unset; seed a dev admin:
ADMIN_EMAIL=admin@tbb.org ADMIN_PASSWORD=admin-secret-123 ./gradlew :server:run
curl localhost:8080/health          # {"status":"ok","service":"texas-bible-bowl","season":"Acts"}
# Without ESV_API_TOKEN the ESV-backed endpoints return 503 — everything else works.

# Web app (Kotlin/JS) — build the static bundle, then serve it
./gradlew :web:jsBrowserDistribution       # -> web/build/dist/js/productionExecutable
python3 -m http.server 8090 --directory web/build/dist/js/productionExecutable
# open http://localhost:8090 — the app calls http://localhost:8080 unless
# window.TBB_BACKEND_URL is set (Pages injects the prod URL at deploy time)

# Desktop app (quick local visual checks; also talks to localhost:8080)
./gradlew :app:run
```

### Server configuration (env)

- `PORT` — listen port (default 8080; Fly/Cloud Run set this).
- `DATABASE_URL` — either a managed-Postgres URL (`postgresql://user:pass@host/db?sslmode=require`,
  as Neon hands out) or a `jdbc:postgresql://…` url with `DATABASE_USER` / `DATABASE_PASSWORD`.
  Unset → in-memory store (dev only).
- `JWT_SECRET`, `JWT_ISSUER`, `JWT_AUDIENCE` — token signing/verification.
- `ESV_API_TOKEN` — licensed ESV API token (server-side only, never shipped to clients).
- `ALLOWED_ORIGINS` — comma-separated web origins for CORS (e.g. `https://markgitset.github.io`);
  unset stays permissive for dev.
- `ADMIN_EMAIL` / `ADMIN_PASSWORD` — optional first-run admin bootstrap (created only if
  the email doesn't already exist).
- `ESV_CACHE_DIR` — dev-only on-disk ESV chapter cache, used when `DATABASE_URL` is unset
  (prod caches chapters in Postgres). Default `~/.cache/texas-bible-bowl/esv` — outside the
  repo because cached ESV text is copyrighted and must never be committed.
- `ESV_CACHE_REFRESH=true` — make the file cache re-fetch and overwrite existing entries.
- `PRIME_CACHE_ON_START=true` — index the season's study data in the background at boot so
  the first study/PDF request doesn't pay the cold-start cost (best-effort, non-blocking).

## Deployment (target: < $50/yr infrastructure)

Stack: **backend → Fly.io**, **Postgres → Neon**, **web → GitHub Pages** — all
scale-to-zero / free-tier.

### Backend → Fly.io

The server ships as a container ([server/Dockerfile](server/Dockerfile)) bundling
the JRE, the app fat jar, and the static **Typst** binary (release binaries embed
their default fonts). [fly.toml](fly.toml) wraps it with scale-to-zero.

```bash
# One-time: install flyctl, then authenticate (opens a browser)
fly auth login

# Create the app (name in fly.toml must be globally unique) and set secrets
fly apps create texas-bible-bowl
fly secrets set \
  DATABASE_URL='postgresql://user:pass@ep-xxx.neon.tech/biblebowl?sslmode=require' \
  JWT_SECRET="$(openssl rand -hex 32)" \
  ESV_API_TOKEN='<your ESV token>' \
  ALLOWED_ORIGINS='https://markgitset.github.io' \
  ADMIN_EMAIL='you@example.com' ADMIN_PASSWORD='<strong password>'

fly deploy   # builds server/Dockerfile, ships to https://texas-bible-bowl.fly.dev
```

### Postgres → Neon (free tier)

Create a free project at neon.tech and copy its pooled connection string into the
`DATABASE_URL` secret above. The server parses it into a JDBC url + credentials.

### Web (site + app) → GitHub Pages ($0)

Pushes to `main` trigger [.github/workflows/pages.yml](.github/workflows/pages.yml),
which publishes **one artifact**: the Hugo site (`site/`) at the root and the
Kotlin/JS app under `/app/`. Before the Hugo build it bakes the live season
params (`GET /seasons/current`) into `site/data/params.json`, and it injects the
backend URL from the repo variable `BACKEND_URL` into the app's `index.html` as
`window.TBB_BACKEND_URL`. To wire it up: set `BACKEND_URL` (Settings → Secrets
and variables → Actions → Variables) to the Fly URL, and enable Pages with the
**GitHub Actions** source.

Live: <https://markgitset.github.io/texas-bible-bowl/> (app at `/app/#study`).

## Status

Working now, verified end-to-end: accounts + JWT auth, five-role RBAC,
crowd-sourced question bank (submit → moderate → study → vote) with TSV/XLSX
export, licensed ESV proxy with Postgres (prod) / on-disk (dev) chapter cache,
backend-served season params editable in-app, an interactive quiz engine, study
screens (headings, names, numbers), and Typst PDF generation: practice tests,
verse and heading flashcards, names/numbers indices, and the full study text
with category highlighting and unique-word underlining. Next: team/registration
flows, on-test-day grading, and the iOS target.
