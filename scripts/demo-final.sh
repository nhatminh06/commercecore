#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

for command in curl jq docker; do
  command -v "$command" >/dev/null || { echo "Missing prerequisite: $command" >&2; exit 1; }
done

echo "Prerequisites: docker compose up -d, ./gradlew bootRun, curl, jq."
curl -fsS localhost:8091/actuator/health >/dev/null
curl -fsS localhost:8080/api/products/__failure_lab_probe__ >/dev/null 2>&1 || true

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
