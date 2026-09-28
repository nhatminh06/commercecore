package com.commercecore.catalog;

import com.commercecore.inventory.Inventory;
import com.commercecore.inventory.InventoryRepository;
import com.commercecore.shared.BusinessRuleViolation;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductService {

    private final ProductRepository productRepository;
    private final InventoryRepository inventoryRepository;

    public ProductService(ProductRepository productRepository, InventoryRepository inventoryRepository) {
        this.productRepository = productRepository;
        this.inventoryRepository = inventoryRepository;
    }

    /**
     * A product must never exist without a corresponding inventory row (and vice versa), so both
     * inserts commit as one unit. Without the transaction, a crash between the two inserts would
     * leave a product with no inventory record, which every inventory read/consume call assumes
     * cannot happen.
     */
    @Transactional
    public Product createProduct(String sku, String name, BigDecimal priceAmount, int initialQuantity) {
        if (productRepository.existsBySku(sku)) {
            throw new BusinessRuleViolation(HttpStatus.CONFLICT, "duplicate_sku", "SKU already exists: " + sku);
        }
        if (initialQuantity < 0) {
            throw new BusinessRuleViolation(HttpStatus.BAD_REQUEST, "invalid_quantity",
                "initialQuantity must not be negative");
        }

        Product product = productRepository.save(new Product(sku, name, priceAmount));
        inventoryRepository.save(new Inventory(sku, initialQuantity));
        return product;
    }

    public Product getBySku(String sku) {
        return productRepository.findBySku(sku)
            .orElseThrow(() -> new BusinessRuleViolation(HttpStatus.NOT_FOUND, "unknown_sku", "Unknown SKU: " + sku));
    }

    public List<Product> getAll() {
        return productRepository.findAll(Sort.by(Sort.Direction.ASC, "sku"));
    }
}
