# Payment-driven order workflow

## Why payment outcome drives order state

Checkout (`docs/checkout.md`) leaves an order `PENDING` with `ACTIVE` reservations — stock is held,
but nothing about the order is final yet. A payment's outcome is the fact that resolves that
ambiguity: `AUTHORIZED` means the customer's money is committed, so the held stock should become
permanently the order's; `FAILED` means it never will be, so the held stock must go back to being
available to someone else. This milestone is the first place those two already-proven, independent
facts (checkout's reservation, payment's outcome) get reconciled into one coherent order state,
consuming payment events asynchronously from Kafka rather than synchronously inside
`PaymentService.initiatePayment` — the same reasoning as the outbox itself: the workflow must
survive a crash between "payment resolved" and "order updated" without losing or duplicating either
side.

## AUTHORIZED flow

```text
PAYMENT_AUTHORIZED
        ↓
Kafka
        ↓
workflow consumer
        ↓
BEGIN
receipt
order CONFIRMED
reservations CONFIRMED
ORDER_CONFIRMED outbox
COMMIT
        ↓
ack Kafka
```

Reservations go `ACTIVE → CONFIRMED`. Confirming does **not** touch
`inventory.available_quantity` — that quantity was already decremented back at checkout time, when
each reservation became `ACTIVE` (`docs/reservations.md`). Confirmation only makes that decrement
permanent; it is not a second consumption.

## FAILED flow

```text
PAYMENT_FAILED
        ↓
Kafka
        ↓
workflow consumer
        ↓
BEGIN
receipt
release reservations
restore inventory
order CANCELLED
ORDER_CANCELLED outbox
COMMIT
        ↓
ack Kafka
```

Reservations go `ACTIVE → RELEASED`, and `ReservationService.release` restores each one's quantity
to `inventory.available_quantity` — reused unchanged from Milestone 3, inside this workflow's own
transaction.

## Order states and legal transitions

```text
PENDING    — payment/workflow outcome unresolved
CONFIRMED  — payment authorized; held inventory permanently confirmed
CANCELLED  — payment failed; held inventory compensated
```

```text
PENDING   → CONFIRMED   (AUTHORIZED applied)
PENDING   → CANCELLED   (FAILED applied)
CONFIRMED → CONFIRMED   (idempotent no-op: AUTHORIZED re-applied)
CANCELLED → CANCELLED   (idempotent no-op: FAILED re-applied)
CONFIRMED → CANCELLED   (rejected — conflicting terminal transition)
CANCELLED → CONFIRMED   (rejected — conflicting terminal transition)
```

No `PAID`/`SHIPPED`/`FULFILLING`/`REFUNDED`/`RETURNED` — those correspond to workflows this
milestone doesn't implement.

## Reservation semantics under each payment outcome

| Reservation state | AUTHORIZED | FAILED |
|---|---|---|
| ACTIVE | → CONFIRMED | → RELEASED (restores stock) |
| CONFIRMED | idempotent no-op | conflict (rejected) |
| RELEASED | conflict (rejected — see below) | idempotent no-op (already compensated) |
| EXPIRED | conflict (rejected — see below) | already compensated by expiration; skipped, no restoration |

## Why the workflow needs its own receipt table

`kafka_event_receipts` (Milestone 9) belongs to the **technical proof consumer**
(`commercecore-proof-consumer`) — it exists purely to prove Kafka delivery/dedup mechanics.
`order_workflow_event_receipts` belongs to a **different** consumer group
(`commercecore-order-workflow`) with a real business side effect. Deduplication identity is scoped
to a consumer's own side effect, not globally to the Kafka topic: the proof consumer processing
event E1 must not prevent the workflow consumer from also processing E1, and vice versa — each
group owns its own durable "have I already done my job for this event" ledger. Sharing one receipt
table across both would make one consumer's completion silently suppress the other's, which is not
how independent consumer groups work in Kafka and must not be simulated as if it were.

