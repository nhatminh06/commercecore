# Kafka-backed outbox delivery

## Why Kafka now

The outbox milestone (`docs/outbox.md`) built the claim/publish/mark mechanism and proved its
correctness against a fake, in-memory sink (`RecordingEventSink`). This milestone replaces that
fake sink with a real Apache Kafka broker without touching the claim/publish/mark algorithm at
all — `OutboxPublisher` is unmodified. The point is to see whether the correctness properties
already proven (duplicate-safe retries, stable event IDs, at-least-once publication) actually hold
against a real distributed system, not just an in-process test double.

## Two separate reliability boundaries — never one "exactly-once" system

```text
PostgreSQL outbox  ---at-least-once--->  Kafka
                                            |
                                     at-least-once
                                            |
                                            v
                                   consumer (idempotent)
```

These are two independent hops, each with its own duplicate-delivery window, and neither is
exactly-once:

1. **Outbox → Kafka.** `OutboxPublisher` cannot always tell "Kafka never got this send" from
   "Kafka got it and I never heard back" (see `docs/outbox.md`'s unavoidable duplicate-delivery
   window). A crash between Kafka accepting a record and CommerceCore marking `published_at` means
   the same outbox row gets claimed and sent again later — a second, physically distinct Kafka
   record with the *same* `eventId`.
2. **Kafka → consumer.** Kafka's own delivery contract is at-least-once: a consumer that crashes
   or is rebalanced after processing a record but before committing its offset will see that
   record again.

Nothing in this design claims "exactly-once Kafka delivery," "exactly-once end-to-end delivery,"
or "exactly-once payment processing." Both hops can duplicate. Correctness comes entirely from the
third piece: **consumer-side processing is idempotent**, keyed on a stable `eventId` that survives
every retry at every hop.

### Producer idempotence does not close the outbox's duplicate window

Kafka's producer idempotence feature (`enable.idempotence`) prevents the *producer client* from
creating duplicate records due to its own internal retries (e.g. a TCP retry after an
unacknowledged send). It says nothing about `OutboxPublisher` deciding, at the application level,
to call `send()` a second time for the same outbox row because it never learned the first send
succeeded. That is a second, independent, application-level `send()` call — Kafka's producer
idempotence has no way to recognize it as "the same" event, because from Kafka's point of view it
is a brand new record. This project does not enable producer idempotence for this reason: it would
not eliminate the duplicate window this milestone already documents and tests, and enabling it
without that understanding would be misleading.

## Topic, partitioning, and key

One topic: `commerce.events` (`KafkaTopics.COMMERCE_EVENTS`). Every domain event this project
produces goes to it — there is no per-aggregate-type topic split, because nothing yet demands one
and a single topic keeps ordering/partitioning reasoning in one place.

The record key is the event's `aggregateId` (e.g. an order ID), not `eventId`. Consequence: Kafka
guarantees ordering only *within* a partition, and all records sharing a key land on the same
partition — so all events for one order are delivered to a consumer in the order they were sent,
but there is **no ordering guarantee across different aggregates** (two different orders' events
can interleave in any order, and may be processed by different partitions/consumers concurrently).
This is per-aggregate ordering, not a global ordering claim.

The topic is created with 3 partitions and replication factor 1 (`KafkaConsumerConfig`'s `NewTopic`
bean) — replication factor 1 because this project runs a single local broker, not a cluster; see
Limitations.

## Envelope

```json
{
  "eventId": "c6d2cb75-0d4d-43d5-8e4c-fd4357dd6b95",
  "eventType": "ORDER_CREATED",
  "aggregateType": "ORDER",
  "aggregateId": "5303db7d-5d3a-431d-a685-4b6a4f08d87c",
  "createdAt": 1787880597.180403000,
  "payload": {"orderId": "5303db7d-...", "status": "PENDING", "total": 39.98}
}
```

`EventEnvelope` is a direct mirror of `outbox.PublishedEvent` — it excludes every outbox-internal
bookkeeping field (`claimToken`, `attemptCount`, `claimedAt`, `publishedAt`). `eventId` is always
`outbox_events.id`, generated once when the row is first inserted and never regenerated on retry —
this is the field consumer-side deduplication keys on. `payload` carries the same JSON already
stored in the outbox row's `payload` column, parsed rather than re-encoded.

## Producer

