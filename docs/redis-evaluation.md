# Redis evaluation (Milestone 12)

## Problem evaluated

Whether Redis solves a real, currently-measurable CommerceCore problem — not whether Redis is a
reasonable technology in general. The question was evaluated per candidate use case, against the
actual code that exists today, not against a hypothetical future scale.

## Decision

**SKIP.** No candidate use case has a current, measured problem that Redis would fix. No Redis
dependency, Compose service, or Testcontainer was added.

## Candidate use cases

### A. Catalog cache (SKU → product data)

Checkout must always re-read the authoritative price from PostgreSQL at checkout time
(`CheckoutService.executeCheckout` reads `ProductRepository.findBySku` fresh, per line, every
call) — this is non-negotiable regardless of any cache decision, so a catalog cache could only ever
help the read path (`GET /api/products/{sku}`), never checkout correctness. Measured: 500 reads
against a freshly seeded 1,000-product catalog average **p50 1.02ms / p90 1.58ms / p99 2.88ms**
over HTTP, on a single local PostgreSQL instance, for a primary-key lookup. There is no read
latency problem to solve.

### B. Cart cache

Measured: 300 reads against 100 seeded carts (2 items each) average **p50 1.21ms / p90 1.60ms /
p99 3.09ms**. Same conclusion as catalog: a plain indexed lookup on a small table is already fast.
A cache would add invalidation logic (every `setItemQuantity`/`removeItem` call would need to
invalidate or update the cached copy) to solve a problem that doesn't exist.

### C. Checkout idempotency

`CheckoutIdempotencyRepository.acquireLock` uses `pg_advisory_xact_lock`, held for exactly the
lifetime of the checkout transaction and released automatically on commit or rollback — this is
what lets checkout be all-or-nothing for the same idempotency key. Moving this to Redis would mean
the idempotency guard and the order/reservation/outbox commit are no longer the same atomic unit:
a Redis-side lock or key can't participate in a PostgreSQL transaction, so a crash between "Redis
says done" and "PostgreSQL actually committed" (or the reverse) becomes possible in a way it
structurally cannot today. **REJECT** — this would weaken an already-correct invariant to solve no
problem.

### D. Inventory locking

`InventoryRepository.tryConsume` is one atomic conditional `UPDATE ... WHERE available_quantity >=
:quantity` — the decrement and the "is there enough" check are the same PostgreSQL statement, so
there is nothing a lock could add. A Redis distributed lock would mean acquiring a lock *and then*
running the SQL, introducing lock expiry, lock-ownership, and dual-system split-brain risk (lock
says "held," but the process died before the SQL ran or before releasing it) for a problem the
single atomic statement already solves without any of that. **REJECT**.

### E. Reservation expiration

`ReservationService.expireDueReservations` transitions a reservation to `EXPIRED` and restores its
quantity to `inventory.available_quantity` in one PostgreSQL transaction. A Redis TTL key expiring
is not a transaction — nothing atomically links "the key expired" to "the reservation row changed
and the inventory row changed together." If Redis expired the key but the corresponding PostgreSQL
update never ran (crash, network partition, Redis restart with no persistence), the reservation
would be silently wrong forever with no record of the discrepancy. **REJECT as source of truth**.

### F. Rate limiting

CommerceCore has no authentication and is a single local application instance — there is no
multi-instance deployment for cross-process rate limiting to coordinate across, and abuse
protection has never been in scope. This is the most plausible *future* Redis use, but implementing
it now would be solving a problem CommerceCore doesn't have yet. **Not implemented; recorded as a
concrete future trigger** below.

### G. Kafka consumer deduplication

`KafkaEventReceiptRepository`/`OrderWorkflowEventReceiptRepository` both use
`INSERT ... ON CONFLICT DO NOTHING` against a real PostgreSQL table, and — critically — the
workflow receipt commits in the *same transaction* as the business side effect it guards (order
confirmation, reservation release). Redis-backed dedup would decouple those two things: the receipt
could be marked in Redis while the PostgreSQL business transaction rolls back, or vice versa, and a
Redis restart/flush would forget every receipt while PostgreSQL still remembered its own state —
reintroducing exactly the duplicate-processing risk this dedup exists to prevent. **REJECT**.

### H. Reconciliation batching