```sql
CREATE TABLE order_workflow_event_receipts (
    event_id     UUID PRIMARY KEY,
    event_type   VARCHAR(64) NOT NULL,
    payment_id   UUID NOT NULL,
    order_id     UUID NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL
);
```

The same `INSERT ... ON CONFLICT (event_id) DO NOTHING` idiom as every other dedup table in this
project. A duplicate `event_id` whose recorded identity (event type / payment / order) does not
match the delivery attempting to reuse it is rejected outright as a conflict — a reused event ID is
never silently treated as "the same event, trust it."

## Receipt, business mutation, and outbox event commit atomically

`OrderPaymentWorkflowService.handlePaymentAuthorized`/`handlePaymentFailed` are each one
`@Transactional` method: claiming the workflow receipt, verifying the payment's PostgreSQL state,
locking and transitioning the order, transitioning every reservation, and writing the resulting
`ORDER_CONFIRMED`/`ORDER_CANCELLED` outbox row all happen in that one transaction. If any invariant
is violated partway through, the whole method throws and every part of that sequence — including
the just-inserted receipt row — rolls back together. There is never a committed state where the
receipt exists but the order doesn't, or the order changed but the outbox event didn't.

`ReservationService.confirm`/`release` are on a different Spring bean, called from within this
already-active transaction. Because Spring's transactional proxy only applies to calls that cross a
bean boundary (not self-invocation on the same bean), this is a genuine cross-bean call and the
proxy intercepts it normally: its `@Transactional` (default `REQUIRED` propagation) simply joins
the caller's already-open transaction rather than starting a new one. That is what makes "reuse the
proven reservation transition mechanism, inside the workflow's own atomic unit" actually true rather
than aspirational — verified directly by the atomicity tests (a rejected workflow leaves reservation
state completely untouched, never partially transitioned).

## Duplicate and redelivery safety

- **Same event ID delivered 20 times (physically, over real Kafka)** — the first delivery claims
  the receipt (`INSERT ... ON CONFLICT DO NOTHING` returns 1) and applies the business transition;
  every subsequent delivery of the same ID finds the receipt already present (returns 0) and is a
  pure no-op — no re-confirmation, no re-cancellation, no second inventory restoration, no second
  `ORDER_CONFIRMED`/`ORDER_CANCELLED` event.
- **Two different event IDs reporting the same already-applied outcome** (e.g. a payment
  authorization notified twice under different event IDs) — both claim their own receipt row (two
  rows), but the second one's business logic finds the order already `CONFIRMED`/`CANCELLED` and
  returns without mutating anything further or emitting a second outbox event. Event-ID
  deduplication and business-state idempotency are two separate, independently necessary layers —
  the first stops a literal replay from re-running the transaction at all; the second stops a
  *different* event ID that happens to report the same fact from re-applying it.
- **Consumer crash after the transaction commits, before the Kafka offset is acknowledged** — the
  workflow transaction (receipt + order + reservations + outbox event) already committed; Kafka
  redelivers the same record because the offset was never confirmed; redelivery re-enters the same
  method, finds the receipt already present, and safely no-ops. Offset acknowledgment happens
  strictly after the transaction returns, never before — a crash before commit leaves nothing
  committed and Kafka correctly redelivers; a crash after commit but before ack is provably safe by
  the same no-op path.
- **Consumer restart** — this safety is backed entirely by PostgreSQL (the receipt row, the order
  status, the reservation status), not JVM memory. A restarted consumer instance has no in-memory
  state at all and behaves identically to any other redelivery.

## Payment verification: PostgreSQL is authoritative, not the Kafka payload

