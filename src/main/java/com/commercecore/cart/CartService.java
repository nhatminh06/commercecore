package com.commercecore.cart;

import com.commercecore.catalog.ProductRepository;
import com.commercecore.shared.BusinessRuleViolation;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deliberately has no dependency on inventory. A cart records purchase intent; it never reads or
 * writes {@code inventory.available_quantity}. See {@code docs/cart.md}.
 */
@Service
public class CartService {

    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final ProductRepository productRepository;

    public CartService(CartRepository cartRepository, CartItemRepository cartItemRepository,
        ProductRepository productRepository) {
        this.cartRepository = cartRepository;
        this.cartItemRepository = cartItemRepository;
        this.productRepository = productRepository;
    }

    @Transactional
    public Cart createCart() {
        return cartRepository.save(new Cart(UUID.randomUUID()));
    }

    public Cart getCart(UUID cartId) {
        return cartRepository.findById(cartId)
            .orElseThrow(() -> new BusinessRuleViolation(HttpStatus.NOT_FOUND, "cart_not_found",
                "Unknown cart: " + cartId));
    }

    public List<CartItem> getItems(UUID cartId) {
        return cartItemRepository.findByCartId(cartId);
    }

    /**
     * Sets the SKU's line to exactly {@code quantity}. Does not check current stock: the whole
     * point of a cart is to record intent that may exceed, or later be invalidated by, actual
     * availability. Stock validation belongs to reservation/checkout, not here.
     */
    @Transactional
    public void setItemQuantity(UUID cartId, String sku, int quantity) {
        requireCartExists(cartId);
        if (quantity <= 0) {
            throw new BusinessRuleViolation(HttpStatus.BAD_REQUEST, "invalid_quantity",
                "quantity must be positive");
        }
        if (!productRepository.existsBySku(sku)) {
            throw new BusinessRuleViolation(HttpStatus.NOT_FOUND, "unknown_sku", "Unknown SKU: " + sku);
        }

        cartItemRepository.setQuantity(cartId, sku, quantity);
    }

    @Transactional
    public void removeItem(UUID cartId, String sku) {
        requireCartExists(cartId);
        cartItemRepository.deleteByCartIdAndSku(cartId, sku);
    }

    private void requireCartExists(UUID cartId) {
        if (!cartRepository.existsById(cartId)) {
            throw new BusinessRuleViolation(HttpStatus.NOT_FOUND, "cart_not_found", "Unknown cart: " + cartId);
        }
    }
}
