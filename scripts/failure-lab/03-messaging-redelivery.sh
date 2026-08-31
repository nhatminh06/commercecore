#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/../.."

echo "Prerequisite: Docker is running and the current user can access it."
echo "Evidence: outbox failure windows, real Kafka duplicates, consumer redelivery, and workflow dedup."
echo "Expected: stable event IDs, retryable outbox rows, and one logical business effect."

./gradlew --no-daemon :test --rerun-tasks \
  --tests com.commercecore.outbox.OutboxPublisherTest \
  --tests com.commercecore.kafka.KafkaEventReceiptConsumerTest \
  --tests com.commercecore.checkout.OrderPaymentWorkflowKafkaTest

echo "PASS: at-least-once delivery produced idempotent logical effects."
