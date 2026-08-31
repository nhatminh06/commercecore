# Payment reconciliation

## Why timeout is ambiguous

`PaymentProviderTimeoutException` means CommerceCore asked the provider to authorize a payment and
never received an answer — not that the answer was "no." The provider may have processed the
request and lost the response on the way back. `PaymentStatus.UNKNOWN` (Milestone 6) records this
honestly instead of guessing. Retrying `authorize()` on an UNKNOWN payment would risk a second real
charge if the first one actually succeeded — this project's fake provider models real-world
provider-side idempotency (the same `providerRequestId` returns the same result), but a real
processor's guarantees here are exactly the kind of thing you cannot assume without proof. The
correct move is not "try again" — it's "ask what actually happened."

## Reconciliation queries; it never authorizes

```text
UNKNOWN
   ↓
provider lookup
   ├── AUTHORIZED
   │      ↓
   │   payment AUTHORIZED
   │      ↓
   │   PAYMENT_AUTHORIZED
   │      ↓
   │   Kafka workflow
   │
   ├── DECLINED
   │      ↓
   │   payment FAILED
   │      ↓
   │   PAYMENT_FAILED
   │      ↓
   │   Kafka workflow
   │
   └── NOT_FOUND
          ↓
       remain UNKNOWN
       case OPEN
```

`PaymentProvider.lookup(providerRequestId)` is read-only — it must never authorize, retry
authorization, or create/mutate provider-side state. At runtime it invokes remote
`LookupPayment`; the Payment Service reads `provider_payments`. Transport failure is not
`NOT_FOUND`. End-to-end tests prove authorization count stays unchanged across reconciliation.

## Provider request identity

`providerRequestId` is CommerceCore's own payment UUID (`payment.getId()`) — the same stable
identity `PaymentService.initiatePayment` already passes to `authorize()`. Reconciliation reuses it
unchanged for `lookup()`, so the provider can recognize a query about a request it actually
received.

## Provider lookup states vs. local payment states

`ProviderPaymentStatus` (`AUTHORIZED`/`DECLINED`/`NOT_FOUND`) is a deliberately separate model from
`PaymentStatus` (`PENDING`/`AUTHORIZED`/`FAILED`/`UNKNOWN`). They share two names by coincidence,
not by identity: a provider's `AUTHORIZED` is what actually happened on the provider's side; a
local payment's `AUTHORIZED` is what CommerceCore currently believes and has committed. Reconciling
one into the other is exactly the mapping this milestone implements — never assume they're
interchangeable.

## Reconciliation states

- **OPEN** — provider truth is still unresolved (`NOT_FOUND` for a still-unresolved local
  payment). Retry later; nothing to apply yet.
- **RESOLVED** — provider truth became definitive and CommerceCore safely applied it (or already
  matched it).
- **REQUIRES_REVIEW** — provider truth is definitive, but automatic business repair is unsafe or
  contradicts what CommerceCore already recorded. Durable evidence for a human or a future policy
  — never guessed through automatically.

One row per payment (`UNIQUE(payment_id)`), not a history table — each reconciliation attempt
re-affirms or updates the same row (`attempt_count` increments every call).

## UNKNOWN → AUTHORIZED

```text
BEGIN
lock payment (re-read — it may have changed since the provider was queried)
PaymentTransitionService.resolveToAuthorized(...)   -- payment AUTHORIZED + PAYMENT_AUTHORIZED outbox, atomically
check: is every reservation for the order still ACTIVE/CONFIRMED?
  yes -> case RESOLVED
  no  -> case REQUIRES_REVIEW, reason AUTHORIZED_WITHOUT_RESERVED_STOCK
COMMIT
```

The payment transition and the domain event are the existing, unmodified Milestone 6/8 mechanism
(`PaymentTransitionService`) — reconciliation never mutates `payments.status` directly and never
writes an outbox row itself. The existing Kafka order workflow (Milestone 10) then reacts to
`PAYMENT_AUTHORIZED` exactly as it always has.

