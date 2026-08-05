# Staging environment

A full, separate staging stack so prod can be treated as precious once the production
domain points at it. Nothing deploys to staging automatically.

| Piece | Prod | Staging |
|---|---|---|
| Frontend (Hugo site + web app) | GitHub Pages, auto on push to `main` (`pages.yml`) | Fly app **`texas-bible-bowl-staging-web`** (nginx static), manual deploy |
| Backend (Ktor) | Fly app `texas-bible-bowl`, manual `fly deploy` | Fly app **`texas-bible-bowl-staging`**, manual deploy |
| Database | Neon (production branch) | **Neon branch of prod** (branch-only strategy) |

URLs: https://texas-bible-bowl-staging-web.fly.dev (frontend, sends
`X-Robots-Tag: noindex`) and https://texas-bible-bowl-staging.fly.dev (backend).

## Deploying

```bash
tools/deploy-staging.sh backend   # :server:test, then fly deploy -c fly.staging.toml
tools/deploy-staging.sh web       # web dist + Hugo (staging baseURL/backend) → deploy staging-web
tools/deploy-staging.sh all
```

Deploy staging **from the feature branch you want to test** — that's the point. Prod
deploys are unchanged: `fly deploy` (plain, from `main`) for the backend, merge to
`main` for the frontend.

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
  migrations independently.

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
- `ADMIN_EMAIL` / `ADMIN_PASSWORD` — `staging-admin@tbb.org`; seeded on boot only if that
  email doesn't exist yet, so it works both in-memory and on a prod branch (which already
  has the prod admins — those sign-ins work on staging too). Changing `ADMIN_PASSWORD`
  later does NOT update the existing user; after a branch reset the seeded staging admin
  is recreated from the secret on next boot.
- `ALLOWED_ORIGINS` — `https://texas-bible-bowl-staging-web.fly.dev`.

## Smoke checklist after a deploy

1. `curl https://texas-bible-bowl-staging.fly.dev/health`
2. `curl https://texas-bible-bowl-staging.fly.dev/seasons/current`
3. Open https://texas-bible-bowl-staging-web.fly.dev, browse to the app (`/app/#study`),
   sign in as `staging-admin@tbb.org`.

Both apps scale to zero when idle; the first request after a while pays a cold start
(~40 s for the JVM backend, ~1 s for nginx).
