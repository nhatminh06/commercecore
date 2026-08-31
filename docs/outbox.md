# Transactional outbox

## The dual-write problem

CommerceCore's database and any future message broker are two separate systems. There is no
single transaction that spans both, so writing to one and then the other is never atomic:

```text
BAD:

BEGIN
INSERT order
COMMIT
      ↓
publish OrderCreated
      ↓
CRASH
```

If the process crashes (or the broker is unreachable) between the commit and the publish, the
order exists but no event describing it ever will — anything downstream that was supposed to
react to `OrderCreated` never finds out. The opposite ordering is just as broken:

```text
BAD:

publish OrderCreated
      ↓
BEGIN
INSERT order
ROLLBACK   -- insufficient stock, a conflict, anything
```

Now the event claims something happened that the database says never did. Publishing before
committing can announce a fact that turns out to be false.

## The fix: persist the event with the fact, in the same transaction

```text
GOOD:

BEGIN
INSERT order
INSERT outbox_events (ORDER_CREATED)
COMMIT
```

```text
outbox_events
      ↓
publisher (reads later, separately)
      ↓
external sink
```

Both writes are ordinary rows in the same PostgreSQL transaction. If the transaction commits,
both exist; if it rolls back, neither does. There is no window where one exists without the
other — which is exactly the property "commit order, then publish" and "publish, then commit
order" both lack. Publication itself becomes a separate, later, retryable step, decoupled from
the business transaction that produced the fact.

## Domain event vs. transport event

This project already has one instance of "the same fact reported multiple ways" —
`payment_webhook_events` (Milestone 7). The same distinction applies here, sharper:

- A **transport event** (a payment provider webhook delivery, `payment_webhook_events`) is a
  message that arrived. There can be many, with different IDs, reporting the same underlying
  fact.
- A **domain event** (`outbox_events`) is the fact itself, as CommerceCore understands it, written
  once when — and only when — the fact actually becomes true.

Two different provider event IDs (`evt_1`, `evt_2`) can both legitimately report "this payment is
authorized." `PaymentTransitionService.resolveToAuthorized` only writes a `PAYMENT_AUTHORIZED`
outbox row when its conditional `UPDATE` actually changes the payment's status — the second
webhook, hitting an already-`AUTHORIZED` payment, is an idempotent no-op and writes nothing. Two
transport receipts, one domain event. See `PaymentOutboxTest.distinctWebhookEventIdsWithSameOutcomeStillWriteOnlyOneDomainEvent`.

## Outbox table

```sql
CREATE TABLE outbox_events (
    id             UUID PRIMARY KEY,
    aggregate_type VARCHAR(32) NOT NULL CHECK (aggregate_type IN ('ORDER', 'PAYMENT')),
    aggregate_id   UUID NOT NULL,
    event_type     VARCHAR(64) NOT NULL CHECK (event_type IN ('ORDER_CREATED', 'PAYMENT_AUTHORIZED', 'PAYMENT_FAILED')),
    payload        JSONB NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL,
    published_at   TIMESTAMPTZ NULL,
    attempt_count  INTEGER NOT NULL DEFAULT 0,
    claimed_at     TIMESTAMPTZ NULL,
    claim_token    UUID NULL
);
```

