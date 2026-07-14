# Texas Bible Bowl — All-Platform App

An all-platform (web + Android + iPhone) study & competition companion for
[Texas Bible Bowl](https://www.texasbiblebowl.org). Built almost entirely in
Kotlin: **Kotlin Multiplatform + Compose Multiplatform** UI over a **Ktor +
PostgreSQL** backend, sharing domain logic ported from
[`bible-bowl`](https://github.com/markgitset/bible-bowl) and
[`chupacabra`](https://github.com/markgitset/chupacabra).

> Current season book: **Acts (ESV)**. See `~/.claude/plans/…` for the full plan.

## Modules

| Module        | Kind                    | Purpose |
|---------------|-------------------------|---------|
| `:core`       | KMP (jvm, android, js)  | Pure domain: `VerseRef`, `ChapterRef`, `VerseRange`, `Book`, `Heading`. Ported from `bible-bowl/model`. |
| `:shared-api` | KMP (jvm, android, js)  | RBAC (`Role`/`Permission`), `Division`/`Round`, and serializable DTOs shared by clients **and** server. |
| `:generation` | KMP (jvm, android, js)  | Study-material generators shared by clients and server: `QuizEngine`, Typst markup builders. |
| `:client`     | KMP (jvm, android, js)  | `TbbApi` — the typed backend client shared by the Compose apps and the web app. |
| `:server`     | Ktor (JVM)              | JWT auth, RBAC-guarded question bank, ESV proxy + Postgres cache, Typst PDF generation. |
| `:app`        | Compose MP (android, desktop) | Native app UI. iOS target slots in once a macOS host is available. |
| `:web`        | Kotlin/JS (browser)     | The web app: plain-DOM screens styled with the Hugo site's CSS, deployed under `/app/` on Pages. |

## Roles (RBAC)

`contestant` (default) · `coach` · `registrar` · `grader` · `admin` — stackable,
some scoped to a congregation/team/event. Enforced server-side; the UI reveals
features from the same permission set.

## Run it

Prereqs: JDK 23, Typst (installed for later PDF work). No Android SDK needed for
web/desktop.

```bash
# Run the whole test suite (core models, RBAC, server integration)
./gradlew test

# Run the Ktor backend on :8080  (POST /auth/register, /auth/login, /questions …)
./gradlew :server:run
curl localhost:8080/health          # {"status":"ok","service":"texas-bible-bowl","season":"Acts"}

# Web app (Kotlin/JS) — production static bundle (deployed to GitHub Pages)
./gradlew :web:jsBrowserDistribution       # -> web/build/dist/js/productionExecutable
# …or a dev server with auto-reload
./gradlew :web:jsBrowserRun

# Desktop app (quick local visual checks)
./gradlew :app:desktopRun -DmainClass=net.markdrew.biblebowl.app.MainKt
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

### Web app → GitHub Pages ($0)

Pushes to `main` trigger [.github/workflows/pages.yml](.github/workflows/pages.yml),
which builds the Kotlin/JS bundle, injects the backend URL from the repo variable
`BACKEND_URL`, and publishes. To wire it up: set `BACKEND_URL` (Settings → Secrets
and variables → Actions → Variables) to the Fly URL, and enable Pages with the
**GitHub Actions** source.

## Status

Working now, verified end-to-end: accounts + JWT auth, five-role RBAC,
crowd-sourced question bank (submit → moderate → study → vote), licensed ESV
proxy with Postgres chapter cache, and Typst practice-test PDF generation
(server endpoint + one-tap download in the app). Next: port the remaining
bible-bowl generators (flashcards, indices, chapter drills), team/registration
flows, and on-test-day grading.
