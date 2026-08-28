# CommerceCore

CommerceCore is a backend-only e-commerce system built to study correctness under concurrency
and failure: inventory, transactions, checkout, payments, idempotency, events, and recovery.
It is not a storefront project — there is no frontend, and none is planned as part of the core
work.

## Current milestone

**Milestone 12 — Redis Evaluation.**

Redis was evaluated against every plausible current use case (catalog cache, cart cache, checkout
idempotency, inventory locking, reservation expiry, rate limiting, Kafka dedup, reconciliation
batching, sessions) and **skipped** — no measured problem currently justifies it. See
`docs/redis-evaluation.md` for the decision matrix, real local latency measurements, and concrete
revisit triggers. No Redis dependency, Docker Compose service, or Testcontainer was added.

Implemented:

- Product/SKU catalog with a minimal HTTP API.
- Inventory tracking per SKU with database-enforced oversell protection.
- Persistent server-side carts (`docs/cart.md`) — purchase intent, not stock ownership.
- Explicit, persistent inventory reservations with an `ACTIVE → CONFIRMED/RELEASED/EXPIRED`
  lifecycle (`docs/reservations.md`) — concurrency-safe stock *ownership*.
- Checkout that converts a cart into reserved stock and a persistent `PENDING` order
  (`docs/checkout.md`), with persistent, PostgreSQL-backed checkout idempotency
  (`docs/idempotency.md`).
- Fake external payment authorization with explicit `AUTHORIZED` / `FAILED` / `UNKNOWN` state
  (`docs/payments.md`), and idempotent payment webhook processing (`docs/payment-webhooks.md`).
- **A transactional PostgreSQL outbox for committed commerce events** (`docs/outbox.md`).
  CommerceCore writes domain events (`ORDER_CREATED`, `PAYMENT_AUTHORIZED`, `PAYMENT_FAILED`) to
  `outbox_events` in the same transaction as the corresponding business state. A publisher
  provides at-least-once delivery using stable event IDs and PostgreSQL-coordinated retryable
  claims.
- **A real Kafka broker as the outbox's delivery target** (`docs/kafka.md`). `OutboxPublisher`'s
  claim/publish/mark algorithm is unchanged from Milestone 8 — only the sink behind it changed,
  from an in-memory fake to `KafkaDomainEventSink`, selected by a single Spring profile
  (`kafka`). One topic (`commerce.events`), a stable `eventId` preserved across every delivery
  attempt, and a record key of `aggregateId` (per-aggregate partition affinity, not global
  ordering). A technical proof consumer (`KafkaEventReceiptConsumer`) persists a deduplication
  receipt per event ID into a PostgreSQL `kafka_event_receipts` table — proving delivery and
  redelivery are handled correctly.
- **Payment outcome events now drive an idempotent Kafka-backed order workflow** (`docs/order-workflow.md`):
  `AUTHORIZED` payments confirm orders and reservations, while `FAILED` payments cancel orders and
  restore reserved inventory exactly once. A dedicated Kafka consumer group
  (`commercecore-order-workflow`, separate from the technical proof consumer) consumes
  `PAYMENT_AUTHORIZED`/`PAYMENT_FAILED`, verifies the referenced payment's state directly against
  PostgreSQL before trusting the event, and applies the resulting order/reservation transition and
  its own `ORDER_CONFIRMED`/`ORDER_CANCELLED` outbox event atomically with a persistent,
  PostgreSQL-backed workflow receipt — proven safe under physical duplicate Kafka delivery,
  distinct event IDs reporting the same outcome, and consumer failure both before and after commit.
- **CommerceCore can reconcile UNKNOWN payments by querying provider-side state without issuing a
  second authorization** (`docs/reconciliation.md`). Definitive provider outcomes reuse the
  existing payment/outbox/Kafka workflow unchanged; unsafe contradictions — a provider outcome that
  disagrees with what CommerceCore already recorded, or an `AUTHORIZED` payment whose reservation
  has already expired or been released — are persisted as durable `payment_reconciliation_cases`
  evidence (`OPEN`/`RESOLVED`/`REQUIRES_REVIEW`) instead of being guessed through automatically.
  `PaymentProvider.lookup` is read-only, proven by an unchanged authorization-call count across
  every reconciliation test, including 20 repeated and 20 concurrent reconciliation attempts and a
  genuine webhook-vs-reconciliation race.

