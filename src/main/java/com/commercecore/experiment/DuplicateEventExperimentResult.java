package com.commercecore.experiment;
import java.util.UUID;
public record DuplicateEventExperimentResult(String experimentId, int attempts, UUID eventId,
    int newlyPersistedReceipts, long authoritativeReceiptCount, boolean complete,
    boolean invariantPreserved, long durationMs) { }