## UNKNOWN → FAILED

Same shape, using `PaymentTransitionService.resolveToFailed`. No reservation-safety check is
needed here: a `FAILED` outcome never claims inventory ownership, so there's nothing to lose.

## NOT_FOUND stays unresolved

For this fake provider, `NOT_FOUND` only ever means "I have no record of this specific request ID"
— it says nothing about whether a charge occurred. Treating it as proof of decline would be
inventing a stronger guarantee than the provider actually offers. A still-unresolved local payment
(`PENDING`/`UNKNOWN`) with a `NOT_FOUND` lookup stays `UNKNOWN`; the case becomes/stays `OPEN`
(`attempt_count` still increments — the attempt happened, it just didn't resolve anything), ready
for a later reconciliation attempt once more provider-side state might exist. A real provider
integration would need to prove its own `NOT_FOUND` contract explicitly before this could ever be
treated as a decline.

## Local/provider contradictions are never silently reversed

```text
local AUTHORIZED, provider DECLINED  -> REQUIRES_REVIEW (LOCAL_AUTHORIZED_PROVIDER_DECLINED)
local FAILED,     provider AUTHORIZED -> REQUIRES_REVIEW (LOCAL_FAILED_PROVIDER_AUTHORIZED)
```

Both `AUTHORIZED` and `FAILED` are terminal local states (Milestone 6). Reconciliation never flips
a terminal state based on a later, contradicting provider read — that would be trusting the newer
observation over the one CommerceCore already committed to and acted on (an outbox event may
already have been published and consumed). The contradiction itself is durable, explicit evidence
instead.

## Provider-reference mismatch

If the local payment is `AUTHORIZED` with reference `pay_A` and a later lookup reports `AUTHORIZED`
with a *different* reference `pay_B`, that's not a matching confirmation — it's a mismatch. The
case becomes `REQUIRES_REVIEW` (`PROVIDER_REFERENCE_MISMATCH`); the stored reference is never
overwritten with the new one.

## AUTHORIZED after reservation expiry: the central unsafe case

```text
provider AUTHORIZED
+
reservation EXPIRED
      ↓
payment truth = AUTHORIZED   (the customer really did pay — this is still recorded)
      ↓
DO NOT confirm order
DO NOT re-reserve
      ↓
REQUIRES_REVIEW
```

Payment truth and inventory ownership are separate facts. The customer paying does not change
whether CommerceCore still owns the stock it would need to fulfill the order — a reservation that
expired (or was released) already returned that stock to the available pool, possibly to another
buyer. Recording the payment as `AUTHORIZED` while refusing to confirm the order is not a
contradiction; it's two independently true statements. `ReservationService.isOwnershipIntactForOrder`
is the single shared check both `PaymentReconciliationApplier` and `OrderPaymentWorkflowService`
use to decide this — the same definition of "safe" in both places, not two independently
implemented and possibly-diverging rules.

## Why the Kafka workflow no longer retries this conflict forever

Before this milestone, `OrderPaymentWorkflowService` rejected an `AUTHORIZED` event against a lost
reservation by throwing — rolling back the receipt and leaving the Kafka offset unacknowledged, so
the same record redelivered indefinitely. That is correct behavior for a *transient* failure (a
database hiccup) but wrong for a *permanent* one: no amount of redelivery can recreate inventory
that's already gone. This milestone changes that one branch: on detecting the same lost-reservation
condition, the workflow now commits the workflow receipt, upserts (or re-affirms) the same
`payment_reconciliation_cases` row `PaymentReconciliationApplier` would have written, leaves order
and reservation state untouched, and acknowledges the Kafka record — converting an endless retry
loop into one piece of durable evidence, checked once, not re-checked forever.

```text
transient technical failure                 permanent business inconsistency
(PostgreSQL unavailable)                     (payment AUTHORIZED, reservation EXPIRED)
      ↓                                             ↓
throw                                        persist REQUIRES_REVIEW
      ↓                                             ↓
Kafka redelivers — may succeed later          acknowledge — retrying cannot fix inventory ownership
```

## Why automatic re-reservation and refunds are not implemented

Re-reserving stock automatically after discovering `AUTHORIZED + EXPIRED` could oversell (the stock
may already belong to someone else) or unfairly jump the order ahead of others who reserved it
since. A refund is a distinct financial operation this project has never implemented, with its own
correctness properties (idempotency, provider API, accounting) that deserve their own milestone,
not a side effect bolted onto reconciliation. `REQUIRES_REVIEW` exists specifically so this decision
is deferred to an explicit, deliberate future policy — refund, re-reservation, or operator
intervention — not guessed at automatically here.

## Batch reconciliation

`PaymentReconciliationService.reconcileUnknownBatch(limit)` loads at most `limit` UNKNOWN payments
(oldest first, via a `Pageable`-bounded query — never a full table scan) and reconciles each
independently. One payment's provider or database failure is caught and does not abort or roll back
any other payment's reconciliation in the same batch. No scheduler exists yet — batch and
single-payment reconciliation are both invoked explicitly (tests, or the development-only
`POST /api/dev/reconciliation/run` / `POST /api/dev/payments/{paymentId}/reconcile` endpoints,
which exist only under the `dev` profile).

## Interaction with the Kafka order workflow

Reconciliation and the order workflow (Milestone 10) never duplicate each other's job. Reconciling
UNKNOWN → AUTHORIZED does exactly what a synchronous provider success or an authoritative webhook
already does: transition the payment and write `PAYMENT_AUTHORIZED` via `PaymentTransitionService`.
The Kafka workflow consumer picks that event up the same way regardless of which of the three paths
produced it, and its own payment-state verification (Milestone 10) still re-checks PostgreSQL
before acting — reconciliation doesn't get a shortcut around that check.

## Webhook vs. reconciliation race

An authoritative webhook and a reconciliation attempt can genuinely race for the same UNKNOWN
payment. Both ultimately call into the same lock-and-decide mechanism: the webhook path locks the
payment (`PaymentRepository.findByIdForUpdate`) inside `PaymentWebhookService.processWebhook`;
reconciliation's apply phase does the same inside `PaymentReconciliationApplier.apply`. Whichever
transaction acquires the row lock first performs the real transition and writes the one
`PAYMENT_AUTHORIZED` outbox event; the second transaction blocks until the first commits, then
re-reads the payment — now already `AUTHORIZED` with a reference that matches (both paths are
querying/reporting the same provider truth) — and takes the no-op branch. Neither path needs to
know about the other; the row lock is the entire coordination mechanism.

## Transient vs. permanent failure, restated

| | Transient technical failure | Permanent business inconsistency |
|---|---|---|
| Example | PostgreSQL briefly unavailable | Payment AUTHORIZED, reservation EXPIRED |
| Right response | Throw, let Kafka/the caller retry | Persist REQUIRES_REVIEW, acknowledge, stop retrying |
| Why | The same operation will likely succeed later | Retrying can never recreate lost inventory ownership |

## Current limitations

- Fake provider only — no real payment processor's reconciliation/lookup API has been integrated.
- The extracted provider is simulated rather than connected to a real processor. Its ledger is
  persistent PostgreSQL and survives Payment Service restart.
- No automatic refund workflow.
- No automatic re-reservation.
- `REQUIRES_REVIEW` has no operator UI — SQL/API inspection is the only interface for now.
- No scheduler triggers reconciliation automatically; it is invoked explicitly.
- No shipping/fulfillment.
- One CommerceCore process, one Payment Service process, a static gRPC address, and one local Kafka
  broker; there is no service discovery, TLS, or multi-instance deployment.
