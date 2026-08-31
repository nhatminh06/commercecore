package com.commercecore.paymentservice;

import java.math.BigDecimal;
import java.util.UUID;

public record ProviderPayment(UUID providerRequestId, BigDecimal amount, Status status,
                              String providerReference, boolean delayResponse) {

    public enum Status {
        AUTHORIZED,
        DECLINED
    }
}