`KafkaDomainEventSink` implements the same `DomainEventSink` interface `RecordingEventSink` always
did — `OutboxPublisher` calls `publish(PublishedEvent)` without knowing or caring which
implementation is active. It builds an `EventEnvelope`, sends it with `KafkaTemplate.send(topic,
key, envelope)`, and blocks (with a 10-second timeout) on the returned future so that a send
failure surfaces as an exception `OutboxPublisher` can react to (leaving the row unpublished and
retryable) rather than being silently lost in the background.

`acks: all` is configured — the producer waits for the broker to fully acknowledge the write
before considering the send successful. Documented honestly: this project runs a **single local
broker**, so `acks: all` here does not provide multi-node durability the way it would against a
replicated cluster. It is still the more conservative, more correct default to reach for, and it
is what a real deployment would use — this project just isn't (yet) running the topology that
makes it meaningfully durable.

## Consumer: `KafkaEventReceiptConsumer`

This is a **technical proof consumer only**. It does not confirm an order, release a reservation,
change payment state, send email, or perform any business workflow. Its entire job is to prove
that Kafka delivery — including consumer-group rebalancing and redelivery — can be safely
processed by inserting one row into `kafka_event_receipts`:

```sql
CREATE TABLE kafka_event_receipts (
    event_id       UUID PRIMARY KEY,
    event_type     VARCHAR(64) NOT NULL,
    aggregate_type VARCHAR(32) NOT NULL,
    aggregate_id   UUID NOT NULL,
    consumed_at    TIMESTAMPTZ NOT NULL
);
```

`PRIMARY KEY (event_id)` plus `INSERT ... ON CONFLICT (event_id) DO NOTHING`
(`KafkaEventReceiptRepository.tryInsert`) is the entire deduplication mechanism — the same
affected-row-count idiom already used for the outbox claim and the payment webhook receipt. The
table is **persistent** (PostgreSQL), deliberately not an in-memory `Set`/`ConcurrentHashMap`: an
in-memory structure would forget every receipt on process restart, which is exactly the moment
Kafka is most likely to redeliver a record (an uncommitted offset from before the crash).

## Offset-commit ordering: receipt before acknowledge, never the reverse

```text
1. record arrives
2. INSERT INTO kafka_event_receipts ... ON CONFLICT DO NOTHING   (commits or safely no-ops)
3. acknowledgment.acknowledge()                                  (offset committed)
```

The listener uses `ContainerProperties.AckMode.MANUAL_IMMEDIATE` with `enable-auto-commit: false`
— nothing auto-commits an offset. If step 2 throws, step 3 never runs: the offset stays
uncommitted and Kafka redelivers the same record later (via `DefaultErrorHandler`'s bounded
backoff, or on the next rebalance/restart). If the ordering were reversed — offset committed before
the receipt is durably written — a crash between those two steps would lose the event forever: the
consumer would never see it again, and no receipt would exist. Committing the local state first and
the offset second is what makes "redelivered" and "lost" different outcomes; the wrong order makes
them the same outcome.

## Duplicate and redelivery scenarios, and why each is safe

| Scenario | What happens | Why it's safe |
|---|---|---|
| Outbox retries the same claimed row after a lease expiry | A second Kafka record, same `eventId` | Consumer's `ON CONFLICT DO NOTHING` on `event_id` — second insert is a no-op |
| Kafka redelivers because the offset was never committed | The listener runs again for the same record | Same `event_id` — no-op insert; consumer logic is naturally idempotent, not "retried differently" |
| Consumer crashes before the receipt insert commits | Offset never acknowledged | Redelivery is guaranteed (correct outcome); no receipt was ever written, so no duplicate risk either |
| Consumer crashes after the receipt commits but before acknowledge | Offset never acknowledged, record redelivered | Redelivered processing hits the same no-op insert; receipt is already correct and stays that way |
| Consumer group rebalances (a member joins/leaves) | Partitions reassigned; whichever member gets a partition resumes from its last committed offset | Standard Kafka consumer-group semantics; nothing here is CommerceCore-specific |

## Restart recovery

Because the receipt table is PostgreSQL, not in-memory, a full process restart loses nothing: any
record the consumer had accepted and inserted a receipt for (but possibly not yet acknowledged)
simply gets redelivered and reprocessed into the same no-op. The dedup state survives the process
that produced it, which is the entire reason it isn't a `ConcurrentHashMap`.

## Sink selection: one Spring profile, not scattered conditionals

Exactly one `DomainEventSink` bean exists in any given Spring context:

- `RecordingEventSink` — `@Profile("!kafka")`, the deterministic in-memory fake used by
  `OutboxPublisher`'s own unit/failure tests (`docs/outbox.md`).
