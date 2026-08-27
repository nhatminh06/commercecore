package com.commercecore.payment;

import java.math.BigDecimal;
import java.util.UUID;

public record PaymentResponse(UUID paymentId, UUID orderId, BigDecimal amount, PaymentStatus status,
    String providerReference) {

    static PaymentResponse from(Payment payment) {
        return new PaymentResponse(payment.getId(), payment.getOrderId(), payment.getAmount(), payment.getStatus(),
            payment.getProviderReference());
    }
}
