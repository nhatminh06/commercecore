#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/../.."

echo "Prerequisite: Docker is running and the current user can access it."
echo "Evidence: real PostgreSQL inventory, reservation, and checkout-idempotency races."
echo "Expected: no oversell, one reservation transition, and one logical checkout."

./gradlew --no-daemon :test --rerun-tasks \
  --tests com.commercecore.inventory.InventoryConcurrencyTest \
  --tests com.commercecore.reservation.ReservationConcurrencyTest \
  --tests com.commercecore.checkout.CheckoutIdempotencyConcurrencyTest

echo "PASS: PostgreSQL concurrency invariants held."
