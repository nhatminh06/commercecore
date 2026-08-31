#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/../.."

echo "Prerequisite: Docker is running and the current user can access it."
echo "Evidence: repeated/concurrent reconciliation, webhook race, expiry conflict, and real gRPC recovery."
echo "Expected: lookup never reauthorizes; unsafe authorization becomes REQUIRES_REVIEW."

./gradlew --no-daemon :test --rerun-tasks \
  --tests com.commercecore.payment.PaymentReconciliationServiceTest \
  --tests com.commercecore.payment.PaymentReconciliationEndToEndTest \
  --tests com.commercecore.payment.RemotePaymentReconciliationEndToEndTest

echo "PASS: reconciliation recovered safe outcomes and isolated unsafe ones."
