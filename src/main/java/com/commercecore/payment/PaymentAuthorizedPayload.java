package com.commercecore.payment;

import java.math.BigDecimal;
import java.util.UUID;

record PaymentAuthorizedPayload(UUID paymentId, UUID orderId, BigDecimal amount, String providerReference) {
}
