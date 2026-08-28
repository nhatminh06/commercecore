package com.commercecore.checkout;

import java.util.UUID;

record OrderCancelledPayload(UUID orderId, String status) {
}
