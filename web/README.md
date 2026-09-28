# CommerceCore Control Room

An optional engineering interface for inspecting and exercising the CommerceCore correctness
backend. This is not a customer storefront.

## Technology

Next.js App Router, React, TypeScript, Tailwind CSS, shadcn/ui conventions, and ESLint.

## Prerequisites

- Node.js 20.9 or newer
- npm
- Java 21 and Docker for the CommerceCore stack

## Run locally

Start PostgreSQL, Kafka, the Payment Service, and CommerceCore from the repository root:

```bash
docker compose up -d
./gradlew bootRun
```

In another terminal, install and start the frontend:

```bash
cd web
cp .env.example .env.local
npm install
npm run dev
```

Open <http://localhost:3000>. CommerceCore listens on <http://localhost:8080> in the documented
local workflow.

`COMMERCECORE_API_URL` configures the backend target. Next.js proxies
`/api/commercecore/*` to the backend's `/api/*` routes, keeping browser requests same-origin and
avoiding a global backend CORS change. Restart the frontend after changing the value.

## Store Simulator

Open `/store` to:

- load the real CommerceCore catalog and per-SKU inventory;
- create a server-side cart (the browser remembers only its ID);
- set, change, and remove cart lines through the cart API;
- submit checkout with an editable `demo-checkout-<UUID>` idempotency key; and
- follow a successful checkout to the authoritative `/orders/{id}` inspector.

The cart panel's estimated total uses current catalog prices for display only. CommerceCore
calculates and snapshots the authoritative order total during checkout.

The same checkout key is preserved after any rejection or transport failure so it can be retried
deliberately. The UI never retries automatically. An HTTP business rejection is shown as a
rejection; a network or unreadable-response failure warns that the checkout outcome may be
unknown. CommerceCore's persistent idempotency—not the disabled checkout button—protects the
logical checkout.

## Order Inspector

Use `/orders` to enter an order ID, or follow checkout directly to `/orders/{id}`. The
inspector reads current order, payment, reservation, provider-observation, and reconciliation state
from the purpose-specific read-only inspection endpoint. Manual refresh explicitly refetches this
state; there is no polling. If refresh fails, the previous result is labeled as the last successful
state rather than presented as newly current.

The **Current state** section reports stored values at fetch time. The **State model** section is
documented possibility only and is explicitly not an observed timeline. CommerceCore does not
expose historical events here; that evidence remains future Event Explorer scope. `UNKNOWN` means
provider authorization or failure is not yet authoritative, and `REQUIRES_REVIEW` means provider
truth cannot be applied automatically without violating or contradicting stored business state.
The inspector offers no mutation, payment, failure, or reconciliation controls.

## Event Explorer

Use `/events` to inspect the newest 100 persisted transactional-outbox records, filter by event or
aggregate identity, and open a linkable `/events/{eventId}` evidence view. The frontend calls the
read-only `/api/dev/events` inspection API, so CommerceCore must run with its `dev` profile (the
documented `./gradlew bootRun` workflow enables `kafka,dev`). The endpoint is absent without that
profile.

An outbox row proves that a business fact and publication intent committed locally. `published`
means the publisher's sink call returned successfully and CommerceCore subsequently marked that
row; it does not prove a consumer processed the record. A consumer receipt is separate PostgreSQL
evidence that the named consumer committed its logical processing for the event ID. Those receipts
make logical effects idempotent under redelivery, while Kafka transport remains at least once.

The explorer cannot prove how many physical Kafka delivery attempts occurred, whether an
unpublished event reached Kafka before an acknowledgment was lost, or exactly-once transport,
because CommerceCore does not persist that evidence. Publisher claim counts are labeled separately
and are not presented as Kafka delivery counts. Payloads contain the existing commerce-domain JSON
stored in the outbox; no credentials or transport headers are stored there. Inspection and refresh
perform no publication, replay, retry, or domain mutation.

## Concurrency Lab

`/experiments` is a development-only correctness laboratory. One backend request prepares isolated
`EXP-*` domain state, coordinates bounded worker threads with ready/start latches, invokes the real
reservation, checkout, provider gRPC, or consumer-receipt path, and reads authoritative state after
the workers finish. The browser never creates the race.

Implemented experiments are inventory contention (default 100 buyers / 10 units), concurrent
checkout idempotency, provider request idempotency, and duplicate consumer-receipt processing. The
worker/attempt range is 2–200, inventory is 0–10,000, quantity is 1–100, and backend execution is
bounded to 30 seconds with executor cleanup. A timed-out experiment is `INCOMPLETE`, never `PASS`.

| Experiment | Existing evidence | Real path and coordination | Database invariant | Authoritative result |
|---|---|---|---|---|
| Inventory contention | inventory/reservation concurrency tests | latched workers call `ReservationService.reserve` | conditional decrement and nonnegative check | inventory row plus worker outcomes |
| Checkout idempotency | 20-way checkout retry test | latched workers call transactional checkout | advisory lock and key primary key | checkout mapping plus returned order identities |
| Provider idempotency | 20-way TCP RPC test | latched workers call `PaymentProvider.authorize` | provider advisory lock and request primary key | read-only provider lookup plus logical responses |
| Duplicate event processing | duplicate Kafka/receipt tests | latched workers call the proof consumer's receipt primitive | receipt event-ID primary key with `ON CONFLICT` | persisted receipt row and insert counts |

Inventory `PASS` is derived from persisted final availability and the conservation equation; it
does not claim every intermediate value was observed. Provider evidence uses a read-only provider
lookup, but CommerceCore cannot count the provider service's private rows. Duplicate-event attempts
invoke the same persistent receipt primitive used by the proof consumer; they are not physical Kafka
delivery counts. These are controlled correctness experiments, not throughput or scalability tests,
and they create durable development data without automatic cleanup.

## Failure Lab

Use `/failure-lab` to run three controlled Payment Service outcomes through real CommerceCore APIs:
normal authorization, an explicit decline, and authorization committed remotely followed by a
caller timeout. Every run creates fresh product, cart, checkout, order, and payment identifiers.
The provider control is one-shot and resets to normal success after the next authorization.

The page deliberately separates the configured **Injected behavior**, the response CommerceCore
**Observed**, and the authoritative **Persisted state** returned by Order Inspector. A timeout is
shown as an ambiguous `UNKNOWN` payment, not a decline. The reconciliation action appears only for
`UNKNOWN`; it invokes CommerceCore's development reconciliation endpoint, which looks up the
existing payment/provider-request UUID and never submits another authorization.

Failure Lab requires both CommerceCore's `dev` profile and the Payment Service's `dev` profile.
The documented Compose and `bootRun` workflow enables both. `PAYMENT_PROVIDER_API_URL` configures
the frontend proxy target and defaults to `http://localhost:8091`. Results link to Order Inspector
and Event Explorer for deeper persisted evidence. The UI does not expose authorization call counts,
provider truth before lookup, arbitrary event publication, Kafka replay, reservation expiry, or
generic infrastructure fault injection.

## Current scope and limitations

The frontend includes the responsive Control Room shell, functional Store Simulator, read-only
Order Inspector and Event Explorer, and guided development-only Failure and Concurrency Labs.
Infrastructure status remains neutral because no health aggregation endpoint exists. The backend
catalog contains only products explicitly created through `POST /api/products`; an empty catalog
is shown as-is.

Arbitrary mutation controls, Kafka replay, background experiment history, and performance
benchmarking remain intentionally unimplemented.

## Checks

```bash
npm run lint
npm run build
```
