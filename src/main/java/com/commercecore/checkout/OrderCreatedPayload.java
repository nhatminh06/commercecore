package com.commercecore.checkout;

import java.math.BigDecimal;
import java.util.UUID;

record OrderCreatedPayload(UUID orderId, String status, BigDecimal total) {
}
