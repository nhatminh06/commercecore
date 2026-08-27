# Inventory reservations

## Why carts do not reserve stock

A cart line means "the customer wants this" — see `docs/cart.md`. It costs nothing to be wrong,
because nothing has actually been claimed. A reservation is different in kind: creating one
takes real stock out of `inventory.available_quantity` immediately, so other buyers can no longer
have it. Milestone 2 proved carts never do this; this milestone adds the operation that
deliberately does.

## Lifecycle

```text
          confirm
ACTIVE ───────────→ CONFIRMED

  │
  ├── release ────→ RELEASED
  │
  └── expiry ─────→ EXPIRED
```

No transition leaves `CONFIRMED`, `RELEASED`, or `EXPIRED` — they are terminal for this
milestone. Repeat-call policy (see §"Terminal-state / idempotency policy" below) is deliberately
not "anything goes": `ACTIVE` is the only status a transition can start from and actually change
anything.

## Schema

```sql
CREATE TABLE inventory_reservations (
    id          UUID PRIMARY KEY,
    sku         VARCHAR(64) NOT NULL REFERENCES products (sku),
    quantity    INTEGER NOT NULL CHECK (quantity > 0),
    status      VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE', 'CONFIRMED', 'RELEASED', 'EXPIRED')),
    expires_at  TIMESTAMPTZ NOT NULL
);
```

- `CHECK (status IN (...))` is a `VARCHAR` plus a constraint, not a PostgreSQL enum type —
  simpler to work with from Flyway/JPA, and the project has no other enum-backed columns to be
  consistent with. It still makes an invalid status value impossible to write, the same job an
  enum type would do.
- `CHECK (quantity > 0)` mirrors the same invariant already enforced on `cart_items.quantity`.
- No `cart_id`/`order_id`/`customer_id` — a reservation is created directly against a SKU in this
  milestone (see "Reservations do not require carts" below).

## Inventory effect per transition

| Transition | `available_quantity` |
|---|---|
| create (→ ACTIVE) | decremented by `quantity` |
| ACTIVE → CONFIRMED | unchanged — the decrement is now permanent |
| ACTIVE → RELEASED | incremented by `quantity` (returned) |
| ACTIVE → EXPIRED | incremented by `quantity` (returned) |

## Reservation creation transaction

`ReservationService.reserve` reuses the exact mechanism that proved Milestone 1's no-overselling
invariant — `InventoryRepository.tryConsume`, the single conditional `UPDATE ... WHERE
available_quantity >= :quantity`. The decrement and the reservation `INSERT` happen inside one
`@Transactional` method, i.e. one PostgreSQL transaction: if the `INSERT` somehow failed after a
successful decrement, the whole transaction — decrement included — rolls back. There is no
window where stock is taken but no reservation row exists to account for it, and no window where
a reservation row exists without having actually taken the stock: `tryConsume` returning 0 rows
(unknown SKU or insufficient stock) exits before any `INSERT` is attempted at all.

## Concurrency mechanism for transitions

Creation is a single atomic statement, but confirm/release/expire are not: each has to read the
current status, decide whether the transition is legal, write the new status, and — for
release/expire — also restore inventory. That's check-then-act across multiple statements, so it
needs an explicit lock, not just a conditional `UPDATE`.

`ReservationRepository.findByIdForUpdate` issues `SELECT ... FOR UPDATE` (via
`@Lock(PESSIMISTIC_WRITE)`) and holds that row lock for the rest of the transaction. A second
concurrent transition on the *same* reservation blocks until the first transaction commits, then
re-reads the row and sees the **post-transition** status — not the stale `ACTIVE` it would have
read without the lock. That is what makes every one of the following safe:

- **Double release**: second caller blocks, then sees `RELEASED` → idempotent no-op, stock not
  restored again.
- **Concurrent confirm vs. release** on the same reservation: whichever transaction gets the lock
  first commits its transition; the second sees the now-terminal status and is rejected with
  `invalid_reservation_transition`. Exactly one terminal state wins, and inventory always matches
  whichever one it was.
- **Double expiration**: `expireDueReservations` re-locks and re-checks each candidate
  individually before acting (see below), so running it twice over the same due reservation
  restores stock only once.

This is deliberately the same category of tool the milestone suggested (`SELECT ... FOR UPDATE`)
rather than a process-local `synchronized` — a Java lock only coordinates threads in one JVM, and
says nothing about two separate requests/connections racing at the database.

## Terminal-state / idempotency policy

| Operation | ACTIVE | CONFIRMED | RELEASED | EXPIRED |
|---|---|---|---|---|
| confirm | → CONFIRMED | no-op success | 409 conflict | 409 conflict |
| release | → RELEASED (+restore) | 409 conflict | no-op success | 409 conflict |

A repeated call in the *same* terminal state (confirm-after-confirm, release-after-release) is
treated as a safe retry, not an error — consistent with the rest of the project's "retries are
normal" stance. A repeated call that would cross from *one* terminal state into a *different* one
(release-after-confirm, confirm-after-release) is rejected, because it would either double-spend
stock (impossible: it's already gone) or double-restore it.

## Expiration semantics

`ReservationService.expireDueReservations(Instant now)` takes `now` as an explicit parameter
rather than reading a clock internally. Rule: `expires_at <= now` is due (tested exactly at, one
second before, and one second after the boundary). It runs in two passes: a plain (non-locking)
query finds `ACTIVE` reservations with `expires_at <= now`, then each candidate is individually
re-locked (`findByIdForUpdate`) and its status/expiry re-checked *under the lock* before actually
transitioning it. That second check is what makes a reservation released or confirmed
concurrently, between the scan and the lock, correctly left alone instead of double-transitioned.

**No background scheduler exists.** `expireDueReservations` is a plain service method, proven by
calling it directly with a controlled `Instant` in tests. Nothing calls it automatically, on a
timer or otherwise — a reservation whose `expires_at` has passed stays `ACTIVE` in the database,
and still holds its stock, until something calls this method. Automatic background expiration is
a later decision (see Known limitations).

## Clock model

`expiresAt` is computed once, at creation time, as `Instant.now() + 15 minutes` (the fixed TTL,
defined in one place: `ReservationService.RESERVATION_TTL`), truncated to microsecond precision
to match what PostgreSQL's `TIMESTAMPTZ` actually stores — without the truncation, the
in-memory `Instant` and the value read back from the database can differ by a sub-microsecond
rounding remainder, which breaks an exact `expires_at <= now` boundary check. `Instant` (UTC) is
used throughout instead of a local date/time, and instead of storing "time remaining" (which
cannot survive a restart meaningfully — see the milestone's own reasoning). No `Clock` bean is
injected: nothing else in this milestone needs to control "the current time" other than the
explicit `now` parameter to `expireDueReservations`, so introducing one now would be unused
machinery.

## Reservations do not require carts

A reservation is created directly against a `sku` + `quantity`, independent of any cart. This is
deliberate for this milestone: it lets reservation correctness (atomicity, concurrency, terminal
states) be proven on its own, before checkout — the next milestone — has to also get "convert
cart lines into reservations" right at the same time.

## Known limitations

- No automatic background expiration (no `@Scheduled`, no worker). The transition logic is
  proven; nothing invokes it on a timer yet.
- Reservations are not linked to carts, checkout, or orders.
- No payment, no order, no checkout flow yet.
- Anonymous, like carts and products: no customer/session ownership.
- TTL is a single fixed server-side constant (15 minutes), not configurable per request.
