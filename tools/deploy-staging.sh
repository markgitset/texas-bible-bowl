#!/usr/bin/env bash
# Deploy the staging stack (docs/staging.md). Prod deploys are untouched by this script.
# Runs locally or in CI (deploy-staging.yml auto-runs `backend` + `web` on push to main).
#
#   tools/deploy-staging.sh backend   # :server:test + :server:installDist, then fly deploy -c fly.staging.toml
#   tools/deploy-staging.sh web       # build web dist + Hugo site, then deploy staging-web
#   tools/deploy-staging.sh all       # both, backend first
set -euo pipefail
cd "$(dirname "$0")/.."

# CI installs the binary as `flyctl` (superfly/flyctl-actions); Mark's machine has `fly`.
FLY=$(command -v fly || command -v flyctl || echo /home/linuxbrew/.linuxbrew/bin/fly)

deploy_backend() {
  # installDist here, not in the Dockerfile: the image just COPYs the dist (see
  # server/Dockerfile for why the in-image Gradle build was removed).
  ./gradlew :server:test :server:installDist
  # --local-only: build the copy-only image with the local Docker daemon and push it —
  # Fly's remote depot builder wedges and then deploys time out waiting to connect to it.
  # Needs Docker running (CI runners and the dev machine both have it); if Docker is ever
  # unavailable, drop the flag to fall back to Fly's remote builder.
  "$FLY" deploy --local-only -c fly.staging.toml
}

case "${1:-all}" in
  backend) deploy_backend ;;
  web)     tools/deploy-web.sh staging ;;
  all)     deploy_backend; tools/deploy-web.sh staging ;;
  *)       echo "usage: $0 [backend|web|all]"; exit 2 ;;
esac