**A real payment provider delivers webhooks at-least-once, not exactly-once** — the same event
can arrive once, many times, or concurrently, including after CommerceCore already resolved
things another way. `POST /api/webhooks/payments` is built for that: the same provider event ID
delivered any number of times produces one logical payment transition, proven under real 20-way
concurrent duplicate delivery, not just sequential retries. A different, legitimate concern is
also handled: two *different* event IDs can validly report the same outcome for the same
payment (a provider re-notifying under a new event ID), and that stays safe too — state-level
idempotency (`AUTHORIZED + AUTHORIZED` is a no-op) is a second, independent layer from event-ID
deduplication. See `docs/payment-webhooks.md` and `PaymentWebhookConcurrencyTest` for the
evidence.

**A payment's committed outcome now drives the order's final state, asynchronously via Kafka —
not synchronously inside payment initiation.** `AUTHORIZED` moves the order `PENDING → CONFIRMED`
and every reservation `ACTIVE → CONFIRMED` (inventory is not touched again — it was already
decremented at checkout time). `FAILED` moves the order `PENDING → CANCELLED`, releases every
reservation, and restores their quantities to `inventory.available_quantity` exactly once, even
under repeated/duplicate delivery of the same or equivalent payment event. See
`docs/order-workflow.md`.

**Checkout is the operation that turns cart intent into inventory ownership.** It reads the
cart's current lines, snapshots each product's *current* price (not a cart-stored price — carts
store none), reserves every line via the same reservation primitive from Milestone 3, and creates
one order — all in a single database transaction. If any line can't be reserved, the whole
checkout rolls back: no partial reservations, no order, no idempotency mapping. See
`docs/checkout.md`. **Repeated checkout with the same `Idempotency-Key` returns the same order
and does not reserve stock twice** — enforced in PostgreSQL via a transaction-scoped advisory
lock, not just a unique constraint. See `docs/idempotency.md`.

**The transactional outbox solves the dual-write problem**: committing a business fact (an order,
a payment transition) and losing the event describing it, or publishing an event for a fact that
then rolls back. The event row is written in the exact same transaction as the fact it describes
— `ORDER_CREATED` alongside the order/items/reservations, `PAYMENT_AUTHORIZED`/`PAYMENT_FAILED`
alongside the payment state change, whether that change came from a synchronous provider call or
a later webhook. Publication itself is honestly **at-least-once, not exactly-once**: a message
being accepted by Kafka and the acknowledgment then being lost (crash, network drop) means the
retry that follows delivers the exact same stable event ID a second time as a second, distinct
Kafka record — proven, not hidden. See `docs/outbox.md` and
`OutboxPublisherTest`/`OutboxPublisherConcurrencyTest` for the evidence.

**Kafka delivery is likewise at-least-once on its own hop, not exactly-once**, and the two hops
(outbox → Kafka, Kafka → consumer) are independent — neither combines with the other into an
end-to-end exactly-once guarantee. Correctness comes from consumer-side idempotency: the same
`eventId` processed once, twice, or a thousand times produces exactly one
`kafka_event_receipts` row, proven under a forced duplicate physical Kafka delivery of the same
event ID and under consumer-side failure injected both before and after the receipt commits. See
`docs/kafka.md` and `KafkaDomainEventSinkTest`/`KafkaEventReceiptConsumerTest`/
`OutboxKafkaEndToEndTest` for the evidence.

**Automatic background reservation expiration is still not implemented.** Expiration itself
returns stock correctly (Milestone 3), and both the order workflow and payment reconciliation now
*notice* an expired or released reservation before acting on a payment outcome for it — refusing to
confirm the order rather than overselling, and instead persisting durable `REQUIRES_REVIEW`
evidence — but neither automatically resolves the situation into a final order state or repairs
inventory ownership; the order is left `PENDING`. See `docs/checkout.md` → Known limitations,
`docs/order-workflow.md` → Current limitations, and `docs/reconciliation.md`.

