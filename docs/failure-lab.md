# Final failure lab

The lab maps each correctness claim to a reproducible failure, observed evidence, and the boundary
of the guarantee. Tests use real PostgreSQL, real Kafka, or real localhost TCP gRPC whenever those
semantics matter. They do not prove every possible thread schedule or production availability.

## Portfolio evidence

| Scenario | Expected | Observed |
|---|---|---|
| 100 buyers / stock 10 | 10 winners, 90 failures, stock 0 | 10 / 90 / 0 |
| Last item race | one winner, stock 0 | 1 winner, 1 failure, stock 0 |
| Double release of 2 from stock 5 | restore once | final RELEASED, stock 5 |
| Confirm/release race | one legal terminal state | CONFIRMED/stock 3 or RELEASED/stock 5 |
| Checkout partial failure | rollback all | stock 5/0; no order, reservation, mapping, or outbox event |
| Same checkout key x20 | one order | sequential and concurrent: one ID, one reservation, stock 8 |
| Same provider request x20 | one provider fact | one row, one logical authorization, one reference |
| Same provider ID/different amounts | reject conflict | one row; one success; one `FAILED_PRECONDITION` |
| gRPC timeout after processing | remote AUTH, local UNKNOWN | provider AUTHORIZED/reference present; caller deadline; local UNKNOWN |
| Provider restart | truth survives | lookup/retry returned same status/reference; one row |
| Duplicate webhook x20 | one transition | one receipt and one payment outcome event |
| Outbox accept-before-mark | duplicate same event ID | sink count 1 while unpublished, then count 2 with identical ID |
| Kafka duplicate record | one logical receipt | at least 2 physical records, 1 receipt/effect |
| Duplicate FAILED workflow | restore once | CANCELLED, RELEASED, stock restored once, one cancellation event |
| Webhook/reconciliation race | one outcome | AUTHORIZED/reference stable, one payment outcome event |
| AUTHORIZED after expiry | review, no oversell | AUTHORIZED + REQUIRES_REVIEW; PENDING/EXPIRED; stock restored |

## Failure matrix

| Failure | Invariant | Evidence | Action |
|---|---|---|---|
| Inventory oversell | stock never negative | `InventoryConcurrencyTest` | reused |
| Reservation double release | restore at most once | `ReservationConcurrencyTest` | reused |
| Reservation confirm/release | one terminal state | `ReservationConcurrencyTest` | reused |
| Checkout partial failure | atomic rollback | `CheckoutApiTest`, `CheckoutOutboxTest` | reused |
| Checkout duplicate request | one logical order | `CheckoutIdempotencyTest` | reused |
| Concurrent checkout duplicate | PostgreSQL-coordinated single execution | `CheckoutIdempotencyConcurrencyTest` | reused |
| Provider duplicate authorization | one provider row/reference | `PaymentProviderGrpcIntegrationTest` | reused |
| Provider amount conflict | identity binds amount | `PaymentProviderGrpcIntegrationTest` | reused |
| Payment response timeout | remote truth may differ from observation | provider and remote E2E tests | reused |
| Payment Service down | commerce state remains valid | `RemotePaymentUnavailableIntegrationTest` | reused |
| Payment Service restart | provider truth persists | provider integration test | reused |
| Duplicate webhook | one receipt/transition | webhook API/concurrency tests | reused |
| Outbox fail-before-accept | event remains retryable | `OutboxPublisherTest` | reused |
| Outbox accept-before-mark | stable-ID duplicate is visible | `OutboxPublisherTest` | reused |
| Kafka duplicate record | one logical receipt | `KafkaEventReceiptConsumerTest` | reused |
| Consumer pre-commit failure | no receipt/ack; retry succeeds | `KafkaEventReceiptConsumerTest` | reused |
| Consumer post-commit/pre-ack failure | duplicate becomes no-op | receipt and workflow Kafka tests | reused |
| Duplicate AUTHORIZED workflow | confirm once | `OrderPaymentWorkflowKafkaTest` | reused |
| Duplicate FAILED workflow | cancel/restore once | `OrderPaymentWorkflowKafkaTest` | reused |
| Repeated/concurrent reconciliation | one transition, no authorize | `PaymentReconciliationServiceTest` | reused |
| Webhook/reconciliation race | one outcome event | `PaymentReconciliationServiceTest` | reused |
| Authorization after expiry | durable review, no stock mutation | reconciliation/workflow tests | reused |
| Development controls in normal profile | no `/api/dev/*` beans | `DevelopmentEndpointProfileTest` | added |

