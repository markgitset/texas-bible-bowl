# Domain migration: texasbiblebowl.org → the real production stack

Moves the domain from the frozen GitHub Pages snapshot (repo `tbb-website`, old IA) to
the current production stack, and moves prod's static hosting from GitHub Pages to Fly
(matching staging). Canonical host is the **apex** (what search engines already index);
`www` 301s to it. The backend gains `api.texasbiblebowl.org`.

DNS: Namecheap (`registrar-servers.com`). The zone also carries **Google Workspace MX
and google-site-verification TXT records — never touch those.**

## Phase 0 — prod web host on Fly ✅ (this PR)

Fly app **`texas-bible-bowl-web`** (nginx, 1 always-warm machine, www→apex 301, serves
the site built at `baseURL = https://texasbiblebowl.org/`). Deployed by the "Deploy to
production" promotion (`tools/deploy-web.sh prod`); GitHub Pages is no longer deployed
to. Old `/details/*` and `/study-resources/*` URLs are covered by Hugo aliases.
Smoke-test host: https://texas-bible-bowl-web.fly.dev.

## Phase 1 — pre-issue TLS certs (no user impact) — certs created 2026-08-08

`fly certs add` has been run for all three hostnames. At Namecheap, add the DNS-01
validation CNAMEs (Namecheap host field shown) so the certs issue while GitHub still
serves all traffic:

| Type | Host | Value |
|---|---|---|
| CNAME | `_acme-challenge` | `texasbiblebowl.org.d85lwdr.flydns.net` |
| CNAME | `_acme-challenge.www` | `www.texasbiblebowl.org.d85lwdr.flydns.net` |
| CNAME | `_acme-challenge.api` | `api.texasbiblebowl.org.jq3l980.flydns.net` |

Then `fly certs check <hostname> -a <app>` (apps: `texas-bible-bowl-web` for apex/www,
`texas-bible-bowl` for api) until all three are issued. Cutover then starts with
working TLS.

## Phase 2 — DNS cutover (Namecheap) ✅ done 2026-08-08

Leaving MX/TXT alone and using TTL ≈ 5 min during the transition:

| Record | Host | Old value | New value |
|---|---|---|---|
| A | `@` | 185.199.108–111.153 (×4, GitHub) | `66.241.124.167` |
| AAAA | `@` | 2606:50c0:… (×4, GitHub) | `2a09:8280:1::164:8b64:0` |
| CNAME | `www` | `markgitset.github.io` | `d85lwdr.texas-bible-bowl-web.fly.dev` |
| CNAME | `api` | (new) | `jq3l980.texas-bible-bowl.fly.dev` |

No downtime window: old and new hosts both serve the site during propagation.
**Rollback** = restore the GitHub A/AAAA/CNAME values; the old snapshot stays intact in
`tbb-website` until Phase 4, so don't retire it early. Records went out at the zone's
standard 30-min TTL rather than the 5-min transition TTL, so a rollback would take that
long to propagate — lower the TTL first next time.

Verified at cutover: apex + www + all deep pages serve `server: Fly/…`,
`www.texasbiblebowl.org/event/` 301s to the apex, the `/study-resources/*` aliases still
200, Let's Encrypt certs are live on all three hostnames, and
`https://api.texasbiblebowl.org/health` returns ok.

## Phase 3 — clients onto the api domain ✅ configured 2026-08-08

1. ✅ `ALLOWED_ORIGINS` already includes `https://texasbiblebowl.org` (released with the
   2026-08-08 promotion); the old Pages origin rides along during the transition.
2. ✅ Repo variable `BACKEND_URL` = `https://api.texasbiblebowl.org`.
3. **Takes effect on the next "Deploy to production" promotion**, which rebuilds the site
   and app against the api URL. Until that runs, the live app still calls
   `texas-bible-bowl.fly.dev` — which works, so this is not urgent.

The fly.dev backend URL keeps working indefinitely (Android's baked default; update
`app/build.gradle.kts` `tbb.backendUrl` default at the next app release). Once the
promotion has run and the site is confirmed healthy, trim `ALLOWED_ORIGINS` to the origins
still in use (drop the old `markgitset.github.io` entry).

## Phase 4 — retirement ✅ done 2026-08-08

The original plan was to keep GitHub Pages alive on this repo with `texasbiblebowl.org` as
its custom domain, so `markgitset.github.io/texas-bible-bowl/*` would 301 to the domain.
**That isn't possible once DNS points away from GitHub** — Pages verifies the domain
resolves to it before accepting the setting. Old github.io links now 404, which Mark
accepted: that URL was a dev/preview address, never the public one.

What was found and fixed:

1. **This repo's Pages was still live** and serving a frozen Aug 8 build — not just stale
   marketing copy but a *working app*, pointed at the prod backend, still inside
   `ALLOWED_ORIGINS`, and indexable (no `noindex`). So an old bookmark could sign in and
   write to the production database from a build that no longer gets deployed, while also
   competing with texasbiblebowl.org for the same search terms. Pages disabled
   (`gh api -X DELETE repos/markgitset/texas-bible-bowl/pages`); both URLs now 404.
2. **`tbb-website` still claimed `texasbiblebowl.org`** as its Pages CNAME. Pages disabled
   there too, releasing the claim. Archiving the repo is Mark's call.
3. **`ALLOWED_ORIGINS` trimmed** to `https://texasbiblebowl.org` — the old
   `markgitset.github.io` origin now gets a 403. Clients sending no `Origin` header (the
   Android app) are unaffected.
4. Docs sweep for `markgitset.github.io`; Android's `tbb.backendUrl` default moved to the
   api domain; HSTS added to `prod-web/nginx.conf` at `max-age=86400` (ramp to a year after
   a few stable weeks — see the comment there before adding `includeSubDomains`/`preload`).

**Do not delete the three `_acme-challenge` CNAMEs** at Namecheap. Fly reuses them to renew
the certs; removing them breaks renewal silently, ~60 days out.
