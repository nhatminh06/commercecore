package com.commercecore.inventory;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/products/{sku}/inventory")
public class InventoryController {

    private final InventoryService inventoryService;

    public InventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @GetMapping
    public InventoryResponse get(@PathVariable String sku) {
        return InventoryResponse.from(inventoryService.getBySku(sku));
    }

    @PostMapping("/consume")
    public ResponseEntity<Void> consume(@PathVariable String sku, @RequestBody ConsumeInventoryRequest request) {
        inventoryService.consume(sku, request.quantity());
        return ResponseEntity.noContent().build();
    }
}
