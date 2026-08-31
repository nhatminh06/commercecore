# Payment Service extraction

## Why Payment was selected

Payment already behaved like an external system: it had a stable request identity, provider
reference, ambiguous timeout state, lookup, and reconciliation. Extracting that one boundary adds
real distributed failure semantics without fragmenting Catalog, Cart, Inventory, Checkout,
Orders, Outbox, or Kafka consumers.

```text
BEFORE

CommerceCore
    ↓ local PaymentProvider call
FakePaymentProvider

AFTER

CommerceCore
    ↓ gRPC
Payment Service
    ↓
provider PostgreSQL ledger
```

CommerceCore is therefore a modular commerce application with one deliberately extracted remote
payment-provider boundary, not a microservices architecture.

## Contract

`payment-proto/src/main/proto/payment_provider.proto` defines one service:

```text
AuthorizePayment(provider_request_id, amount_minor_units)
LookupPayment(provider_request_id)
```

Authorization returns `AUTHORIZED` with a stable provider reference or `DECLINED` with a reason.
Lookup returns `AUTHORIZED`, `DECLINED`, or `NOT_FOUND`. Decline and not-found are successful RPC
responses. Timeout is never a response enum: it is a real gRPC deadline/transport failure.

Protobuf field numbers are permanent identities. Existing numbers must never be reused if a field
is removed; compatible evolution should add new fields with new numbers.

## Exact money

The wire uses signed 64-bit minor units. CommerceCore requires exactly two decimal places and uses
`setScale(2, UNNECESSARY).movePointRight(2).longValueExact()`. Thus `30.50` becomes exactly `3050`;
fractional cents and overflow are rejected rather than rounded. The current model assumes one
two-decimal currency and deliberately does not add multi-currency behavior.

## Identity, ledger, and idempotency

`provider_request_id` is the existing CommerceCore payment UUID. The client never generates a
second ID. Payment Service owns only:

```text
provider_payments(provider_request_id, amount, status, provider_reference, created_at)
```

`provider_request_id` is the PostgreSQL primary key. A transaction-scoped PostgreSQL advisory lock
serializes the same request identity across threads and service instances, while the primary key
is the final uniqueness invariant. Same ID plus same amount returns the stored outcome and
reference. Same ID plus different amount returns gRPC `FAILED_PRECONDITION`. The ledger persists
only `AUTHORIZED` or `DECLINED`; `TIMEOUT` and `UNKNOWN` describe what CommerceCore observed.

## Deadlines and ambiguity

CommerceCore applies `commercecore.payment.grpc.deadline-ms`, default `500`, to every call. It does
not automatically retry authorization. For authorization, `DEADLINE_EXCEEDED`, `UNAVAILABLE`, and
`CANCELLED` are ambiguous and become `PaymentProviderTimeoutException`; `PaymentService` records
the local payment as `UNKNOWN`. `FAILED_PRECONDITION` remains an explicit conflict. A lookup
transport failure throws `PaymentProviderUnavailableException` and is never converted to provider
`NOT_FOUND`.

For `TIMEOUT_AFTER_PROCESSING`, Payment Service commits `AUTHORIZED` and its provider reference,
then delays the response beyond CommerceCore's deadline. `TIMEOUT_AFTER_DECLINE` does the same with
`DECLINED`. The client deadline therefore proves only that CommerceCore stopped waiting—not that
the provider transaction failed.

## Reconciliation

```text
CommerceCore UNKNOWN
    ↓ gRPC LookupPayment(same payment UUID)
provider AUTHORIZED / DECLINED / NOT_FOUND
    ↓
existing PaymentReconciliationApplier
```

Reconciliation never calls `AuthorizePayment`. Definitive truth uses the existing transactional
payment/outbox transition. `NOT_FOUND` leaves the payment unresolved. A transport failure remains
a transport failure. Existing `AUTHORIZED`-after-reservation-expiry safety still produces
`REQUIRES_REVIEW` rather than confirming an order without owned stock.

## Two databases and no distributed transaction

CommerceCore PostgreSQL owns products, inventory, carts, reservations, orders, payments,
reconciliation cases, outbox events, and Kafka receipts. Payment Service PostgreSQL owns provider
facts only. Neither service reads the other's database, and there is no XA or two-phase commit.
The temporary disagreement between “what CommerceCore observed” and “what the provider did” is the
failure model reconciliation exists to repair.

## gRPC versus Kafka

gRPC means “do this or tell me this now”: synchronous authorization and lookup. Kafka means “this
committed business fact already happened”: asynchronous payment outcome delivery to the existing
order workflow. gRPC does not replace the transactional outbox or Kafka.

## Development and verification

Start the real local architecture:

```bash
docker compose up -d
./gradlew bootRun
```

Configure the next provider outcome:

```bash
curl -X POST localhost:8091/api/dev/provider/next-outcome \
  -H 'Content-Type: application/json' \
  -d '{"outcome":"TIMEOUT_AFTER_PROCESSING"}'
```

Create a product and cart, then substitute the returned IDs:

```bash
curl -X POST localhost:8080/api/products \
  -H 'Content-Type: application/json' \
  -d '{"sku":"GRPC-DEMO","name":"gRPC demo","price":30.50,"initialQuantity":2}'
curl -X POST localhost:8080/api/carts
curl -X PUT localhost:8080/api/carts/{cartId}/items/GRPC-DEMO \
  -H 'Content-Type: application/json' -d '{"quantity":1}'
curl -X POST localhost:8080/api/carts/{cartId}/checkout \
  -H 'Idempotency-Key: grpc-demo-1'
curl -X POST localhost:8080/api/orders/{orderId}/payment
curl -X POST localhost:8080/api/dev/payments/{paymentId}/reconcile
curl -X POST localhost:8080/api/dev/outbox/publish
curl localhost:8080/api/orders/{orderId}
```

Restart the provider process and retry lookup/reconciliation:

```bash
docker compose restart payment-service
curl -X POST localhost:8080/api/dev/payments/{paymentId}/reconcile
```

Inspect CommerceCore:

```bash
docker compose exec postgres psql -U commercecore -d commercecore -c \
  "SELECT * FROM payments ORDER BY created_at DESC;"
docker compose exec postgres psql -U commercecore -d commercecore -c \
  "SELECT * FROM orders ORDER BY created_at DESC;"
docker compose exec postgres psql -U commercecore -d commercecore -c \
  "SELECT * FROM inventory_reservations ORDER BY created_at DESC;"
docker compose exec postgres psql -U commercecore -d commercecore -c \
  "SELECT * FROM payment_reconciliation_cases ORDER BY updated_at DESC;"
docker compose exec postgres psql -U commercecore -d commercecore -c \
  "SELECT * FROM outbox_events ORDER BY created_at DESC;"
```

Inspect provider truth:

```bash
docker compose exec payment-postgres psql -U payment_provider -d payment_provider -c \
  "SELECT * FROM provider_payments ORDER BY created_at DESC;"
```

Build and run focused evidence:

```bash
./gradlew clean test
./gradlew build
./gradlew :payment-service:test
./gradlew :test --tests com.commercecore.payment.RemotePaymentReconciliationEndToEndTest
```

## Known limitations

The Payment Service is fake, runs as one local instance, and uses a statically configured host and
port. There is no TLS/mTLS, service authentication, service discovery, real processor, refund
workflow, automatic reconciliation scheduler, Kubernetes, Redis, or frontend. The development
control endpoint is enabled only by Payment Service's `dev` profile.
