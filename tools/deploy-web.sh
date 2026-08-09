#!/usr/bin/env bash
# Build the Hugo site + Kotlin/JS app and deploy the static frontend for one environment.
# Runs locally or in CI (deploy-staging.yml / deploy-production.yml).
#
#   tools/deploy-web.sh staging   # → Fly app texas-bible-bowl-staging-web (staging URLs)
#   tools/deploy-web.sh prod      # → Fly app texas-bible-bowl-web (canonical hugo.toml baseURL)
#
# prod reads BACKEND_URL from the environment (CI passes the repo variable); it defaults
# to the fly.dev backend host until the api.texasbiblebowl.org cutover (docs/domain-migration.md).
set -euo pipefail
cd "$(dirname "$0")/.."

env=${1:?usage: tools/deploy-web.sh staging|prod}
hugo_args=()
case "$env" in
  staging)
    backend_url="https://texas-bible-bowl-staging.fly.dev"
    # Override the canonical baseURL with the staging host (canonifyURLs relocates all links).
    hugo_args=(-b "https://texas-bible-bowl-staging-web.fly.dev/")
    app_dir=staging-web
    ;;
  prod)
    backend_url="${BACKEND_URL:-https://texas-bible-bowl.fly.dev}"
    app_dir=prod-web
    ;;
  *) echo "usage: $0 staging|prod"; exit 2 ;;
esac

# CI installs the binary as `flyctl` (superfly/flyctl-actions); Mark's machine has `fly`.
FLY=$(command -v fly || command -v flyctl || echo /home/linuxbrew/.linuxbrew/bin/fly)
HUGO=$(command -v hugo || echo "$HOME/bin/hugo")

./gradlew :web:jsBrowserDistribution

# Bake current season params from this environment's backend, restoring the committed
# fallback afterward so the working tree stays clean. The backup must live OUTSIDE
# site/data/ — Hugo loads every file in that directory as site data.
params=site/data/params.json
params_bak=$(mktemp)
cp "$params" "$params_bak"
trap 'cp "$params_bak" "$params"; rm -f "$params_bak"' EXIT
if curl -fsS --max-time 30 "$backend_url/seasons/current" -o /tmp/tbb-web-params.json \
    && [ -s /tmp/tbb-web-params.json ]; then
  cp /tmp/tbb-web-params.json "$params"
  echo "Baked live season params from $backend_url"
else
  echo "WARNING: could not fetch $backend_url/seasons/current; using committed fallback params"
fi

out="$PWD/$app_dir/public"
rm -rf "$out"
HUGO_PARAMS_BACKENDURL="$backend_url" "$HUGO" -s site --gc --minify "${hugo_args[@]}" -d "$out"
cp "$params_bak" "$params" && rm -f "$params_bak" && trap - EXIT

# /app/ assembly: Hugo renders the shared-chrome shell at app/index.html; copy the JS
# dist around it, excluding the dev-only index.html.
dist=web/build/dist/js/productionExecutable
test -s "$out/app/index.html" || { echo "ERROR: Hugo did not render the app shell (app/index.html)"; exit 1; }
rsync -a --exclude=index.html "$dist"/ "$out/app/"
cp "$out/app/index.html" "$out/app/404.html"

# Passing the directory makes it both the config source and the (tiny) build context.
"$FLY" deploy "$app_dir"
