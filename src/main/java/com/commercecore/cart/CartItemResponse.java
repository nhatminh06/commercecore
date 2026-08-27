package com.commercecore.cart;

public record CartItemResponse(String sku, int quantity) {

    static CartItemResponse from(CartItem item) {
        return new CartItemResponse(item.getSku(), item.getQuantity());
    }
}
