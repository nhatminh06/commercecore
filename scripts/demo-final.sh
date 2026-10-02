#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

if [[ ${1:-} == "--help" || ${1:-} == "-h" ]]; then
  cat <<'EOF'
Usage: scripts/demo-final.sh

Run the local-development-only timeout-after-provider-commit scenario. Requires
docker compose dependencies and CommerceCore from ./gradlew bootRun.
EOF
  exit 0
fi
[[ $# -eq 0 ]] || { echo "Unknown argument: $1" >&2; exit 2; }

for command in curl jq docker; do
  command -v "$command" >/dev/null || { echo "Missing prerequisite: $command" >&2; exit 1; }
done

echo "Prerequisites: docker compose up -d, ./gradlew bootRun, curl, jq."
for endpoint in localhost:8091/actuator/health localhost:8080/actuator/health/readiness; do
  ready=false
  for _ in $(seq 1 60); do
    if curl -fsS --max-time 2 "$endpoint" >/dev/null 2>&1; then ready=true; break; fi
    sleep 1
  done
  [[ "$ready" == true ]] || { echo "Timed out waiting for $endpoint" >&2; exit 1; }
done

run_id="$(date +%s)"
sku="FINAL-LAB-$run_id"

curl -fsS -X POST localhost:8080/api/products \
  -H 'Content-Type: application/json' \
  -d "{\"sku\":\"$sku\",\"name\":\"Final failure lab\",\"price\":30.50,\"initialQuantity\":1}" >/dev/null

cart_json="$(curl -fsS -X POST localhost:8080/api/carts)"
cart_id="$(jq -er '.id' <<<"$cart_json")"

curl -fsS -X PUT "localhost:8080/api/carts/$cart_id/items/$sku" \
  -H 'Content-Type: application/json' -d '{"quantity":1}' >/dev/null

order_json="$(curl -fsS -X POST "localhost:8080/api/carts/$cart_id/checkout" \
  -H "Idempotency-Key: final-lab-$run_id")"
order_id="$(jq -er '.orderId' <<<"$order_json")"

curl -fsS -X POST localhost:8091/api/dev/provider/next-outcome \
  -H 'Content-Type: application/json' -d '{"outcome":"TIMEOUT_AFTER_PROCESSING"}' >/dev/null

payment_json="$(curl -fsS -X POST "localhost:8080/api/orders/$order_id/payment")"
payment_id="$(jq -er '.paymentId' <<<"$payment_json")"
local_before="$(jq -er '.status' <<<"$payment_json")"

provider_row="$(docker compose exec -T payment-postgres psql -U payment_provider -d payment_provider -Atc \
  "SELECT status || '|' || provider_reference FROM provider_payments WHERE provider_request_id = '$payment_id';")"
provider_status="${provider_row%%|*}"
provider_reference="${provider_row#*|}"

test "$local_before" = "UNKNOWN"
test "$provider_status" = "AUTHORIZED"
test -n "$provider_reference"

curl -fsS -X POST "localhost:8080/api/dev/payments/$payment_id/reconcile" >/dev/null
curl -fsS -X POST localhost:8080/api/dev/outbox/publish >/dev/null

order_status=""
for _ in $(seq 1 30); do
  order_status="$(curl -fsS "localhost:8080/api/orders/$order_id" | jq -er '.status')"
  test "$order_status" = "CONFIRMED" && break
  sleep 0.25
done
test "$order_status" = "CONFIRMED"

echo "order_id=$order_id"
echo "payment_id=$payment_id"
echo "local_before=$local_before"
echo "provider_status=$provider_status"
echo "provider_reference=$provider_reference"
echo "order_after=$order_status"
echo "PASS: remote AUTHORIZED/local UNKNOWN recovered through lookup, outbox, and Kafka."