`payload` is `JSONB`, not a Java-serialized blob — inspectable directly with a `SELECT`, and
interoperable with whatever eventually reads it. Only three event types exist because only three
correspond to domain facts CommerceCore actually produces right now; no `ORDER_UPDATED`-style
catch-all, no event for `UNKNOWN` (CommerceCore doesn't know enough yet to say anything).

## Event creation

`OutboxEventWriter.write(...)` is the one place a row gets inserted, called from exactly three
places, each right where the corresponding fact commits:

- `CheckoutService.executeCheckout` — `ORDER_CREATED`, immediately after the order row is saved,
  in the same transaction as the order, its items, and its reservations. A rolled-back checkout
  (insufficient stock) rolls this back too; a replayed checkout (same `Idempotency-Key`) never
  re-enters this method, so it never writes a second event either.
- `PaymentTransitionService.resolveToAuthorized`/`resolveToFailed` — `PAYMENT_AUTHORIZED`/
  `PAYMENT_FAILED`, only when the conditional payment `UPDATE` actually affected a row. Shared by
  both paths that can transition a payment: `PaymentService.initiatePayment`'s synchronous
  provider call, and `PaymentWebhookService.processWebhook`'s asynchronous evidence — one
  component, so state transition and outbox event can't accidentally diverge between them.

## Payload shapes

```json
// ORDER_CREATED
{"orderId": "...", "status": "PENDING", "total": 30.50}

// PAYMENT_AUTHORIZED
{"paymentId": "...", "orderId": "...", "amount": 30.50, "providerReference": "pay_abc123"}

// PAYMENT_FAILED
{"paymentId": "...", "orderId": "...", "amount": 30.50}
```

No payment credentials, no full entity graphs — just what a downstream consumer would plausibly
need.

## Why the same-bean-different-transaction trick works for payment transitions

`PaymentTransitionService` is a separate Spring bean from both `PaymentService` and
`PaymentWebhookService`. Called from `PaymentService.initiatePayment` (deliberately not
`@Transactional` — see `docs/payments.md`), its own `@Transactional` opens a short transaction
covering the state transition and the outbox insert together. Called from
`PaymentWebhookService.processWebhook` (already `@Transactional`), the same method's
`@Transactional` simply joins that already-active transaction — ordinary Spring `REQUIRED`
propagation. Same code, correct in both places, because it never needs to know which situation
it's in.

## Publisher: claim, publish, mark — three separate steps

```text
Phase 1 (short DB transaction): claim a batch
      ↓ commit
Phase 2 (no DB transaction open): call the sink
      ↓
Phase 3 (short DB transaction): mark published
```

`OutboxPublisher.publishBatch` is deliberately not `@Transactional`, for the same reason
`PaymentService.initiatePayment` isn't (`docs/payments.md`): the sink call is external, and
holding a database transaction — and the row locks that come with claiming — open across it would
mean an external call latency problem becomes a database lock contention problem too.

### Claiming

```sql
UPDATE outbox_events
SET claim_token = :claimToken, claimed_at = :now, attempt_count = attempt_count + 1
WHERE id IN (
    SELECT id FROM outbox_events
    WHERE published_at IS NULL
      AND (claimed_at IS NULL OR claimed_at <= :staleBefore)
    ORDER BY created_at, id
    LIMIT :batchSize
    FOR UPDATE SKIP LOCKED
)
```

`FOR UPDATE SKIP LOCKED` is what lets multiple publisher workers coordinate without an
application-level lock: a row another transaction is mid-claim on is silently skipped, not waited
on, so concurrent workers naturally end up claiming disjoint slices of the pending backlog instead
of serializing behind each other. `ORDER BY created_at, id` gives deterministic claim order — but
that is a claim-selection order, not a delivery-ordering guarantee across a future distributed
broker, which this project does not promise.

### Claim lease

A worker can crash after claiming and before publishing. `claimed_at <= staleBefore` (where
`staleBefore = now - 30s`) is what makes a stuck claim recoverable: after the lease elapses, any
worker (including the original one, on a later sweep) can claim the row again. `now` is an
explicit parameter to `publishBatch`, not read internally — the same reasoning as
`ReservationService.expireDueReservations` — so lease-expiry tests are deterministic without
sleeping.

### Marking published

```sql
UPDATE outbox_events
SET published_at = :now, claim_token = NULL, claimed_at = NULL
WHERE id = :id AND claim_token = :claimToken AND published_at IS NULL
```

Requiring the caller's own `claim_token` to still match means a worker whose claim has already
gone stale — and been reclaimed by someone else — cannot mark the row published out from under
the new claimant, even if its own slow publish attempt eventually completes.

## The unavoidable duplicate-delivery window

```text
sink accepts E1 (records it internally)
      ↓
publisher never finds out — network drop, crash, whatever
      ↓
E1 stays unpublished locally (claimed, but published_at still NULL)
      ↓
lease expires
      ↓
retry — E1 is claimed and published again
      ↓
sink now holds E1 twice
```

This is not a bug to fix. `OutboxPublisher` genuinely cannot distinguish "the sink never got
this" from "the sink got this and I just never heard back" — both look identical from the
caller's side: an exception (or nothing) instead of a clean return. Treating both cases the same
way (leave it retryable) is the only honest choice. `RecordingEventSink`'s `ACCEPT_THEN_THROW`
mode models this directly: it records the delivery in its own ledger *before* throwing, so tests
can prove the sink really did receive the message even though CommerceCore's own state
(`published_at`) still says otherwise.

**This is at-least-once publication, not exactly-once.** The event ID stays stable across every
delivery attempt of the same logical event — `OutboxPublisher` never generates a new ID per
retry — precisely so a downstream consumer, once one exists, can deduplicate on it. No such
consumer exists yet; that correctness burden is documented, not solved, in this milestone.

## No automatic scheduler yet

`OutboxPublisher.publishBatch` is invoked explicitly — by tests, or by the development-only
`POST /api/dev/outbox/publish` endpoint under the `dev` profile. Nothing calls it on a timer. Automatic periodic
publication is a decision for the Kafka milestone, which will also decide the operational polling
model.

## Known limitations

- Fake sink only (`RecordingEventSink`) — no real broker, no Kafka.
- At-least-once publication: duplicate delivery is possible and demonstrated, not eliminated.
- No downstream consumer exists — nothing reads from the sink and acts on it yet.
- No event retention/cleanup policy — published rows are kept indefinitely (deliberately, for
  auditability), and no pruning job exists.
- No automatic scheduling of `publishBatch`.