`PaymentReconciliationService.reconcileUnknownBatch` already uses a `Pageable`-bounded query
(`findByStatusOrderByCreatedAtAsc`, default limit 25) — never a full table scan. Measured: 10 batch
calls (limit 25) against a freshly seeded set of UNKNOWN payments completed in single-digit to
double-digit milliseconds each. No throughput or latency problem exists to justify a Redis-backed
queue.

### I. Sessions

CommerceCore has no authentication and no user sessions. Redis session storage solves a problem
that does not exist in this project. **REJECT**.

## Measurements

Performed against a real `docker compose up -d` (PostgreSQL 16 + Kafka) and `./gradlew bootRun`,
seeded with 1,000 products and 100 carts (2 items each) — a modest, reproducible local dataset, not
a claim of production scale:

| Operation | Sample size | p50 | p90 | p99 |
|---|---|---|---|---|
| `GET /api/products/{sku}` | 500 | 1.02ms | 1.58ms | 2.88ms |
| `GET /api/carts/{cartId}` | 300 | 1.21ms | 1.60ms | 3.09ms |
| `POST /api/carts/{cartId}/checkout` | 50 | 5.61ms | 7.70ms | — |
| `POST /api/dev/reconciliation/run?limit=25` | 10 | — | — | max 80.84ms (cold), single-digit ms thereafter |

These are real, one-off local measurements against the actual running application — not fabricated,
and not a claim about behavior under concurrent load or at a larger scale. They answer the one
question this milestone asks: is PostgreSQL currently a bottleneck for CommerceCore's actual
read/write paths? No. A single-row indexed PostgreSQL lookup over local HTTP is already
sub-2-millisecond at this scale; there is nothing here for a cache to meaningfully speed up.

## Correctness cost of each Redis pattern, restated

| Pattern | Cost |
|---|---|
| Cache | Invalidation logic, staleness windows, a second place to reason about "what is currently true" |
| Distributed lock | Lock expiry vs. actual work duration, lock ownership after a crash, split-brain between lock state and DB state |
| TTL-based expiry | Not atomic with the PostgreSQL transaction it would need to trigger — a lost link between "expired" and "compensated" |
| Cache-backed idempotency/dedup | State that can diverge from the PostgreSQL transaction it's supposed to guard, on restart or flush |

## Decision matrix

```text
Use case              Current problem?   Redis benefit         Correctness cost              Decision
Catalog cache          No (1-3ms reads)   None measured         Invalidation, stale price     REJECT
Cart cache             No (1-3ms reads)   None measured         Invalidation                  REJECT
Checkout idempotency   No                 None                  Breaks atomicity w/ order tx  REJECT
Inventory locking      No                 None (SQL is atomic)  Lock expiry, split-brain       REJECT
Reservation expiry     No                 None                  TTL not atomic w/ restore     REJECT
Rate limiting          No auth, no scale  N/A yet                N/A yet                       DEFER
Kafka dedup            No                 None                  Decouples receipt from tx     REJECT
Reconciliation batch   No (bounded, fast) None                  N/A                            REJECT
Sessions               No auth exists     None                  N/A                            REJECT
```

## Revisit triggers

Concrete, not vague. Reconsider Redis only if one of these actually occurs:

- A measured catalog- or cart-read p90/p99 latency that is actually a problem for a real workload
  (not synthetic local numbers like the ones above) — e.g. sustained concurrent load testing shows
  PostgreSQL read latency growing unacceptably under realistic traffic.
- CommerceCore gains authentication and is deployed as more than one instance, making
  cross-process rate limiting an actual requirement (a single instance can rate-limit in memory;
  Redis only becomes necessary once state must be shared across processes).
- A genuinely high-volume ephemeral counter appears (e.g. a per-second metric or a short-lived
  deduplication window measured in seconds, not something that needs to survive a restart) where
  losing the data on a restart is explicitly acceptable.
- A read-heavy, rarely-changing workload emerges with clear, simple invalidation semantics (e.g. a
  catalog that changes rarely and is read far more often than today's tests exercise) — and even
  then, only as a cache-aside layer that CommerceCore must remain correct without if Redis is down.

## What must never happen regardless of any future Redis adoption

- PostgreSQL remains the sole source of truth for orders, payments, inventory, reservations,
  outbox, and all dedup/idempotency state.
- Any future Redis use must be a derived, optional accelerator — CommerceCore must stay correct
  (no overselling, no duplicate payment, no lost order) if Redis is unavailable, merely slower.