## Scenario notes

### PostgreSQL concurrency and rollback

Run:

```bash
scripts/failure-lab/01-postgres-concurrency.sh
./gradlew :test --tests com.commercecore.checkout.CheckoutApiTest \
  --tests com.commercecore.outbox.CheckoutOutboxTest
```

The inventory test starts 100 calls at one barrier against 10 units. The reservation test locks
one row while competing release/confirm transactions decide a legal terminal state. Checkout's
single transaction rolls back prior line reservations when a later SKU has insufficient stock.

### Remote provider and deadline ambiguity

Run:

```bash
scripts/failure-lab/02-provider-boundary.sh
./gradlew :test --tests com.commercecore.payment.RemotePaymentUnavailableIntegrationTest
```

The server commits `AUTHORIZED` or `DECLINED` before sleeping past the client deadline. Therefore
`DEADLINE_EXCEEDED` proves only that the caller stopped waiting. It does not prove remote rollback.
Provider PostgreSQL remembers the result across server restart and serializes same-ID calls.

### Webhooks, outbox, and Kafka

Run:

```bash
./gradlew :test --tests com.commercecore.payment.PaymentWebhookConcurrencyTest
scripts/failure-lab/03-messaging-redelivery.sh
```

Inbound provider event identity and outbound domain event identity are separate layers. The outbox
can publish a stable event more than once if the sink accepts it before the local published marker
commits. Kafka consumers therefore claim their own durable receipts inside the same transaction as
their side effects and acknowledge offsets only afterward.

### Reconciliation and unsafe business conflict

Run:

```bash
scripts/failure-lab/04-reconciliation-safety.sh
```

Reconciliation performs `LookupPayment`, not `AuthorizePayment`. A webhook and reconciliation may
race, but payment row locking makes one transition win and the other idempotent. If an authorization
is discovered after reservation expiry, the system records the financial truth but refuses to
invent stock ownership: payment `AUTHORIZED`, case `REQUIRES_REVIEW`, order `PENDING`, reservation
`EXPIRED`, inventory unchanged from the restored value.

## Real-process demonstration

```bash
docker compose up -d
./gradlew bootRun
# in another terminal
scripts/demo-final.sh
```

The script prints the real order/payment IDs, verifies local `UNKNOWN` against remote `AUTHORIZED`,
reconciles, publishes the outbox, waits for Kafka workflow confirmation, and exits nonzero if an
obvious invariant fails.

The final Milestone 14 run observed:

| Real-process scenario | Evidence |
|---|---|
| Success | order `ac48bf19-da04-4eb6-8e5a-2e47829a567f`, payment `9a95c0cb-dc8d-422f-815b-030426fe2e9e`, provider reference `pay_c3c2646f-5537-46d6-8356-7a4379c506bc`, final order `CONFIRMED` |
| Timeout after processing | order `41a67627-3d67-4199-ab53-5c45cc2885e0`, payment `af6e2971-c2ad-4203-ad52-f39a83ef8030`, local `UNKNOWN`, remote `AUTHORIZED`, reference `pay_cd7c8adb-7e5e-4348-a583-06d4b622813c`, final order `CONFIRMED` |
| Decline | order `c1b7990e-312c-49ca-b0aa-b9d89dec4aa1`, payment `0cf8dfd2-c3d9-4406-96e5-cf7b72206785`, stock `5 -> 3 -> 5`, payment `FAILED`, order `CANCELLED` |
| Provider restart | timeout payment lookup returned `AUTHORIZED` with the same reference; provider row count remained 1; reconciliation attempt count became 2 |
| Physical Kafka duplicate | event `33c7f5a7-8bfe-4dc0-b0db-7eab8e4528c7` appended twice; total topic end offset `14 -> 16`; proof receipts 1; workflow receipts 1 |

