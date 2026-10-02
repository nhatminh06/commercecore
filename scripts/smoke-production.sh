#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: scripts/smoke-production.sh [--base-url URL] [--timeout SECONDS]

Run read-only and public-safe checks against a production-mode deployment.

Options:
  --base-url URL     Public origin to test (default: http://localhost)
  --timeout SECONDS  Maximum time to wait for readiness (default: 180)
  -h, --help         Show this help
EOF
}

base_url=http://localhost
timeout=180
while (($#)); do
  case "$1" in
    --base-url) [[ $# -ge 2 ]] || { echo "--base-url requires a URL" >&2; exit 2; }; base_url=${2%/}; shift 2 ;;
    --timeout) [[ $# -ge 2 ]] || { echo "--timeout requires seconds" >&2; exit 2; }; timeout=$2; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done

command -v curl >/dev/null || { echo "Missing prerequisite: curl" >&2; exit 1; }
[[ "$base_url" =~ ^https?://[^/]+(:[0-9]+)?$ ]] || { echo "Invalid --base-url: $base_url" >&2; exit 2; }
[[ "$timeout" =~ ^[1-9][0-9]*$ ]] || { echo "--timeout must be a positive integer" >&2; exit 2; }

deadline=$((SECONDS + timeout))
until health=$(curl --silent --show-error --fail --max-time 5 "$base_url/healthz" 2>/dev/null); do
  if ((SECONDS >= deadline)); then
    echo "Timed out waiting for $base_url/healthz" >&2
    exit 1
  fi
  sleep 2
done
[[ "$health" == *'"status":"UP"'* ]] || { echo "Readiness endpoint did not report UP" >&2; exit 1; }

curl --silent --show-error --fail --max-time 10 "$base_url/" >/dev/null
catalog=$(curl --silent --show-error --fail --max-time 10 "$base_url/api/commercecore/products")
[[ "$catalog" == *'DEMO-KEYBOARD'* && "$catalog" == *'DEMO-DOCK'* && "$catalog" == *'DEMO-MUG'* ]] || {
  echo "Catalog did not contain the deterministic portfolio products" >&2
  exit 1
}

expect_blocked() {
  local method=$1 path=$2 status
  status=$(curl --silent --output /dev/null --write-out '%{http_code}' --max-time 10 -X "$method" "$base_url$path")
  [[ "$status" == "404" ]] || { echo "Expected $method $path to be blocked with 404, got $status" >&2; exit 1; }
}

expect_blocked POST /api/commercecore/products
expect_blocked POST /api/commercecore/dev/outbox/publish
expect_blocked POST /api/commercecore/dev/experiments/inventory-contention
expect_blocked POST /api/commercecore/webhooks/payments
expect_blocked POST /api/payment-provider/dev/provider/next-outcome

echo "Production smoke checks passed: $base_url"
