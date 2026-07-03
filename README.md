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

## Status

Phase 0 (scaffold) + the accounts/RBAC/question-bank MVP backbone are in place
and tested. Next: swap the in-memory repositories for Exposed/Postgres, add the
ESV proxy + cache, then port the study-material generators and Typst PDF export.
