# Checkout idempotency

## Why retries happen, and why they're dangerous here

A client sends `POST /api/carts/{cartId}/checkout`. The server reserves stock, creates the
order, and commits — then the response is lost (network drop, client timeout, proxy hiccup). The
client cannot tell "the request never arrived" apart from "it arrived and succeeded, but the
answer got lost." A correct client retries. Without anything to recognize that retry as *the
same logical request*, the server would run checkout again: reserve the same stock a second
time, create a second order. This is exactly what Milestone 4 left open, by design, until this
milestone.

```text
Client                          CommerceCore
  |  POST checkout                   |
  |  Idempotency-Key: checkout-abc   |
  |---------------------------------->
  |                                  | creates Order 123, commits
  |         X response lost X        |
  |<- - - - - - - - - - - - - - - - -|
  |  (client doesn't know what happened)
  |
  |  POST checkout                   |
  |  Idempotency-Key: checkout-abc   |
  |---------------------------------->
  |                                  | finds existing mapping -> Order 123
  |<---------------------------------|
  |  200, Order 123 (not a new order)|
```

## Key semantics

`Idempotency-Key` is a required header on `POST /api/carts/{cartId}/checkout`. It is treated as
**opaque** — never lowercased, never trimmed except to detect a blank/whitespace-only value
(rejected the same as a missing header), never parsed. `"ABC"` and `"abc"` are different keys.
Maximum length is 255 characters (matching the `VARCHAR(255)` column), rejected explicitly rather
than silently truncated by the database.

The logical request this milestone identifies is **`cartId` alone** — checkout has no other
input. So:

- **same key + same cart** → the same logical request, replayed. Returns the original order.
  Checkout is not re-run: no re-reservation, no re-pricing, regardless of what the cart or
  catalog looks like now.
- **same key + different cart** → a different logical request reusing an already-claimed key.
  Rejected with `409 idempotency_key_reused`. The original mapping is never overwritten, and the
  second cart is never silently resolved to the first cart's order.

This is intentionally narrow. A more general system might fingerprint the full request body
(e.g. a SHA-256 hash of a JSON payload) to detect "same key, different request." Checkout accepts
no body, so `cartId` already *is* the complete request identity — inventing a fingerprinting
scheme on top would be solving a problem that doesn't exist yet.

## Schema

```sql
CREATE TABLE checkout_idempotency (
    idempotency_key VARCHAR(255) PRIMARY KEY,
    cart_id         UUID NOT NULL REFERENCES carts (id),
    order_id        UUID NOT NULL REFERENCES orders (id),
    created_at      TIMESTAMPTZ NOT NULL
);
```

No response-body blob, no HTTP status blob, no endpoint name, no TTL/`expires_at`. `order_id` is
`NOT NULL`: a row only ever exists after a checkout has fully succeeded (see below), so there's
no partial/in-progress row to represent.

## Persistence — why JVM memory can't do this

The mapping lives in PostgreSQL, not in a `ConcurrentHashMap`, `Caffeine`, or any other
process-local structure. The guarantee has to survive:

- **Application restart** — an in-memory map is gone the moment the process exits; the table
  isn't.
- **Multiple application instances** — two instances of CommerceCore behind a load balancer share
  the same PostgreSQL database, but each has its own JVM heap. Only shared, external state can
  coordinate them.

## Concurrency: how PostgreSQL prevents duplicate execution, not just duplicate rows

A naive "SELECT the mapping, if absent INSERT after checkout" is not enough. Twenty concurrent
requests for the same key could **all** SELECT and see nothing before any of them INSERTs —
each would then proceed to run the entire checkout (reserve stock, create an order) in its own
transaction. A unique constraint on `idempotency_key` would only reject the *second and later
`INSERT`s* — by then, up to twenty separate orders and reservations could already be committed.
The constraint catches a duplicate *row*; it does nothing to stop duplicate *execution*.

`checkoutIdempotently` closes this gap with `pg_advisory_xact_lock`, a PostgreSQL
transaction-scoped advisory lock:

```sql
SELECT pg_advisory_xact_lock(hashtextextended(:key, 0));
```

`hashtextextended` deterministically maps the arbitrary-length key string to a 64-bit lock ID.
The call **blocks** the calling transaction until it can acquire that lock ID, and the lock is
**released automatically** at `COMMIT` or `ROLLBACK` — never leaked past the transaction's
lifetime, never requiring explicit unlock code.

