#!/usr/bin/env bash
# Deploy the staging stack (docs/staging.md). Prod deploys are untouched by this script.
# Runs locally or in CI (deploy-staging.yml auto-runs `backend` + `web` on push to main).
#
#   tools/deploy-staging.sh backend   # :server:test, then fly deploy -c fly.staging.toml
#   tools/deploy-staging.sh web       # build web dist + Hugo site, then deploy staging-web
#   tools/deploy-staging.sh all       # both, backend first
set -euo pipefail
cd "$(dirname "$0")/.."

# CI installs the binary as `flyctl` (superfly/flyctl-actions); Mark's machine has `fly`.
FLY=$(command -v fly || command -v flyctl || echo /home/linuxbrew/.linuxbrew/bin/fly)

deploy_backend() {
  ./gradlew :server:test
  "$FLY" deploy -c fly.staging.toml
}

case "${1:-all}" in
  backend) deploy_backend ;;
  web)     tools/deploy-web.sh staging ;;
  all)     deploy_backend; tools/deploy-web.sh staging ;;
  *)       echo "usage: $0 [backend|web|all]"; exit 2 ;;
esac
