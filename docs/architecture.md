# Final architecture

CommerceCore is a correctness-first modular commerce application with one deliberately extracted
remote boundary. It is not a general microservices platform.

```text
REST client
    |
    v
CommerceCore ------------------------------------------------------+
    |                                                              |
    | local ACID transactions                                      | synchronous command/query
    v                                                              v
CommerceCore PostgreSQL                                      TCP gRPC
    |                                                              |
    | payments, orders, reservations,                              v
    | idempotency, receipts, outbox                         Payment Service
    |                                                              |
    | committed publication intent                                 | provider transaction
    v                                                              v
OutboxPublisher -> Kafka commerce.events                    Provider PostgreSQL
                       |                                     provider_payments
                       +-> proof consumer group
                       |      -> kafka_event_receipts
                       +-> order-workflow consumer group
                              -> order/reservation mutation
                              -> order_workflow_event_receipts
```

## Why three infrastructure mechanisms exist

- **PostgreSQL** owns local invariants and atomic state changes. Conditional updates prevent
  negative inventory; row/advisory locks serialize competing transitions; unique and check
  constraints provide durable identity and valid-state enforcement.
- **gRPC** is the synchronous `PaymentProvider` command/query boundary. It answers “authorize this
  request now” and “what did the provider do?”, but its deadline cannot atomically include either
  database.
- **Kafka** carries committed facts asynchronously. The transactional outbox closes the local
  business-state/publication-intent dual-write gap; persistent consumer receipts make redelivery
  safe. Delivery is at least once, not exactly once.

## Ownership

CommerceCore owns products, carts, inventory, reservations, orders, commerce payment state,
checkout idempotency, webhook receipts, reconciliation cases, outbox events, and Kafka consumer
receipts. Payment Service owns only provider request identity, amount, definitive provider outcome,
and provider reference. Neither service reads the other's database, and there is no XA or 2PC.

## Failure boundaries

The design deliberately permits temporary disagreement between local payment observation and
provider truth. A deadline can leave CommerceCore `UNKNOWN` while Payment Service is already
`AUTHORIZED` or `DECLINED`. Reconciliation repairs safe disagreement through read-only lookup.
When stock ownership is already lost or terminal financial states conflict, the system records
`REQUIRES_REVIEW` instead of guessing a compensation.

## Development-only controls

CommerceCore `/api/dev/*` controllers require the `dev` profile. `./gradlew bootRun` activates
`kafka,dev` for the documented local workflow. The recording-sink control additionally requires
Kafka to be disabled. Payment Service's outcome controller requires its `dev` profile, enabled by
Compose. These endpoints are verification tools, not authenticated production APIs.

