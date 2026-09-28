package com.commercecore.experiment;
import java.util.List;
import java.util.UUID;
public record CheckoutExperimentResult(String experimentId, int workers, UUID cartId, String idempotencyKey,
    int successfulResponses, int failedResponses, List<UUID> distinctResponseOrderIds,
    long persistedMappingsForCart, boolean complete, boolean invariantPreserved, long durationMs) { }