Milestone 1's invariant still holds: concurrent inventory consumption cannot oversell stock in
PostgreSQL — see `docs/inventory.md` and
`src/test/java/com/commercecore/inventory/InventoryConcurrencyTest.java`.

## Architecture

Single modular monolith, single PostgreSQL database:

```text
HTTP
 ↓
Spring Boot
 ↓
Catalog / Inventory / Cart / Reservation / Checkout / Payment
 ↓                                            ↓
PostgreSQL                        Fake payment provider (in-process)
 ↓
outbox_events
 ↓
OutboxPublisher  →  Kafka (commerce.events)  ─┬─→  KafkaEventReceiptConsumer     → kafka_event_receipts
                                               └─→  OrderPaymentWorkflowConsumer → order_workflow_event_receipts
                                                     ↓
                                                     Order / InventoryReservation → ORDER_CONFIRMED / ORDER_CANCELLED

payments (UNKNOWN)
 ↓
PaymentReconciliationService  →  PaymentProvider.lookup (read-only)  →  PaymentReconciliationApplier
                                                                          ↓
                                                            PaymentTransitionService + payment_reconciliation_cases
```

## Stack

Java 21, Spring Boot, Gradle, PostgreSQL, Kafka, Flyway, JUnit 5, Testcontainers, Docker Compose.

## Running PostgreSQL and Kafka (development)

```bash
docker compose up -d
```

This starts a single PostgreSQL 16 instance on `localhost:5432` (db/user/password:
`commercecore`) and a single Kafka broker (KRaft mode, no ZooKeeper) on `localhost:29092`. Not
used by tests — tests start their own isolated PostgreSQL and Kafka containers via Testcontainers.
`./gradlew bootRun` activates the `kafka` Spring profile by default.

## Running the application

```bash
./gradlew bootRun
```

## Running tests

```bash
./gradlew test
```

Integration tests use Testcontainers to start real PostgreSQL and Kafka containers automatically;
no manual `docker compose up` is required for `./gradlew test` to work.

## API (development/test surface — no authentication)

```http
POST   /api/products
GET    /api/products/{sku}
GET    /api/products/{sku}/inventory
POST   /api/products/{sku}/inventory/consume

POST   /api/carts
GET    /api/carts/{cartId}
PUT    /api/carts/{cartId}/items/{sku}
DELETE /api/carts/{cartId}/items/{sku}

POST   /api/reservations
GET    /api/reservations/{id}
POST   /api/reservations/{id}/confirm
POST   /api/reservations/{id}/release

POST   /api/carts/{cartId}/checkout
GET    /api/orders/{orderId}

POST   /api/orders/{orderId}/payment
GET    /api/orders/{orderId}/payment

POST   /api/webhooks/payments
```

Development-only (not a real production API — see `docs/payments.md`, `docs/outbox.md`,
`docs/kafka.md`, `docs/order-workflow.md`, and `docs/reconciliation.md`):

```http
POST   /api/dev/payment-provider/next-outcome
POST   /api/dev/outbox/publish
POST   /api/dev/outbox/sink/next-outcome   # only exists outside the "kafka" profile
POST   /api/dev/payments/{paymentId}/reconcile
POST   /api/dev/reconciliation/run?limit=25
```

Example:

