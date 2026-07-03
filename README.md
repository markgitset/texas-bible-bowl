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
| `:core`       | KMP (jvm, wasmJs)       | Pure domain: `VerseRef`, `ChapterRef`, `VerseRange`, `Book`, `Heading`. Ported from `bible-bowl/model`. |
| `:shared-api` | KMP (jvm, wasmJs)       | RBAC (`Role`/`Permission`), `Division`/`RoundType`, and serializable DTOs shared by clients **and** server. |
| `:server`     | Ktor (JVM)              | JWT auth, RBAC-guarded question bank, `/health`; ESV proxy + Postgres + Typst PDF land here in later phases. |
| `:app`        | Compose MP (web, desktop) | Shared UI. Android/iOS targets slot in once an Android SDK / macOS host is available. |

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

# Web app (Compose/Wasm) — dev server with hot reload
./gradlew :app:wasmJsBrowserRun
# …or a production static bundle (deploy to Cloudflare/GitHub Pages)
./gradlew :app:wasmJsBrowserDistribution   # -> app/build/dist/wasmJs/productionExecutable

# Desktop app (quick local visual checks)
./gradlew :app:desktopRun -DmainClass=net.markdrew.biblebowl.app.MainKt
```

### Server configuration (env)

`PORT`, `JWT_SECRET`, `JWT_ISSUER`, `JWT_AUDIENCE`, and optional first-run admin
bootstrap `ADMIN_EMAIL` / `ADMIN_PASSWORD`. The ESV API token will be added
server-side only (never shipped to clients).

## Deployment (target: < $50/yr infrastructure)

### Backend → Google Cloud Run

The server ships as a container ([server/Dockerfile](server/Dockerfile)) bundling
the JRE, the app fat jar, the **Typst** binary, and the Libertinus fonts.

```bash
# Build & verify locally
docker build -f server/Dockerfile -t tbb-server .
docker run --rm -p 8080:8080 \
  -e DATABASE_URL=jdbc:postgresql://host.docker.internal:5432/biblebowl \
  -e JWT_SECRET=... -e ESV_API_TOKEN=... tbb-server

# Deploy (one-time: gcloud auth login && gcloud config set project <proj>)
gcloud run deploy tbb-server --source . --region us-central1 \
  --allow-unauthenticated --min-instances 0 \
  --set-env-vars DATABASE_URL=...,JWT_SECRET=...,ESV_API_TOKEN=...,ADMIN_EMAIL=...,ADMIN_PASSWORD=...
```

Scale-to-zero keeps this in the free tier at hobby traffic (~$0–20/yr).

### Postgres → Neon (free tier)

Create a free project at neon.tech; put its JDBC URL/credentials in
`DATABASE_URL`/`DATABASE_USER`/`DATABASE_PASSWORD`. Keeps the backend stateless.

### Web app → Cloudflare Pages / GitHub Pages ($0)

```bash
./gradlew :app:wasmJsBrowserDistribution
# upload app/build/dist/wasmJs/productionExecutable (CI also saves it as the
# `web-dist` artifact on every push)
```

Point `TbbApi.DEFAULT_BASE_URL` (or a build-time override) at the Cloud Run URL,
and restrict the server's CORS `anyHost()` to the web origin before launch.

## Status

Working now, verified end-to-end: accounts + JWT auth, five-role RBAC,
crowd-sourced question bank (submit → moderate → study → vote), licensed ESV
proxy with Postgres chapter cache, and Typst practice-test PDF generation
(server endpoint + one-tap download in the app). Next: port the remaining
bible-bowl generators (flashcards, indices, chapter drills), team/registration
flows, and on-test-day grading.
