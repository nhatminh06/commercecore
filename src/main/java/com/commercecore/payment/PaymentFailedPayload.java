package com.commercecore.payment;

import java.math.BigDecimal;
import java.util.UUID;

record PaymentFailedPayload(UUID paymentId, UUID orderId, BigDecimal amount) {
}