```bash
curl -X POST localhost:8080/api/products \
  -H 'Content-Type: application/json' \
  -d '{"sku":"WIDGET-1","name":"Widget","price":9.99,"initialQuantity":10}'

curl localhost:8080/api/products/WIDGET-1/inventory

curl -X POST localhost:8080/api/products/WIDGET-1/inventory/consume \
  -H 'Content-Type: application/json' \
  -d '{"quantity":1}'

curl -X POST localhost:8080/api/carts

curl -X PUT localhost:8080/api/carts/{cartId}/items/WIDGET-1 \
  -H 'Content-Type: application/json' \
  -d '{"quantity":3}'

curl localhost:8080/api/carts/{cartId}

curl -X POST localhost:8080/api/reservations \
  -H 'Content-Type: application/json' \
  -d '{"sku":"WIDGET-1","quantity":2}'

curl -X POST localhost:8080/api/reservations/{reservationId}/release

curl -X POST localhost:8080/api/carts/{cartId}/checkout \
  -H 'Idempotency-Key: checkout-demo-001'

curl localhost:8080/api/orders/{orderId}

curl -X POST localhost:8080/api/orders/{orderId}/payment

curl localhost:8080/api/orders/{orderId}/payment

curl -X POST localhost:8080/api/webhooks/payments \
  -H 'Content-Type: application/json' \
  -d '{"eventId":"evt-demo-001","type":"PAYMENT_AUTHORIZED","providerRequestId":"{paymentId}","providerReference":"pay_abc123"}'

curl -X POST localhost:8080/api/dev/outbox/publish
```

Inspecting the real Kafka topic and receipt table (with `docker compose up -d` running):

```bash
docker exec commercecore-kafka-1 /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic commerce.events --from-beginning \
  --property print.key=true --timeout-ms 8000

docker exec commercecore-postgres-1 psql -U commercecore -d commercecore \
  -c "SELECT event_id, event_type, aggregate_id, consumed_at FROM kafka_event_receipts;"
```

Reconciling an ambiguous payment (with `docker compose up -d` and `./gradlew bootRun` running):

```bash
curl -X POST localhost:8080/api/dev/payment-provider/next-outcome \
  -H 'Content-Type: application/json' -d '{"outcome":"TIMEOUT_AFTER_PROCESSING"}'

curl -X POST localhost:8080/api/orders/{orderId}/payment

curl -X POST localhost:8080/api/dev/payments/{paymentId}/reconcile

curl -X POST localhost:8080/api/dev/outbox/publish

curl localhost:8080/api/orders/{orderId}
```

## Invariants currently demonstrated

- `inventory.available_quantity` never goes negative under concurrent consumption. See
  `docs/inventory.md` for the mechanism and test evidence.
- Cart quantity is independent of inventory availability: a cart may hold more of a SKU than is
  currently in stock, and adding to a cart never changes `available_quantity`. See
  `docs/cart.md`.
- A reservation atomically decrements `available_quantity`, and concurrent release/confirm
  attempts on the same reservation cannot double-restore or double-consume stock. See
  `docs/reservations.md`.
- Checkout is all-or-nothing: a multi-item checkout either reserves every line and creates one
  order, or leaves no trace at all — proven by an insufficient-stock rollback test and a
  concurrent-last-unit-checkout test. Order line prices are snapshotted at checkout time and are
  unaffected by later catalog price changes. See `docs/checkout.md`.
- Repeated checkout with the same `Idempotency-Key` returns the same order and does not reserve
  stock twice — including under 20-way concurrent duplicate requests and concurrent cross-cart
  key reuse, both proven against real PostgreSQL. See `docs/idempotency.md`.
- One CommerceCore payment exists per order (a database constraint), and duplicate payment
  initiation — sequential or 20-way concurrent — results in exactly one call to the payment
  provider, never one per request. A provider timeout is recorded as `UNKNOWN`, never `FAILED`,
  and the fake provider's own ledger proves the provider may have actually processed the request
  even when CommerceCore couldn't observe the result. See `docs/payments.md`.
- A provider event ID delivered any number of times — sequentially or under 20-way concurrency —
  produces exactly one event receipt and one logical payment transition. `UNKNOWN` payments can be
  resolved to `AUTHORIZED`/`FAILED` by authoritative webhook evidence; already-terminal payments
  reject contradicting events (`AUTHORIZED → FAILED`, `FAILED → AUTHORIZED`) as explicit conflicts
  rather than silently overwriting known truth. See `docs/payment-webhooks.md`.
- `ORDER_CREATED`, `PAYMENT_AUTHORIZED`, and `PAYMENT_FAILED` outbox events commit atomically with
  the business fact they describe — never present without it, never absent when it exists.
  Concurrent publisher workers coordinate purely through PostgreSQL (`FOR UPDATE SKIP LOCKED`),
  publish every pending event exactly once each under real concurrency, and a stale claim from a
  crashed worker becomes reclaimable after its lease expires. See `docs/outbox.md`.
