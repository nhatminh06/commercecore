package com.commercecore.cart;

import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Development/test API — no authentication. A cart is identified by an opaque server-generated
 * UUID; nothing owns it.
 */
@RestController
@RequestMapping("/api/carts")
public class CartController {

    private final CartService cartService;

    public CartController(CartService cartService) {
        this.cartService = cartService;
    }

    @PostMapping
    public ResponseEntity<CartIdResponse> create() {
        Cart cart = cartService.createCart();
        return ResponseEntity.status(HttpStatus.CREATED).body(new CartIdResponse(cart.getId()));
    }

    @GetMapping("/{cartId}")
    public CartResponse get(@PathVariable UUID cartId) {
        Cart cart = cartService.getCart(cartId);
        return CartResponse.of(cart, cartService.getItems(cartId));
    }

    // Sets the SKU's cart quantity to exactly the given value — not an increment. Calling this
    // twice with the same quantity is a no-op, which is what makes it safe to retry.
    @PutMapping("/{cartId}/items/{sku}")
    public ResponseEntity<Void> setItem(@PathVariable UUID cartId, @PathVariable String sku,
        @Valid @RequestBody SetCartItemRequest request) {
        cartService.setItemQuantity(cartId, sku, request.quantity());
        return ResponseEntity.noContent().build();
    }

    // Removing an item that isn't in the cart is treated as an idempotent success, not an error.
    @DeleteMapping("/{cartId}/items/{sku}")
    public ResponseEntity<Void> removeItem(@PathVariable UUID cartId, @PathVariable String sku) {
        cartService.removeItem(cartId, sku);
        return ResponseEntity.noContent().build();
    }
}
