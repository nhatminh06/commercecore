package com.commercecore.payment;

public record PaymentWebhookRequest(String eventId, String type, String providerRequestId, String providerReference) {
}
