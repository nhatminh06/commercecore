package com.commercecore.experiment;
import java.util.List;
import java.util.UUID;
public record ProviderExperimentResult(String experimentId, int workers, UUID providerRequestId,
    int successfulResponses, int failedResponses, List<String> distinctLogicalResults,
    String authoritativeProviderStatus, String providerReference, boolean complete,
    boolean invariantPreserved, long durationMs) { }
