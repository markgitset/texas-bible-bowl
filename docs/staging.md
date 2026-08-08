# Staging environment

A full, separate staging stack so prod can be treated as precious now that the production
domain points at it. Trunk-based flow: **merging to `main` deploys staging; prod is a
manual, approved promotion** of a commit staging already ran.

| Piece | Prod | Staging |
|---|---|---|
| Frontend (Hugo site + web app) | GitHub Pages, via "Deploy to production" promotion (`deploy-production.yml`) | Fly app **`texas-bible-bowl-staging-web`** (nginx static), auto on push to `main` |
| Backend (Ktor) | Fly app `texas-bible-bowl`, same promotion workflow | Fly app **`texas-bible-bowl-staging`**, auto on push to `main` |
| Database | Neon (production branch) | **Neon branch of prod** (branch-only strategy) |

URLs: https://texas-bible-bowl-staging-web.fly.dev (frontend, sends
`X-Robots-Tag: noindex`) and https://texas-bible-bowl-staging.fly.dev (backend).

## Deploying

**Staging (automatic):** every merge to `main` runs `deploy-staging.yml` — `:server:test`,
backend deploy, then the frontend rebuilt at the staging URLs, then a smoke check. The same
steps still run by hand from any branch when you want to try something pre-merge:

```bash
tools/deploy-staging.sh backend   # :server:test, then fly deploy -c fly.staging.toml
tools/deploy-staging.sh web       # web dist + Hugo (staging baseURL/backend) → deploy staging-web
tools/deploy-staging.sh all
```

**Prod (manual promotion):** Actions → **"Deploy to production"** → Run workflow (optionally
pinning a SHA; default is `main`'s HEAD) → approve the `production` environment gate. That
one run re-tests, deploys the Fly backend AND GitHub Pages from the same commit,
health-checks, and tags the commit `prod-YYYYMMDD-HHMM` — `git tag -l 'prod-*'` is the
deploy history. Fly deploy tokens are environment-scoped GitHub secrets
(`FLY_BACKEND_DEPLOY_TOKEN`, `FLY_WEB_DEPLOY_TOKEN`), created with
`fly tokens create deploy -a <app>`.

## Database: Neon branch workflow

Staging's `DATABASE_URL` points at a Neon **branch** of the prod database — an instant
copy-on-write snapshot with its own compute endpoint. Writes on either side never affect
the other; the branch does NOT stay in sync with prod (it's a snapshot, not a replica).

- **Create** (Neon console → project → Branches → New branch, from the production branch's
  head, with a read-write compute endpoint). Copy the branch's pooled connection string and:
  `fly secrets set DATABASE_URL='<branch connection string>' -a texas-bible-bowl-staging`
- **Reset from prod** (refresh staging data / start a migration rehearsal): Branches →
  the staging branch → *Reset from parent*. This DISCARDS all staging-only data. Then
  `fly apps restart texas-bible-bowl-staging` so the server drops old connections.
- **Migration rehearsal** (the reason for branch-only): reset the branch from prod, deploy
  the candidate code to staging — Flyway applies the new migrations to the branch against
  real prod-shaped data (the branch copies `flyway_schema_history`, so only the new
  versions run). If it survives, deploy the same code to prod, which applies the same
  migrations independently. NOTE: since merging to `main` auto-deploys staging, a merged
  migration hits the branch immediately — for a meaningful rehearsal, reset the branch
  from parent BEFORE merging (or rehearse pre-merge via `tools/deploy-staging.sh backend`
  from the feature branch).

Because the branch is a prod copy it contains real registration PII and prod user
accounts — treat staging access accordingly.

## Secrets (staging app)

Set on `texas-bible-bowl-staging`; distinct from prod except where noted:

- `DATABASE_URL` — the Neon branch connection string (above). Until it's set the server
  runs in-memory (fine for smoke-testing; data resets on restart).
- `ESV_API_TOKEN` — same token as prod (same non-profit license, still server-side only):
  `fly secrets set ESV_API_TOKEN=<token> -a texas-bible-bowl-staging`. Unset → ESV
  endpoints 503.
- `JWT_SECRET` — fresh random value, deliberately different from prod so tokens are not
  valid across environments.
- `ADMIN_EMAIL` / `ADMIN_PASSWORD` — deliberately NOT set. The Neon branch is a prod copy,
  so the prod admin accounts (same email + password) already sign in on staging; a separate
  seeded staging admin would just be a second credential guarding the same PII. Only set
  these (temporarily) if you need an admin while running in-memory (no `DATABASE_URL`) —
  and remember the seed persists in a real DB once created: it survives unsetting the
  secrets and only disappears with a user delete or a branch reset from parent.
- `ALLOWED_ORIGINS` — `https://texas-bible-bowl-staging-web.fly.dev`.

## Smoke checklist after a deploy

1. `curl https://texas-bible-bowl-staging.fly.dev/health`
2. `curl https://texas-bible-bowl-staging.fly.dev/seasons/current`
3. Open https://texas-bible-bowl-staging-web.fly.dev, browse to the app (`/app/#study`),
   sign in with your prod admin account (the branch DB carries the prod users).

Both apps scale to zero when idle; the first request after a while pays a cold start
(~40 s for the JVM backend, ~1 s for nginx).