- `KafkaDomainEventSink` — `@Profile("kafka")`, wired up alongside `KafkaProducerConfig`,
  `KafkaConsumerConfig`, and `KafkaEventReceiptConsumer` (all also `@Profile("kafka")`).

No `if (activeProfile.equals("kafka"))` branch exists anywhere in business code — profile gating
happens once, at bean definition, and `OutboxPublisher` never knows which sink it's holding.
`./gradlew bootRun` activates the `kafka` profile by default (see build.gradle); tests that extend
`AbstractKafkaIntegrationTest` activate it via `@ActiveProfiles("kafka")`, and all other existing
tests are entirely unaffected — they run with `RecordingEventSink` exactly as they did before this
milestone.

## Local Docker Compose setup

`compose.yaml` adds a `kafka` service: `apache/kafka:3.8.0`, running in **KRaft mode** (no
ZooKeeper — combined broker+controller, single node, `CLUSTER_ID` fixed for a stable local
identity). Ports `9092` (advertised as `kafka:9092` for anything inside the Compose network) and
`29092` (advertised as `localhost:29092`, what the application and any host-side CLI use).

```bash
docker compose up -d          # starts Postgres + Kafka
./gradlew bootRun             # kafka profile active by default; creates commerce.events on boot
```

No CommerceCore Dockerfile exists or is needed — the application itself still runs directly via
Gradle, only its infrastructure dependencies run in containers.

## Testcontainers setup

`AbstractKafkaIntegrationTest` (extends `AbstractIntegrationTest`) starts a singleton
`org.testcontainers.kafka.KafkaContainer("apache/kafka:3.8.0")` once per test JVM, shared across
every Kafka test class exactly like the existing PostgreSQL singleton container — `./gradlew test`
needs no `docker compose up` beforehand. `@ActiveProfiles("kafka")` activates the real sink/consumer
beans, and a `@DynamicPropertySource` points `spring.kafka.bootstrap-servers` at the container's
actual mapped address.

## Manual verification performed

With `docker compose up -d` and `./gradlew bootRun` running:

```bash
curl -X POST http://localhost:8080/api/products -H "Content-Type: application/json" \
  -d '{"sku":"SKU-DEMO-1","name":"Demo Widget","price":19.99,"initialQuantity":10}'

CART_ID=$(curl -s -X POST http://localhost:8080/api/carts | python3 -c "import sys,json;print(json.load(sys.stdin)['id'])")
curl -X PUT "http://localhost:8080/api/carts/$CART_ID/items/SKU-DEMO-1" -H "Content-Type: application/json" -d '{"quantity":2}'
curl -X POST "http://localhost:8080/api/carts/$CART_ID/checkout" -H "Idempotency-Key: demo-key-1"

curl -X POST http://localhost:8080/api/dev/outbox/publish
```

```sql
SELECT id, aggregate_type, aggregate_id, event_type, published_at FROM outbox_events;
SELECT event_id, event_type, aggregate_type, aggregate_id, consumed_at FROM kafka_event_receipts;
```

```bash
docker exec commercecore-kafka-1 /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic commerce.events --from-beginning \
  --property print.key=true --timeout-ms 8000
```

This confirmed: the outbox row (`published_at` set), a real Kafka record with key = `aggregateId`
and the documented envelope shape, and exactly one `kafka_event_receipts` row. Re-triggering
`/api/dev/outbox/publish` claimed and published nothing (`{"claimed":0,"published":0}`) — the
already-published row is skipped. Manually producing a second physical Kafka record with the same
`eventId` via `kafka-console-producer.sh` left the receipt count at exactly 1.

## Limitations

- Single local broker, replication factor 1 — no broker failover, no multi-node durability despite
  `acks: all`. This is a correctness study of the outbox→Kafka→consumer path, not a high-availability
  deployment.
- No Schema Registry, no Avro/Protobuf — the envelope is plain JSON with no schema evolution
  story beyond "add fields, don't remove/rename ones a consumer might rely on."
- No business workflow consumer exists yet — `KafkaEventReceiptConsumer` only proves delivery and
  deduplication; nothing yet reacts to `ORDER_CREATED` by doing anything.
- No cross-aggregate ordering guarantee — only per-`aggregateId` partition affinity.
- No consumer-lag or broker-health monitoring — out of scope until a concrete failure experiment
  needs it (`docs/kafka.md` intentionally does not add observability tooling).
- Optional broker-restart and different-consumer-group test scenarios were not written — the
  required scenarios (delivery, dedup, redelivery, consumer-group coverage) are all covered without
  them.
