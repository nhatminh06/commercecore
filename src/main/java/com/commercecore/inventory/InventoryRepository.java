package com.commercecore.inventory;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InventoryRepository extends JpaRepository<Inventory, String> {

    /**
     * Atomically consumes stock: the decrement and the "is there enough left" check happen as a
     * single conditional UPDATE evaluated by PostgreSQL under the row's write lock, so two
     * concurrent callers can never both observe (and act on) the same pre-decrement quantity.
     * The affected-row count (0 or 1) is the only signal the caller needs — no separate
     * read-then-write, and no application-level lock.
     */
    @Modifying
    @Query(value = """
        UPDATE inventory
        SET available_quantity = available_quantity - :quantity
        WHERE sku = :sku
          AND available_quantity >= :quantity
        """, nativeQuery = true)
    int tryConsume(@Param("sku") String sku, @Param("quantity") int quantity);

    /**
     * The inverse of {@link #tryConsume}: returns previously-reserved stock to the available
     * pool. Only ever called from a reservation transition (release/expire) that has already
     * proven, under a row lock on the reservation itself, that this quantity was in fact
     * reserved and not yet returned — so this plain increment cannot be called twice for the
     * same reservation. Not exposed as a public inventory-increment operation.
     */
    @Modifying
    @Query(value = """
        UPDATE inventory
        SET available_quantity = available_quantity + :quantity
        WHERE sku = :sku
        """, nativeQuery = true)
    void restore(@Param("sku") String sku, @Param("quantity") int quantity);
}
