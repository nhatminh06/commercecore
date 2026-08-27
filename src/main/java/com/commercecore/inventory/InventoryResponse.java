package com.commercecore.inventory;

public record InventoryResponse(String sku, int availableQuantity) {

    static InventoryResponse from(Inventory inventory) {
        return new InventoryResponse(inventory.getSku(), inventory.getAvailableQuantity());
    }
}
