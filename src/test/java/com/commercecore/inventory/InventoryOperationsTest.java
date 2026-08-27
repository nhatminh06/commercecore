package com.commercecore.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercecore.AbstractIntegrationTest;
import com.commercecore.catalog.ProductService;
import com.commercecore.shared.BusinessRuleViolation;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class InventoryOperationsTest extends AbstractIntegrationTest {

    @Autowired
    private ProductService productService;

    @Autowired
    private InventoryService inventoryService;

    private String newProductWithStock(int quantity) {
        String sku = "SKU-" + UUID.randomUUID();
        productService.createProduct(sku, "Widget", new BigDecimal("9.99"), quantity);
        return sku;
    }

    @Test
    void validConsumeDecrementsStock() {
        String sku = newProductWithStock(10);

        inventoryService.consume(sku, 3);

        assertThat(inventoryService.getBySku(sku).getAvailableQuantity()).isEqualTo(7);
    }

    @Test
    void insufficientStockLeavesQuantityUnchangedAndIsRejected() {
        String sku = newProductWithStock(2);

        assertThatThrownBy(() -> inventoryService.consume(sku, 3))
            .isInstanceOf(BusinessRuleViolation.class)
            .hasFieldOrPropertyWithValue("code", "insufficient_stock");

        assertThat(inventoryService.getBySku(sku).getAvailableQuantity()).isEqualTo(2);
    }

    @Test
    void zeroQuantityIsRejected() {
        String sku = newProductWithStock(5);

        assertThatThrownBy(() -> inventoryService.consume(sku, 0))
            .isInstanceOf(BusinessRuleViolation.class)
            .hasFieldOrPropertyWithValue("code", "invalid_quantity");

        assertThat(inventoryService.getBySku(sku).getAvailableQuantity()).isEqualTo(5);
    }

    @Test
    void negativeQuantityIsRejected() {
        String sku = newProductWithStock(5);

        assertThatThrownBy(() -> inventoryService.consume(sku, -1))
            .isInstanceOf(BusinessRuleViolation.class)
            .hasFieldOrPropertyWithValue("code", "invalid_quantity");

        assertThat(inventoryService.getBySku(sku).getAvailableQuantity()).isEqualTo(5);
    }

    @Test
    void unknownSkuIsRejected() {
        assertThatThrownBy(() -> inventoryService.consume("does-not-exist", 1))
            .isInstanceOf(BusinessRuleViolation.class)
            .hasFieldOrPropertyWithValue("code", "unknown_sku");
    }
}
