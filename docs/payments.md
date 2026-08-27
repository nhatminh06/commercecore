# Payments

## The new boundary

Every module before this one — catalog, inventory, cart, reservation, checkout — is entirely
local: one PostgreSQL database, one transaction, roll back and nothing happened. Payment is the
first operation that reaches a system CommerceCore does not control and cannot roll back.

```text
Database transaction
      |
      X cannot include
      |
External payment provider
```

`ROLLBACK` undoes rows in PostgreSQL. It cannot undo a charge a provider already processed —
there is no message CommerceCore can send that un-happens a real-world side effect. This is why
payment can't be "just another `@Transactional` operation": the moment a provider call is made,
CommerceCore's transaction boundary and reality's boundary have permanently diverged, and no
amount of `@Transactional` can put them back in sync.

## Why a timeout is not a failure

```text
CommerceCore                    Payment Provider
    |  authorize $20.00                |
    |---------------------------------->
    |                                  | charge succeeds
    |         X response lost X        |
    |<- - - - - - - - - - - - - - - - -|
    |  CommerceCore sees: timeout      |
    |  reality: customer was charged   |
```

If CommerceCore marked this `FAILED`, a later (imagined, not built yet) workflow might release
the reservation and let someone else buy the item — while the original customer's card was
already charged. That's a real, costly bug class, not a hypothetical. So a timeout produces
`UNKNOWN`, not `FAILED`: a status that means exactly "processed or not — CommerceCore cannot
currently prove which," never "definitely didn't work."

## State model

```java
enum PaymentStatus { PENDING, AUTHORIZED, FAILED, UNKNOWN }
```

```text
                SUCCESS
PENDING -----------------> AUTHORIZED
   |
   | DECLINED
   +---------------------> FAILED
   |
   | TIMEOUT / UNKNOWN OUTCOME
   +---------------------> UNKNOWN
```

`AUTHORIZED` and `FAILED` are terminal for this milestone — nothing transitions out of them.
`UNKNOWN` is also left alone here: nothing automatically retries or resolves it. A later
reconciliation milestone (querying the provider to find out what actually happened) is what
eventually resolves an `UNKNOWN` payment — not this one.

## Payment vs. order state

**A payment becoming `AUTHORIZED` does not change the order's status.** The order stays
`PENDING`. This is deliberate: this milestone studies payment truth in isolation, before any
later milestone has to coordinate "payment succeeded" with "order should become confirmed" and
"reservations should become confirmed too." Conflating the two here would hide a real
orchestration problem behind convenient-looking code.

## Schema

```sql
CREATE TABLE payments (
    id                 UUID PRIMARY KEY,
    order_id           UUID NOT NULL UNIQUE REFERENCES orders (id),
    amount             NUMERIC(12, 2) NOT NULL CHECK (amount >= 0),
    status             VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'AUTHORIZED', 'FAILED', 'UNKNOWN')),
    provider_reference VARCHAR(255) NULL,
    created_at         TIMESTAMPTZ NOT NULL,
    updated_at         TIMESTAMPTZ NOT NULL
);
```

`UNIQUE(order_id)` is what makes "one payment per order" a database fact, not just an application
convention. No card number, CVV, billing address, or customer token is stored anywhere —
CommerceCore never handles real payment credentials, fake or otherwise.

## Amount: always from the order, never from the client

`POST /api/orders/{orderId}/payment` accepts no request body — there is no field for a client to
put an amount in. `payment.amount` is always `order.total_amount`, read server-side at the moment
the payment row is created. A client cannot authorize a different amount than what checkout
already computed and stored, and a later catalog price change cannot change it either, for the
same reason an order's own line prices don't change (see `docs/checkout.md`).

## Payment identity — three different IDs

```text
Order O1
  |
  v
Payment P1                    <- CommerceCore's own payment identity (payments.id)
  |
  v
providerRequestId = P1's id   <- what CommerceCore sends the provider to identify the request
  |
  v
providerReference             <- what the provider sends back to identify its own record
```

`Payment.id` and `providerReference` (e.g. `pay_...`) are different identities from different
systems. CommerceCore never invents its own idea of what the provider calls its charge; it stores
whatever the provider actually returns, and only when known. `providerRequestId` is fixed as
`Payment.id` — generated once, when the payment row is created, and reused on every subsequent
call for that same payment — never a fresh UUID per attempt. This is deliberately not the
checkout `Idempotency-Key`: checkout and payment are different operations with different retry
identities, and conflating them would make a payment initiation accidentally sensitive to
whatever key a checkout retry happened to use.

## The fake provider

There is no real payment processor. `FakePaymentProvider` is a small, deterministic, in-memory
double — a real Spring bean (not a test mock), because nothing else stands in for a payment
provider in this milestone. Its next outcome is set explicitly, never randomly:

