#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: scripts/verify-production.sh [--env-file PATH] [--base-url URL] [--project-name NAME] [--skip-build]

Validate configuration, build and start production Compose, wait for container
health, and run the public-safe smoke checks. Volumes are never removed.

For local HTTP verification, set SITE_ADDRESS=:80 in the env file.

Options:
  --env-file PATH  Environment file (default: .env.production)
  --base-url URL   Origin passed to smoke-production.sh (default: http://localhost)
  --project-name NAME  Optional isolated Compose project name
  --skip-build     Reuse existing images
  -h, --help       Show this help
EOF
}

env_file=.env.production
base_url=http://localhost
skip_build=false
project_name=
while (($#)); do
  case "$1" in
    --env-file) [[ $# -ge 2 ]] || { echo "--env-file requires a path" >&2; exit 2; }; env_file=$2; shift 2 ;;
    --base-url) [[ $# -ge 2 ]] || { echo "--base-url requires a URL" >&2; exit 2; }; base_url=$2; shift 2 ;;
    --project-name) [[ $# -ge 2 ]] || { echo "--project-name requires a name" >&2; exit 2; }; project_name=$2; shift 2 ;;
    --skip-build) skip_build=true; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done

cd "$(dirname "$0")/.."
command -v docker >/dev/null || { echo "Missing prerequisite: docker" >&2; exit 1; }
docker compose version >/dev/null

scripts/validate-production-env.sh --env-file "$env_file" --allow-local-hostname
compose_args=(--env-file "$env_file" -f compose.prod.yml)
[[ -z "$project_name" ]] || compose_args=(-p "$project_name" "${compose_args[@]}")
docker compose "${compose_args[@]}" config --quiet
if [[ "$skip_build" == false ]]; then
  docker compose "${compose_args[@]}" build
fi
docker compose "${compose_args[@]}" up -d --wait --wait-timeout 240
scripts/smoke-production.sh --base-url "$base_url"

echo "Production verification passed. The stack remains running and volumes were preserved."
echo "Stop it with: scripts/stop-production.sh --env-file $env_file${project_name:+ --project-name $project_name}"
