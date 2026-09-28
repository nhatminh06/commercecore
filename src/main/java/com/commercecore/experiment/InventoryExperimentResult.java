package com.commercecore.experiment;
public record InventoryExperimentResult(String experimentId, String sku, int workers, int initialInventory,
    int quantityPerWorker, int successful, int rejected, int finalInventory, boolean complete,
    boolean invariantPreserved, long durationMs) { }