- `SUCCESS` — returns `Authorized(providerReference)`.
- `DECLINED` — returns `Declined(reason)`.
- `TIMEOUT_AFTER_PROCESSING` — the critical case: the provider *does* record a successful
  authorization in its own internal ledger, then throws `PaymentProviderTimeoutException` instead
  of returning it. Tests can call `hasProviderSideAuthorization(providerRequestId)` afterward to
  prove the provider-side result existed even though CommerceCore received nothing — the concrete
  evidence behind "timeout ≠ failure," not just an assertion of it.

The fake also implements its own idempotency: calling `authorize` again with a
`providerRequestId` it has already processed returns the same result instead of reprocessing —
modeling how a real provider is expected to behave, and giving CommerceCore's own duplicate-call
prevention a second line of defense.

A narrow, clearly-labeled development-only endpoint, `POST /api/dev/payment-provider/next-outcome`,
lets the fake's next outcome be set over HTTP for manual demonstration — the same configuration
tests perform directly on the bean.

## Transaction architecture: three phases, not one

```text
Phase A (DB transaction)        Phase B (no transaction)         Phase C (DB transaction)
tryCreatePayment                paymentProvider.authorize(...)   markAuthorized / markFailed
  -> commits a PENDING row                                          / markUnknown
```

`PaymentService.initiatePayment` is **not** `@Transactional`. If it were, a timeout exception
from Phase B would roll back the *entire* method — including the PENDING row Phase A wrote —
making a persisted `UNKNOWN` outcome impossible. The whole reason `UNKNOWN` can exist as visible
database state is that Phase A's commit already happened before Phase B was ever attempted:
whatever happens next, there's a payment row to update, not one to lose. This is the concrete
form of "the DB transaction cannot include the external call" — not a rule followed for its own
sake, but the direct explanation for why the three-phase split exists at all.

## Duplicate initiation: one provider call, not just one row

A bare `UNIQUE(order_id)` constraint prevents duplicate *rows* — it does nothing to stop the
provider from being called twice by two transactions that both raced past a stale
"does a payment exist yet?" check before either one committed. Preventing *that* is the actual
concurrency problem.

`PaymentRepository.tryCreatePayment` is one atomic statement:

```sql
INSERT INTO payments (id, order_id, amount, status, created_at, updated_at)
VALUES (:id, :orderId, :amount, 'PENDING', :now, :now)
ON CONFLICT (order_id) DO NOTHING
```

Its affected-row count is the entire ownership decision — the same "let the database's own
atomicity decide the winner" idiom already proven by `InventoryRepository.tryConsume` and the
cart upsert. Whichever caller's `INSERT` actually lands (1 row affected) is the sole owner of
this payment attempt and the only one who calls the provider. Every other caller — 0 rows
affected, meaning a row for this order already existed, whether created a moment ago by a
concurrent racer or resolved from an earlier request entirely — reads back the current row and
returns without ever touching `PaymentProvider`. Twenty concurrent callers for a brand-new order's
payment produce exactly one `INSERT` winner and one provider call; twenty concurrent (or
sequential) callers for an already-`AUTHORIZED` order all find the existing row and none call the
provider. One mechanism handles both cases.

## Success flow

`PENDING` row created → `paymentProvider.authorize(payment.id, amount)` returns `Authorized` →
`UPDATE payments SET status='AUTHORIZED', provider_reference=:ref WHERE status='PENDING'`.

## Decline flow

`PENDING` row created → provider returns `Declined` → `status` becomes `FAILED`. **Nothing else
happens.** The reservation stays `ACTIVE`, inventory stays decremented, the order stays
`PENDING`. There is currently no code path that releases stock when a payment fails — that
compensation workflow (payment failure → release reservation → maybe cancel order) belongs to a
later milestone that studies multi-step failure coordination deliberately, not one bolted onto
payment state itself.

## Timeout flow

`PENDING` row created → provider call throws `PaymentProviderTimeoutException` → `status`
becomes `UNKNOWN`, `provider_reference` stays `NULL` (a genuine timeout means no reference ever
arrived to persist). Order and reservation are untouched, exactly as in the decline case.

## Known limitations

- Fake provider only — no real processor, no network calls, no real card handling.
- No webhook endpoint yet (Milestone 7).
- `UNKNOWN` payments are never reconciled — nothing queries the provider to resolve them
  (a later milestone).
- A `FAILED` payment does not release its reservation or touch inventory.
- An `AUTHORIZED` payment does not confirm its order; the order remains `PENDING` regardless of
  payment outcome.
- No refunds, no partial captures, no chargebacks.
- No outbox, no Kafka, no Redis, no Kubernetes, no frontend.
