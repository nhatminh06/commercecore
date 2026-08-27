# Cart

> Adding a SKU to a cart records purchase intent; it does not reserve or decrement inventory.

## Cart identity

A cart is identified by a server-generated `UUID` (`carts.id`). There is no owning user —
authentication doesn't exist yet, and a cart doesn't need one to be useful: the client that
creates a cart is responsible for remembering its ID.

## Schema

```sql
CREATE TABLE carts (
    id UUID PRIMARY KEY
);

CREATE TABLE cart_items (
    cart_id UUID NOT NULL REFERENCES carts (id) ON DELETE CASCADE,
    sku VARCHAR(64) NOT NULL REFERENCES products (sku),
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    PRIMARY KEY (cart_id, sku)
);
```

- `PRIMARY KEY (cart_id, sku)` is what guarantees one logical line per SKU per cart — the
  database rejects a second row for the same pair outright, it isn't just an application
  convention.
- `CHECK (quantity > 0)` is the database-level backstop for the same invariant
  `InventoryService.consume` already enforces in application code for inventory: zero/negative
  quantities are rejected, not silently accepted.
- `sku REFERENCES products (sku)` means a cart can never reference a product that doesn't exist.
- `cart_id REFERENCES carts (id) ON DELETE CASCADE` — there's no cart-delete endpoint yet, but
  when one exists, deleting a cart should also remove its lines without a separate cleanup step.

No `created_at`/`status`/other columns: nothing in this milestone reads them, so they aren't
here. They can be added when a concrete requirement (expiration, checkout linkage) needs them.

## API semantics

```http
POST   /api/carts                       → {"id": "..."}, always empty
GET    /api/carts/{cartId}              → {"id": "...", "items": [{"sku": "...", "quantity": N}]}
PUT    /api/carts/{cartId}/items/{sku}  → {"quantity": N}, 204
DELETE /api/carts/{cartId}/items/{sku}  → 204
```

`PUT` **sets** the line to exactly `quantity` — it is not an increment. Calling it twice with the
same body is a no-op both times, which is what makes it safe to retry. There is deliberately no
"add N to the existing quantity" operation in this milestone; that would make retries ambiguous
(a duplicate request could double-add).

`DELETE` on a SKU that isn't in the cart still returns `204` — removal is idempotent success, not
an error, as long as the cart itself exists.

Operating on an unknown cart (`GET`, `PUT`, or `DELETE`) returns `404 cart_not_found`. An unknown
cart is never implicitly created by `PUT`.

## Product validation, not stock validation

`PUT .../items/{sku}` checks that the SKU exists in `products` (`404 unknown_sku` if not) — a
cart can't reference a phantom product. It does **not** check `inventory.available_quantity`.
Requesting a quantity larger than what's in stock is accepted:

```text
available_quantity = 2
PUT quantity = 10   → 204, cart now holds 10
```

This is deliberate, not an oversight. Availability can change between "add to cart" and
"checkout" — checking it now would only ever be stale by the time it matters. Stock validation
belongs to reservation/checkout (a later milestone), against the current availability at that
moment, not to the cart.

## Price semantics

The cart stores only `sku` and `quantity` — no price. If a client wants to show a price next to a
cart line, it has to look up the current `products.price_amount`, which is explicitly not a
guarantee: the price can change before checkout. Locking in a price at checkout time (a snapshot,
not a live catalog read) is a decision for the checkout milestone, not this one.

## Cart vs. inventory: why cart activity never touches `available_quantity`

`CartService` has no dependency on `InventoryRepository` or `InventoryService` — not "avoids
calling it," structurally does not import it. A cart entry means "a customer wants this," not "a
customer owns this." Two carts (or 3, or 300) can each claim the last unit of stock; nothing is
inconsistent about that, because nothing has actually been taken yet. `available_quantity`
changes only through `InventoryService.consume` (Milestone 1's atomic conditional `UPDATE`),
which cart code never calls. Ownership of stock — reservation — is Milestone 3.

## Known limitations

- No cart expiration/TTL.
- No inventory reservation tied to cart contents.
- No checkout, no price snapshot, no orders.
- No way to delete a whole cart yet (the schema supports it via `ON DELETE CASCADE`; there's just
  no endpoint).
- Anonymous: nothing associates a cart with a customer/session.
