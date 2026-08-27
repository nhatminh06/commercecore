# Inventory concurrency

## Invariant

`inventory.available_quantity` must never go negative, under any number of concurrent callers.

## Schema

```sql
CREATE TABLE inventory (
    sku VARCHAR(64) PRIMARY KEY REFERENCES products (sku),
    available_quantity INTEGER NOT NULL CHECK (available_quantity >= 0)
);
```

The `CHECK` constraint is a backstop, not the concurrency mechanism. If application logic
somehow tried to write a negative value, PostgreSQL rejects the statement. It does not by
itself stop two concurrent readers from both deciding stock is available.

## Chosen strategy: atomic conditional UPDATE

`InventoryRepository.tryConsume` (`src/main/java/com/commercecore/inventory/InventoryRepository.java`):

```sql
UPDATE inventory
SET available_quantity = available_quantity - :quantity
WHERE sku = :sku
  AND available_quantity >= :quantity
```

The check (`available_quantity >= :quantity`) and the write happen in the same statement,
evaluated against the row PostgreSQL has already taken a write lock on for the duration of the
statement. A second concurrent `UPDATE` against the same row blocks until the first commits,
then re-evaluates the `WHERE` clause against the post-commit value — it cannot observe the
pre-decrement quantity the first transaction acted on. There is no separate
"read quantity, then decide, then write" round trip in application code for this to race
around.

Success/failure is read from the affected-row count: 1 row updated means the decrement
happened; 0 means either the SKU doesn't exist or there wasn't enough stock — `InventoryService`
disambiguates those with a follow-up existence check purely to produce a clearer error.

## Why not `synchronized`

A Java-level lock only coordinates threads inside one JVM process. It does nothing for two
application instances (or two separate connections issuing overlapping transactions) hitting
the same database row — which is the situation that actually causes overselling in production.
The correctness mechanism has to live where the shared state lives: PostgreSQL.

## Why not pessimistic `SELECT ... FOR UPDATE`

Would also work, but requires an explicit two-step read-then-write inside a transaction, adding
code and a fetch round trip without buying anything the single conditional `UPDATE` doesn't already
provide for this specific "decrement iff enough stock" operation. Row locking becomes more clearly
justified once a single logical operation needs to touch and validate multiple rows together,
which isn't the case yet.

## Concurrent test scenarios

- `InventoryConcurrencyTest.lastItemRaceAllowsExactlyOneWinner`: stock = 1, two threads race via
  a `CyclicBarrier` to consume 1 unit each. Exactly one succeeds, exactly one fails with
  `insufficient_stock`, final stock = 0.
- `InventoryConcurrencyTest.manyBuyersRaceNeverOversells`: stock = 10, 100 threads race to
  consume 1 unit each. Exactly 10 succeed, 90 fail, final stock = 0.

Both run against a real PostgreSQL container via Testcontainers — no mocking of the repository
or the database.

## Known limitations

- This is a stock *consumption* primitive, not a timed reservation (Milestone 3). A failed
  checkout after a successful `consume` call does not currently return stock automatically.
- No compensating "release"/"restock" operation exists yet.
- Only single-row, single-SKU operations are atomic. Multi-SKU checkout atomicity is a later
  milestone.
