# Checkout

> No payment exists yet. PENDING means stock is held, not paid.

## Flow

```text
Cart
  ↓
read current cart lines
  ↓
validate current product prices (price snapshot)
  ↓
reserve every requested SKU (existing ReservationService primitive)
  ↓
create persistent PENDING order + order items
  ↓
associate reservations with the order
```

## Transaction boundary

`CheckoutService.checkout(cartId)` is one `@Transactional` method — one PostgreSQL transaction
for the whole operation. It runs in two passes:

**Pass 1 (read-only).** Validate the cart exists and isn't empty, then for each cart line read
the product's *current* price and compute `lineTotal = unitPrice × quantity` and the running
order `total`. Nothing is written here — there's nothing to undo if a later line turns out to be
a problem.

**Pass 2 (writes).** Insert the `orders` row first, already carrying its final, fully-computed
total — not a placeholder later updated. It has to come first: both `order_items.order_id` and
`inventory_reservations.order_id` are foreign keys to `orders.id`, so inserting either before the
order row exists would fail the constraint immediately, not just at commit. Then, for each cart
line **sorted by SKU**, call `ReservationService.reserve(sku, quantity, orderId)` and insert the
matching `order_items` row.

If any `reserve` call fails (insufficient stock), the method throws and the whole
`@Transactional` boundary rolls back — the order row, every order item already inserted, and
every reservation/decrement already made earlier in the same loop, all together. Nothing needs
manual compensation, because nothing here was ever committed on its own; PostgreSQL just never
makes any of it visible outside the transaction until `COMMIT`, which never happens.

## Why sort cart lines by SKU

`ReservationService.reserve` takes a row lock on `inventory` (via the conditional `UPDATE`) for
each SKU it touches, held until the whole checkout transaction commits or rolls back. Two
concurrent checkouts sharing overlapping SKUs but reserving them in different orders (cart A:
X then Y; cart B: Y then X) could each hold one lock and then block waiting for the other —
a classic lock-ordering deadlock. Sorting every checkout's cart lines into the same order (by
SKU) before reserving means every checkout attempts to acquire the same SKUs' locks in the same
global order, so that cycle can't form.

## Reservation reuse

Checkout does not reimplement stock-claiming. `ReservationService.reserve(String, int, UUID)` is
the exact same atomic conditional-`UPDATE` mechanism that proved Milestone 1's no-overselling
invariant, with one addition: it now accepts an optional `orderId` to tag the created reservation
with. `ReservationService.reserve(String, int)` (the standalone `/api/reservations` API) delegates
to the same method with `orderId = null` — unchanged behavior, same primitive underneath.
Because `reserve` is `@Transactional` with Spring's default `REQUIRED` propagation, calling it
from inside `CheckoutService.checkout`'s own `@Transactional` method makes it join that same
transaction rather than opening a new one — one commit or one rollback covers both.

## Price snapshot

A cart line stores no price (see `docs/cart.md`). `order_items.unit_price` and `line_total` are
captured once, at checkout time, from `products.price_amount`, and never read from `products`
again afterward. A later price change on the product has no effect on any order already created:

```text
product price = $10.00
checkout        → order line unit_price = $10.00
product price changes to $12.00
GET order       → unit_price is still $10.00
```

## Order structure

```text
orders(id, status, total_amount, created_at)
order_items(order_id, sku, quantity, unit_price, line_total)
```

`status` only ever has one legal value right now: `PENDING`. `total_amount` is the sum of
`line_total` across the order's items, computed server-side — never accepted from the client.
Neither `Order` nor `OrderItem` has a setter for anything after construction: there is no code
path, let alone an API endpoint, that mutates an order after checkout creates it.

## Reservation association

`inventory_reservations` gained a nullable `order_id` column (this migration). Reservations
created through checkout carry the order's id; reservations created through the standalone
`/api/reservations` API leave it null, exactly as before — the existing API's behavior is
unchanged. `ReservationService.getReservationsForOrder(orderId)` is the read path for "which
reservations belong to this order."

## Cart-after-checkout policy

**The cart is left unchanged.** A successful checkout does not clear or modify the cart's items.
Reasoning: payment hasn't happened yet, and checkout retry/idempotency semantics don't exist yet
(that's the next milestone) — clearing the cart now would be a policy decision made before either
of those exist to justify it. `GET /api/carts/{cartId}` after a successful checkout still shows
the same lines that were checked out.

## Known limitations

- **Repeated checkout is not idempotent.** `POST /api/carts/{cartId}/checkout` called twice in a
  row, with stock still available, currently creates **two separate PENDING orders**, each with
  its own reservations. This is a known, deliberate gap — Milestone 5 (idempotency keys) exists
  specifically to close it. Nothing here hides or works around it.
- **Reservation expiration is not order-aware.** If a checkout-created reservation expires
  (`ReservationService.expireDueReservations`), its stock is correctly returned, but the order it
  belongs to is not transitioned out of `PENDING` — there is no code path that touches order
  status during expiration. An order can end up `PENDING` with an `EXPIRED` reservation behind
  it. This incomplete workflow is intentional for this milestone and must be addressed later
  (alongside payment/confirmation, which is when "what happens to an order whose reservation
  expired" actually needs an answer).
- No payment, no order confirmation/cancellation, no idempotency keys, no outbox, no saga.
- Orders are anonymous, like carts and reservations.
