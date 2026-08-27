# Payment webhooks

## Why webhooks are at-least-once

A provider that delivers a webhook and doesn't get a fast, successful (2xx) response back will
retry — it has no way to know whether CommerceCore actually received and processed the event, or
whether the request got lost, or whether CommerceCore crashed mid-processing. So the same logical
event can arrive:

```text
once
twice
ten times
concurrently, from retried connections racing each other
after CommerceCore already resolved the payment some other way
```

This is normal, expected provider behavior — not a bug to work around, a property to design for.
CommerceCore's job is to make the *effect* of receiving `evt_123` the same regardless of how many
times the bytes actually arrive.

**This is not exactly-once delivery.** The provider may still physically send the same event
many times; CommerceCore does not — cannot — prevent that. What CommerceCore guarantees is
**at-least-once delivery, processed idempotently**: however many times `evt_123` arrives, its
effect on payment state happens once.

## Provider event identity

A webhook payload carries three separate identities, and conflating any two of them is a bug:

```text
provider_event_id   -- identifies this specific delivery notification (e.g. "evt_123")
providerRequestId   -- CommerceCore's payment.id, the stable identity Milestone 6 already
                        established for "which payment is this about"
providerReference    -- the provider's own charge/authorization identifier (e.g. "pay_abc123")
```

`provider_event_id` is not payment identity — a single payment can legitimately be the subject of
several *different* event IDs (see "distinct events, same outcome" below). `providerRequestId`
reuses Milestone 6's existing contract (`payment.id`, generated once at payment creation and never
regenerated) rather than inventing a second identity mapping.

## Event receipt table

```sql
CREATE TABLE payment_webhook_events (
    provider_event_id  VARCHAR(255) PRIMARY KEY,
    payment_id         UUID NOT NULL REFERENCES payments (id),
    event_type         VARCHAR(32) NOT NULL CHECK (event_type IN ('PAYMENT_AUTHORIZED', 'PAYMENT_DECLINED')),
    provider_reference VARCHAR(255) NULL,
    received_at        TIMESTAMPTZ NOT NULL
);
```

`PRIMARY KEY(provider_event_id)` is what makes "one event ID processed once" a database fact.
No raw HTTP request, headers, or JSON blob is stored — the receipt only keeps what's needed to
detect a duplicate and to detect payload reuse (see below).

## Two independent layers of idempotency

**Layer 1 — same event ID twice.** `PRIMARY KEY(provider_event_id)` plus
`INSERT ... ON CONFLICT (provider_event_id) DO NOTHING`: the same event ID can only ever be
claimed once. A duplicate delivery of `evt_123` is recognized purely from the insert's
affected-row count.

**Layer 2 — different event IDs, same outcome.** A provider is free to notify CommerceCore of the
same underlying authorization more than once, using *different* event IDs each time (this is
legitimate provider behavior, not a bug on their end). Layer 1 alone would let each of those
insert its own receipt and *also* re-run the payment transition — which is unsafe on its own.
Layer 2 is the payment's own state machine being idempotent at the state level: `AUTHORIZED +
AUTHORIZED(same reference)` is a no-op, `FAILED + DECLINED` is a no-op, regardless of how many
distinct event IDs produced those repeated deliveries. Both layers are necessary; neither
subsumes the other.

## Duplicate-event concurrency: PostgreSQL, not application code

`PaymentWebhookEventRepository.tryClaimEvent` is the mechanism, and it's the same idiom already
proven by `InventoryRepository.tryConsume`, the cart upsert, and `PaymentRepository.tryCreatePayment`:

```sql
INSERT INTO payment_webhook_events (provider_event_id, payment_id, event_type, provider_reference, received_at)
VALUES (:eventId, :paymentId, :eventType, :providerReference, :now)
ON CONFLICT (provider_event_id) DO NOTHING
```

Twenty concurrent deliveries of the same event ID all attempt this INSERT. PostgreSQL's unique
index guarantees exactly one affects a row; every other transaction's INSERT for the same
conflicting key **blocks** until the winner's transaction commits or rolls back, then correctly
sees the conflict (or, if the winner rolled back — see "terminal conflicts" below — finds no
conflict at all and becomes the new winner itself). No `ConcurrentHashMap`, no
`synchronized`, no application-level lock: the affected-row count is the entire decision.

## Payment row locking

Event-ID uniqueness alone only stops the *same* event ID being processed twice. It does nothing
for `evt_1` and `evt_2` — two different, both legitimately-claimed event IDs — racing to apply a
transition to the *same* payment concurrently. `PaymentRepository.findByIdForUpdate`
(`SELECT ... FOR UPDATE`, the same pattern already proven by
`ReservationRepository.findByIdForUpdate`) closes that gap: after claiming its own event ID, each
webhook transaction locks the payment row before inspecting or changing its status, serializing
distinct concurrent events against the same payment one at a time.

## Transaction boundary: event receipt and transition commit together

`PaymentWebhookService.processWebhook` is a single `@Transactional` method — deliberately unlike
`PaymentService.initiatePayment` (Milestone 6), which had to split into three phases because it
makes a real external call. A webhook *is* the external evidence already arrived; there is no
further external call to make, so nothing here needs to be split. Claiming the event and applying
the transition happen in one transaction:

```text
BEGIN
INSERT event receipt (claim)
  -> claimed?