Before applying `PAYMENT_AUTHORIZED`, the workflow loads the referenced payment from PostgreSQL and
requires `payment.status == AUTHORIZED` and `payment.orderId` to match the event's claimed order ID
— symmetrically for `PAYMENT_FAILED` and `FAILED`. A Kafka record is evidence that an event was
*published*, not proof of current truth; by the time it's consumed, only the database can say what's
actually true right now. A payload that contradicts the database is rejected before any order or
reservation mutation happens — this protects the workflow from stale, corrupted, or (in an
adversarial setting) injected records being trusted for an irreversible transition.

## Expired-reservation conflict: `AUTHORIZED` cannot revive lost inventory

If any reservation belonging to the order is already `RELEASED` or `EXPIRED` by the time an
`AUTHORIZED` event is processed, CommerceCore no longer owns that stock — it may already have been
sold to someone else. Confirming the order anyway would either lie about what's reserved or risk
overselling. This milestone refuses that workflow outright: the transaction rolls back completely
(no receipt, no order mutation, order stays `PENDING`), and the Kafka offset is not acknowledged, so
the event remains retryable. This is deliberately left as **unresolved reconciliation territory** —
CommerceCore does not invent a policy here (re-reserve, refund, partial-fulfill); it only refuses to
silently oversell. See "Current limitations."

The symmetric `FAILED`-after-`EXPIRED` case is not a conflict: an expired reservation already
restored its stock independently, so cancelling the order on top of that requires no further
inventory action — the workflow simply skips reservations already in that state rather than calling
`release` (which would itself reject an `EXPIRED → RELEASED` transition).

## Consumer groups: proof and workflow are independent

`commercecore-proof-consumer` (Milestone 9) and `commercecore-order-workflow` (this milestone) are
two separate Kafka consumer groups reading the same `commerce.events` topic. Kafka delivers every
record to every consumer group independently — the two consumers do not compete for records, do not
share offsets, and do not share a receipt table. Verified directly:
`OrderPaymentWorkflowKafkaTest.bothConsumerGroupsIndependentlyProcessTheSamePaymentEvent` sends one
physical record and asserts both `kafka_event_receipts` (proof) and `order_workflow_event_receipts`
(workflow) end up with a row for the same event ID.

## Irrelevant events and loop prevention

The workflow consumer receives every record on `commerce.events`, including `ORDER_CREATED` and its
own output, `ORDER_CONFIRMED`/`ORDER_CANCELLED`. Only `PAYMENT_AUTHORIZED`/`PAYMENT_FAILED` are
inputs; everything else is acknowledged immediately with no database work at all — no receipt row,
no lookup, nothing. This is what makes an infinite loop structurally impossible rather than merely
avoided by convention: `ORDER_CONFIRMED` cannot trigger the workflow, because the workflow's
`onMessage` method has no code path that does anything with an `ORDER_CONFIRMED` envelope besides
acknowledging it.

## Current limitations

- Payment authorization arriving after a reservation has already expired (or been released) is
  refused, not resolved — reconciling that case (re-reserve if stock allows, or otherwise) is
  explicitly future work, not implemented here.
- `UNKNOWN` payments do not drive this workflow at all — there is no `PAYMENT_UNKNOWN` domain
  event, so an order behind an `UNKNOWN` payment stays `PENDING` until a webhook resolves it or
  reconciliation (a later milestone) intervenes.
- No automatic payment retry: a `FAILED` payment stays `FAILED`; this workflow never re-attempts
  authorization.
- No refunds, no shipping/fulfillment — out of scope for this milestone.
- Single CommerceCore application, single local Kafka broker — the same limitations already
  documented in `docs/kafka.md` apply here too.
- At-least-once messaging throughout; correctness comes from the receipt + business-state
  idempotency described above, not from any exactly-once delivery guarantee.
- No dead-letter policy for a permanently invalid workflow event (malformed payload, or an event ID
  reused with contradictory identity) — such a record is rejected on every delivery attempt and,
  under the existing bounded backoff (`docs/kafka.md`), is eventually logged and skipped rather than
  redelivered forever or routed anywhere durable for manual inspection.
