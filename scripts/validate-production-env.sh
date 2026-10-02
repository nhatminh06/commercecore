#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: scripts/validate-production-env.sh [--env-file PATH] [--allow-local-hostname]

Validate the small environment contract used by compose.prod.yml.

Options:
  --env-file PATH          Environment file to validate (default: .env.production)
  --allow-local-hostname   Also allow SITE_ADDRESS=:80 or localhost for local verification
  -h, --help               Show this help

The file is parsed as data and is never sourced.
EOF
}

env_file=.env.production
allow_local=false
while (($#)); do
  case "$1" in
    --env-file)
      [[ $# -ge 2 ]] || { echo "--env-file requires a path" >&2; exit 2; }
      env_file=$2
      shift 2
      ;;
    --allow-local-hostname) allow_local=true; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done

[[ -f "$env_file" ]] || { echo "Production environment file not found: $env_file" >&2; exit 1; }

read_value() {
  local key=$1
  awk -v key="$key" '
    /^[[:space:]]*#/ || /^[[:space:]]*$/ { next }
    {
      line=$0
      sub(/^[[:space:]]*export[[:space:]]+/, "", line)
      if (line ~ "^" key "=") {
        sub("^" key "=", "", line)
        sub(/\r$/, "", line)
        print line
      }
    }
  ' "$env_file" | tail -n 1
}

failures=0
fail() { echo "ERROR: $*" >&2; failures=$((failures + 1)); }

required=(SITE_ADDRESS COMMERCECORE_DB_USER COMMERCECORE_DB_PASSWORD PAYMENT_DB_USER PAYMENT_DB_PASSWORD)
for key in "${required[@]}"; do
  value=$(read_value "$key")
  [[ -n "$value" ]] || fail "$key is required and must not be empty"
done

site_address=$(read_value SITE_ADDRESS)
commerce_user=$(read_value COMMERCECORE_DB_USER)
commerce_password=$(read_value COMMERCECORE_DB_PASSWORD)
payment_user=$(read_value PAYMENT_DB_USER)
payment_password=$(read_value PAYMENT_DB_PASSWORD)

if [[ "$allow_local" == true && ( "$site_address" == ":80" || "$site_address" == "localhost" || "$site_address" == "localhost:"* ) ]]; then
  :
elif [[ ! "$site_address" =~ ^([A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?\.)+[A-Za-z]{2,63}$ ]]; then
  fail "SITE_ADDRESS must be a hostname without a scheme or path (use --allow-local-hostname for local :80)"
fi

for entry in "COMMERCECORE_DB_USER:$commerce_user" "PAYMENT_DB_USER:$payment_user"; do
  key=${entry%%:*}
  value=${entry#*:}
  [[ "$value" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || fail "$key must be a simple PostgreSQL role name"
done

check_password() {
  local key=$1 value=$2 lowered
  lowered=${value,,}
  [[ ${#value} -ge 20 ]] || fail "$key must contain at least 20 characters"
  [[ "$value" != *[[:space:]]* ]] || fail "$key must not contain whitespace"
  case "$lowered" in
    password|changeme|change-me|replace-me|postgres|admin|commercecore|payment|example|secret)
      fail "$key contains an unsafe placeholder value"
      ;;
  esac
}
check_password COMMERCECORE_DB_PASSWORD "$commerce_password"
check_password PAYMENT_DB_PASSWORD "$payment_password"
[[ "$commerce_password" != "$payment_password" ]] || fail "database passwords must be different"

profiles=$(read_value SPRING_PROFILES_ACTIVE)
if [[ ",$profiles," == *,dev,* || ",$profiles," == *,local-provider,* ]]; then
  fail "SPRING_PROFILES_ACTIVE must not enable dev or local-provider"
fi
demo_mode=$(read_value NEXT_PUBLIC_DEMO_MODE)
[[ -z "$demo_mode" || "$demo_mode" == "public" ]] || fail "NEXT_PUBLIC_DEMO_MODE must be public when set"
for key in HTTP_PORT HTTPS_PORT; do
  value=$(read_value "$key")
  [[ -z "$value" || ( "$value" =~ ^[0-9]+$ && "$value" -ge 1 && "$value" -le 65535 ) ]] || fail "$key must be a TCP port from 1 to 65535 when set"
done

if ((failures)); then
  echo "Production environment validation failed with $failures error(s)." >&2
  exit 1
fi

echo "Production environment is valid: $env_file"
