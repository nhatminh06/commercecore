package com.commercecore.checkout;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Development/test API — no authentication. Checkout takes the cart ID and a required
 * {@code Idempotency-Key} header: the server already owns the cart's contents, current prices,
 * and stock, so nothing else needs to come from the client. The header is bound as optional here
 * so a missing header produces the same {@code missing_idempotency_key} business error as a
 * blank one, decided in one place ({@link CheckoutService#checkoutIdempotently}) rather than
 * Spring's generic missing-header handling producing a different error shape.
 */
@RestController
public class CheckoutController {

    private final CheckoutService checkoutService;

    public CheckoutController(CheckoutService checkoutService) {
        this.checkoutService = checkoutService;
    }

    @PostMapping("/api/carts/{cartId}/checkout")
    public ResponseEntity<OrderResponse> checkout(@PathVariable UUID cartId,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        CheckoutResult result = checkoutService.checkoutIdempotently(cartId, idempotencyKey);
        OrderResponse response = OrderResponse.of(result.order(), checkoutService.getItems(result.order().getId()));
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(response);
    }
}
