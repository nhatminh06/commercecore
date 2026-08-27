package com.commercecore.cart;

import java.util.List;
import java.util.UUID;

public record CartResponse(UUID id, List<CartItemResponse> items) {

    static CartResponse of(Cart cart, List<CartItem> items) {
        return new CartResponse(cart.getId(), items.stream().map(CartItemResponse::from).toList());
    }
}