SELECT payment FOR UPDATE (lock)
apply transition (or throw, on conflict)
COMMIT
```

If the event receipt committed on its own but the transition failed, a provider retry would see
"already processed" and never get a chance to apply the missing state change. If the transition
applied but the receipt rolled back, a retry could re-run a transition that already happened. One
transaction makes both mismatches impossible.

**Terminal conflicts roll back the receipt too.** If the claimed event's transition turns out to
be illegal (`AUTHORIZED → FAILED`, `FAILED → AUTHORIZED`, or a provider-reference mismatch on an
already-`AUTHORIZED` payment), `applyTransition` throws, and the whole transaction — including the
just-inserted event receipt — rolls back. This keeps "the event was processed" meaning exactly
"was accepted and applied," never "was received but rejected." A retried delivery of a genuinely
conflicting event hits the same conflict every time, rather than being silently swallowed after
the first attempt.

## Payment transition table

```text
PENDING  + AUTHORIZED  -> AUTHORIZED (reference persisted)
PENDING  + DECLINED    -> FAILED
UNKNOWN  + AUTHORIZED  -> AUTHORIZED (reference persisted)   <- Milestone 6's UNKNOWN, resolved
UNKNOWN  + DECLINED    -> FAILED
AUTHORIZED + AUTHORIZED(same reference)    -> no-op (200)
AUTHORIZED + AUTHORIZED(different reference) -> 409 conflicting_payment_event
AUTHORIZED + DECLINED                      -> 409 conflicting_payment_event
FAILED + DECLINED                          -> no-op (200)
FAILED + AUTHORIZED                        -> 409 conflicting_payment_event
```

`AUTHORIZED` and `FAILED` remain terminal — nothing in this milestone transitions out of either.

## UNKNOWN resolution — the milestone's centerpiece

Milestone 6's scenario: the provider actually authorized a payment, but CommerceCore's synchronous
call timed out before the response arrived, so `payment.status = UNKNOWN` and
`provider_reference = NULL`. When the provider later delivers a `PAYMENT_AUTHORIZED` webhook
carrying its reference for that same `providerRequestId`, that webhook *is* the authoritative
evidence CommerceCore was missing — `UNKNOWN → AUTHORIZED`, reference persisted. This is stronger
than assuming "timeout means it probably failed, so let's treat it as FAILED": that assumption
would have been proven wrong the moment authoritative evidence arrived, and there would be no way
back from an incorrectly-recorded FAILED state under this milestone's terminal-state rules.

## Provider-reference consistency

For an already-`AUTHORIZED` payment, an incoming `AUTHORIZED` event's `providerReference` must
equal the one already stored — checked by direct equality (`Objects.equals`), not by ignoring it.
A matching reference is a safe no-op; a different one is rejected as `conflicting_payment_event`.
`provider_reference` is never silently overwritten.

## Response semantics

Both a first-time accepted delivery and an identical duplicate delivery return `200 OK` with the
current payment state — providers commonly retry any non-2xx response, so a validly-processed
duplicate has to be acknowledged as success, not treated as an error that would trigger yet more
retries. Malformed payloads and conflicts return `4xx` (`400 invalid_webhook_event`,
`404 payment_not_found`, `409 provider_event_id_reused` / `409 conflicting_payment_event`).

## No signature verification

**This endpoint has no authenticity verification.** There is no HMAC signature check, no shared
secret, nothing that proves a request actually came from a real provider rather than anyone who
can reach the endpoint. This is acceptable only because there is no real provider yet — the fake
provider's events are demonstrated by POSTing directly to `/api/webhooks/payments`, which is fine
for a development-only fake but must never be mistaken for production webhook security.

## No order/reservation workflow yet

Even when a webhook resolves a payment all the way to `AUTHORIZED`, the order stays `PENDING` and
its reservations stay `ACTIVE` — this milestone is exactly "external event → correct payment
state," not "payment state → business workflow." Likewise, a webhook that resolves a payment to
`FAILED` does not release the reservation or return inventory. Coordinating payment outcomes with
order/reservation transitions is a deliberately separate, later concern.

## Diagram

```text
Provider
   |
   | evt_123 AUTHORIZED
   v
Webhook Controller
   |
   v
INSERT event receipt (claimed)
   |
   v
LOCK payment
   |
   v
UNKNOWN -> AUTHORIZED
   |
   v
COMMIT

Duplicate evt_123:

Provider
   |
   v
Webhook Controller
   |
   v
event already exists (not claimed)
   |
   v
200 OK
(no transition)
```
