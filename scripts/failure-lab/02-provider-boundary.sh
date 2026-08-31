#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/../.."

echo "Prerequisite: Docker is running and the current user can access it."
echo "Evidence: real TCP gRPC, provider PostgreSQL, deadline ambiguity, restart, and concurrency."
echo "Expected: durable provider truth, stable references, and one row per request identity."

./gradlew --no-daemon :payment-service:test --rerun-tasks \
  --tests com.commercecore.paymentservice.PaymentProviderGrpcIntegrationTest

echo "PASS: remote provider invariants held."
