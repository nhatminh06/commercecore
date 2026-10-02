#!/usr/bin/env bash
set -euo pipefail

if [[ ${1:-} == "--help" || ${1:-} == "-h" ]]; then
  echo "Usage: scripts/stop-production.sh [--env-file PATH] [--project-name NAME]"
  echo "Stops production Compose containers without deleting named volumes."
  exit 0
fi

env_file=.env.production
project_name=
while (($#)); do
  case "$1" in
    --env-file) [[ $# -ge 2 ]] || exit 2; env_file=$2; shift 2 ;;
    --project-name) [[ $# -ge 2 ]] || exit 2; project_name=$2; shift 2 ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
done

cd "$(dirname "$0")/.."
compose_args=(--env-file "$env_file" -f compose.prod.yml)
[[ -z "$project_name" ]] || compose_args=(-p "$project_name" "${compose_args[@]}")
docker compose "${compose_args[@]}" stop