## Repeat stability runs

The scripts are intentionally grouped so the high-value races can be repeated without running the
entire suite:

```bash
for run in 1 2 3; do scripts/failure-lab/01-postgres-concurrency.sh; done
for run in 1 2 3; do scripts/failure-lab/02-provider-boundary.sh; done
for run in 1 2 3; do scripts/failure-lab/03-messaging-redelivery.sh; done
for run in 1 2 3; do scripts/failure-lab/04-reconciliation-safety.sh; done
```

This is repeated empirical evidence, not an exhaustive proof of every possible schedule.

## Database inspection

CommerceCore:

```bash
docker compose exec postgres psql -U commercecore -d commercecore -c \
  "SELECT sku, available_quantity FROM inventory ORDER BY sku;"
docker compose exec postgres psql -U commercecore -d commercecore -c \
  "SELECT id, sku, quantity, status, order_id FROM inventory_reservations ORDER BY expires_at DESC;"
docker compose exec postgres psql -U commercecore -d commercecore -c \
  "SELECT id, status, total_amount, created_at FROM orders ORDER BY created_at DESC;"
docker compose exec postgres psql -U commercecore -d commercecore -c \
  "SELECT id, order_id, amount, status, provider_reference FROM payments ORDER BY created_at DESC;"
docker compose exec postgres psql -U commercecore -d commercecore -c \
  "SELECT idempotency_key, cart_id, order_id FROM checkout_idempotency ORDER BY created_at DESC;"
docker compose exec postgres psql -U commercecore -d commercecore -c \
  "SELECT provider_event_id, payment_id, event_type FROM payment_webhook_events ORDER BY received_at DESC;"
docker compose exec postgres psql -U commercecore -d commercecore -c \
  "SELECT id, event_type, aggregate_id, published_at, attempt_count FROM outbox_events ORDER BY created_at DESC;"
docker compose exec postgres psql -U commercecore -d commercecore -c \
  "SELECT event_id, event_type, aggregate_id FROM kafka_event_receipts ORDER BY consumed_at DESC;"
docker compose exec postgres psql -U commercecore -d commercecore -c \
  "SELECT event_id, event_type, payment_id, order_id FROM order_workflow_event_receipts ORDER BY processed_at DESC;"
docker compose exec postgres psql -U commercecore -d commercecore -c \
  "SELECT payment_id, status, last_provider_status, reason, attempt_count FROM payment_reconciliation_cases ORDER BY created_at DESC;"
```

Payment Service:

```bash
docker compose exec payment-postgres psql -U payment_provider -d payment_provider -c \
  "SELECT provider_request_id, amount, status, provider_reference, created_at FROM provider_payments ORDER BY created_at DESC;"
```

Kafka:

```bash
docker compose exec kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 --describe --topic commerce.events
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic commerce.events --from-beginning \
  --property print.key=true --timeout-ms 8000
docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 --describe --group commercecore-proof-consumer
docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 --describe --group commercecore-order-workflow
```

## Honest limitations

The lab does not establish exactly-once transport, global Kafka ordering, multi-broker availability,
multi-region durability, zero downtime, payment security, PCI compliance, or exhaustive schedule
verification. The provider is simulated; gRPC is plaintext and unauthenticated; reconciliation and
outbox publication are manually triggered; Kafka is one local replication-factor-1 broker; there
is no automatic refund, shipping, fulfillment, operator UI, Redis, Kubernetes, or frontend.