- A domain event published to Kafka carries the same `eventId` as its `outbox_events` row across
  every delivery attempt, never a freshly generated one. The proof consumer's PostgreSQL-backed
  `kafka_event_receipts` table produces exactly one receipt per `eventId` regardless of how many
  times the same physical Kafka record is delivered, proven under forced duplicate delivery and
  under consumer failure injected both before and after the receipt row commits. See
  `docs/kafka.md`.
- An `AUTHORIZED` payment confirms its order and every reservation exactly once, and a `FAILED`
  payment cancels its order and restores every reservation's inventory exactly once — proven under
  20 physical duplicate Kafka deliveries of the same event, under two distinct event IDs reporting
  the same outcome, and under consumer failure injected after the workflow transaction commits but
  before the Kafka offset is acknowledged. A payment event whose PostgreSQL state doesn't match its
  claimed outcome, and a conflicting terminal order transition, are rejected without mutating order,
  reservation, or inventory state; an `AUTHORIZED` payment arriving after its reservation already
  expired/released instead commits durable `REQUIRES_REVIEW` evidence and acknowledges the event,
  rather than retrying an unsafe business conflict forever. See `docs/order-workflow.md`.
- An `UNKNOWN` payment can be repaired by querying — never re-authorizing — the provider.
  Authorization call count stays unchanged across 20 repeated and 20 concurrent reconciliation
  attempts, and across a genuine race against an authoritative webhook, while still producing
  exactly one payment transition and one outcome outbox event. A provider outcome that contradicts
  what CommerceCore already recorded (`AUTHORIZED`↔`FAILED` in either direction, or a provider
  reference mismatch) is never silently reversed — it becomes a durable `REQUIRES_REVIEW` case. An
  `AUTHORIZED` provider truth discovered after the reservation is already lost is still recorded
  (the customer really did pay) without confirming the order or re-reserving stock. See
  `docs/reconciliation.md`.

## Intentionally unsupported (not yet built)

No authentication, real payment processing, real provider integration, Kubernetes, or
microservices. No Redis — deliberately evaluated and skipped, not merely unbuilt; see
`docs/redis-evaluation.md` for why and what would change that. No webhook signature/authenticity
verification — the webhook endpoint is a
development-only surface for the fake provider's events, not production-secure. No automatic
scheduling of outbox publication, and no event retention/cleanup policy on `outbox_events`. No cart
expiration and no price locking at cart time. Reservations are not tied to carts outside of
checkout. No automatic background expiration scheduler — expiration is proven as an explicit,
callable transition, not something that happens on its own yet, and it is not order-aware.
Idempotency is checkout-specific (no generic replay framework, no TTL/cleanup on idempotency rows).
`UNKNOWN` payments still do not drive the order workflow directly — there is no `PAYMENT_UNKNOWN`
domain event — but can now be resolved by explicit or batch reconciliation querying the provider
(`docs/reconciliation.md`); nothing schedules reconciliation automatically yet. An `AUTHORIZED`
payment discovered after its reservation already expired or was released is recorded as durable
`REQUIRES_REVIEW` evidence, not automatically repaired — no automatic re-reservation, no automatic
refund; that policy decision is deliberately deferred. No automatic payment retry, no refund
workflow, no shipping/fulfillment, no operator UI for `REQUIRES_REVIEW` cases (SQL/API inspection
only).

**Kafka is real, but narrow in scope**: a single local broker (no cluster, no failover), one topic,
no Schema Registry, no Avro/Protobuf, no Kafka Streams, no generic saga/workflow framework. The
order workflow consumer handles exactly two event types
(`PAYMENT_AUTHORIZED`/`PAYMENT_FAILED`); no other business consumer exists yet. No client-facing
API can mutate order status directly — order state is driven only by payment outcome. Single
application, single PostgreSQL database — Kafka is an added infrastructure dependency, not a step
toward microservices.
