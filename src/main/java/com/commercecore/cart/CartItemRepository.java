package com.commercecore.cart;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CartItemRepository extends JpaRepository<CartItem, CartItemId> {

    List<CartItem> findByCartId(UUID cartId);

    /**
     * Sets this cart line to exactly {@code quantity}: one atomic statement that inserts the row
     * if it doesn't exist yet, or overwrites the existing (cart_id, sku) row if it does. This is
     * what makes "set quantity" idempotent — calling it twice with the same quantity leaves the
     * same single row either way, with no separate read-then-insert-or-update branch in
     * application code to race against a concurrent caller.
     */
    @Modifying
    @Query(value = """
        INSERT INTO cart_items (cart_id, sku, quantity)
        VALUES (:cartId, :sku, :quantity)
        ON CONFLICT (cart_id, sku) DO UPDATE SET quantity = EXCLUDED.quantity
        """, nativeQuery = true)
    void setQuantity(@Param("cartId") UUID cartId, @Param("sku") String sku, @Param("quantity") int quantity);

    void deleteByCartIdAndSku(UUID cartId, String sku);
}