With twenty concurrent requests for the same key:

1. All twenty call `acquireLock` with the same derived lock ID.
2. PostgreSQL lets exactly one through; the other nineteen block, queued, inside the database
   itself — not spinning, not racing at the application layer.
3. The winner finds no mapping, runs the full checkout, inserts the mapping row, and commits —
   which releases the lock.
4. The next waiter in line acquires the lock, now finds the mapping *does* exist, and returns the
   existing order without touching inventory or reservations at all.
5. This repeats until all twenty have resolved to the same order.

At no point can two transactions simultaneously be inside the "check, maybe execute, maybe
insert" critical section for the same key — the lock, not the unique constraint, is what
prevents duplicate execution. The unique constraint (the primary key) is still there as a
backstop, but under this design it should never actually be violated in practice.

A hash collision between two genuinely *different* keys would only serialize two unrelated
checkouts against each other unnecessarily (extra latency) — never an incorrect result, because
the actual `idempotency_key` equality check still happens against the table's primary key once
the lock is held.

## No IN_PROGRESS state

Because the advisory lock already provides full serialization per key, no persisted
`IN_PROGRESS`/`COMPLETED` state model is needed. A row's mere existence means "this key's
checkout already succeeded, once." Nothing needs to distinguish "in progress" from anything else,
because nothing can observe an in-progress state from another transaction — it's either still
executing under the lock (invisible to everyone else) or fully committed.

## Transaction boundary

`checkoutIdempotently(cartId, key)` is the **only** `@Transactional` entry point. It validates
the key, acquires the lock, decides replay vs. first-execution, and — on first execution — calls
a private, non-`@Transactional` `executeCheckout(cartId)` directly (the Milestone 4 algorithm,
unchanged) before inserting the idempotency row. All of this — lock, lookup, checkout, mapping
insert — is one PostgreSQL transaction:

```text
BEGIN
acquire advisory lock on key
lookup key -> absent
executeCheckout(cartId): reserve stock, create order, create order items
INSERT checkout_idempotency (key, cartId, orderId)
COMMIT   -- lock released here
```

If `executeCheckout` throws (e.g. insufficient stock), `checkoutIdempotently` throws too, and the
**whole transaction rolls back** — the order, the order items, every reservation/decrement made
so far, and the mapping insert that never even happened. Nothing about the failed attempt is
visible afterward. The key is exactly as unclaimed as before the request; a later request with
the same key (against the same cart, or even a different one) starts fresh.

This deliberately avoids a self-invocation hazard: `executeCheckout` used to be the public
`@Transactional checkout` method from Milestone 4. Calling it as `this.checkout(...)` from inside
another method on the same class would bypass Spring's transactional proxy for that call — but
since a transaction is already open (from `checkoutIdempotently`'s own proxy invocation) by the
time `executeCheckout` runs, and `@Transactional`'s default `REQUIRED` propagation would only
join that same transaction anyway, giving `executeCheckout` its own annotation would have been
misleading, not incorrect. Removing it and making the method private removes any doubt.

## Response status

First execution: `201 Created`. Replay: `200 OK` — nothing new was created. The order
representation in the body is identical either way except for whatever status code accompanies
it; the crucial field, `orderId`, is always the same across every replay of the same key.

## Known limitations

- **Idempotency is checkout-specific**, not a generic framework. There is no
  `IdempotencyInterceptor`/`Aspect`/generic replay service — only checkout has this need right
  now. A future payment webhook will need idempotency too, but with different identity semantics
  (an external event ID, not a `cartId`), and should not be forced through this same mechanism.
- **An idempotency key is not authentication.** Possessing a key is not proof of authorization to
  view or act on the order it maps to — there is no authentication in this project at all yet.
- **No TTL/cleanup.** Mapping rows are kept forever. Expiration/cleanup can be added later if
  storage growth becomes a real concern; it isn't one yet.
- **Reservation expiration still isn't order-aware** (a pre-existing Milestone 4 gap, unchanged
  here): if a checkout-created reservation expires, a replay of the original key still returns
  the original `PENDING` order — idempotency replays the *result identity* of the original
  request, it does not reconcile the order against whatever has happened to its reservations
  since. That reconciliation is future work, not something idempotency is meant to solve.
- **Cart remains unchanged after checkout**, exactly as in Milestone 4 — retry behavior is
  therefore unaffected by whatever the cart looks like at retry time, since a replay never reads
  the cart at all.
