#!/usr/bin/env bash
# Deploy the staging stack (docs/staging.md). Prod deploys are untouched by this script.
# Runs locally or in CI (deploy-staging.yml auto-runs `backend` + `web` on push to main).
#
#   tools/deploy-staging.sh backend   # :server:test, then fly deploy -c fly.staging.toml
#   tools/deploy-staging.sh web       # build web dist + Hugo site, then deploy staging-web
#   tools/deploy-staging.sh all       # both, backend first
set -euo pipefail
cd "$(dirname "$0")/.."

BACKEND_URL="https://texas-bible-bowl-staging.fly.dev"
WEB_URL="https://texas-bible-bowl-staging-web.fly.dev/"

# CI installs the binary as `flyctl` (superfly/flyctl-actions); Mark's machine has `fly`.
FLY=$(command -v fly || command -v flyctl || echo /home/linuxbrew/.linuxbrew/bin/fly)
HUGO=$(command -v hugo || echo "$HOME/bin/hugo")

deploy_backend() {
  ./gradlew :server:test
  "$FLY" deploy -c fly.staging.toml
}

deploy_web() {
  ./gradlew :web:jsBrowserDistribution

  # Bake current season params from the staging backend (mirrors pages.yml), restoring the
  # committed fallback afterward so the working tree stays clean. The backup must live
  # OUTSIDE site/data/ — Hugo loads every file in that directory as site data.
  params=site/data/params.json
  params_bak=$(mktemp)
  cp "$params" "$params_bak"
  trap 'cp "$params_bak" "$params"; rm -f "$params_bak"' EXIT
  if curl -fsS --max-time 30 "$BACKEND_URL/seasons/current" -o /tmp/tbb-staging-params.json \
      && [ -s /tmp/tbb-staging-params.json ]; then
    cp /tmp/tbb-staging-params.json "$params"
    echo "Baked live season params from $BACKEND_URL"
  else
    echo "WARNING: could not fetch $BACKEND_URL/seasons/current; using committed fallback params"
  fi

  # Same assembly as pages.yml, but rooted at the staging domain (no /texas-bible-bowl/
  # subpath) and pointed at the staging backend.
  out="$PWD/staging-web/public"
  rm -rf "$out"
  HUGO_PARAMS_BACKENDURL="$BACKEND_URL" "$HUGO" -s site --gc --minify -b "$WEB_URL" -d "$out"
  cp "$params_bak" "$params" && rm -f "$params_bak" && trap - EXIT

  dist=web/build/dist/js/productionExecutable
  test -s "$out/app/index.html" || { echo "ERROR: Hugo did not render the app shell (app/index.html)"; exit 1; }
  rsync -a --exclude=index.html "$dist"/ "$out/app/"
  cp "$out/app/index.html" "$out/app/404.html"

  # Passing the directory makes it both the config source and the (tiny) build context.
  "$FLY" deploy staging-web
}

case "${1:-all}" in
  backend) deploy_backend ;;
  web)     deploy_web ;;
  all)     deploy_backend; deploy_web ;;
  *)       echo "usage: $0 [backend|web|all]"; exit 2 ;;
esac
