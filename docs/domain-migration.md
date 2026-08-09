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

## Phase 4 — redirects + retirement (after a few stable days)

1. Remove the custom domain from `tbb-website`'s Pages settings; then set
   `texasbiblebowl.org` as the custom domain on **this** repo's Pages → GitHub 301s all
   `markgitset.github.io/texas-bible-bowl/*` URLs to the domain. (If that redirect
   misbehaves with DNS pointing away from GitHub, fallback: deploy a tiny meta-refresh
   shell as this repo's final Pages artifact.)
2. Disable `tbb-website`'s Pages / archive the repo (Mark's call).
3. Docs sweep for `markgitset.github.io` URLs; consider HSTS in `prod-web/nginx.conf`
   (`max-age` small at first) once everything has been stable.
