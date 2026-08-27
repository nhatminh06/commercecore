package com.commercecore.inventory;

import com.commercecore.shared.BusinessRuleViolation;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InventoryService {

    private final InventoryRepository inventoryRepository;

    public InventoryService(InventoryRepository inventoryRepository) {
        this.inventoryRepository = inventoryRepository;
    }

    public Inventory getBySku(String sku) {
        return inventoryRepository.findById(sku)
            .orElseThrow(() -> new BusinessRuleViolation(HttpStatus.NOT_FOUND, "unknown_sku", "Unknown SKU: " + sku));
    }

    /**
     * The transaction boundary is exactly the single conditional UPDATE in
     * {@link InventoryRepository#tryConsume}. Nothing else needs to be atomic with it; wrapping
     * it in @Transactional here only makes that boundary explicit and gives the @Modifying query
     * a transaction to run in.
     */
    @Transactional
    public void consume(String sku, int quantity) {
        if (quantity <= 0) {
            throw new BusinessRuleViolation(HttpStatus.BAD_REQUEST, "invalid_quantity",
                "quantity must be positive");
        }

        int updated = inventoryRepository.tryConsume(sku, quantity);
        if (updated == 1) {
            return;
        }

        if (!inventoryRepository.existsById(sku)) {
            throw new BusinessRuleViolation(HttpStatus.NOT_FOUND, "unknown_sku", "Unknown SKU: " + sku);
        }
        throw new BusinessRuleViolation(HttpStatus.CONFLICT, "insufficient_stock",
            "Not enough stock available for SKU: " + sku);
    }
}
