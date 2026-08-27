package com.commercecore.catalog;

import java.math.BigDecimal;

public record ProductResponse(String sku, String name, BigDecimal price) {

    static ProductResponse from(Product product) {
        return new ProductResponse(product.getSku(), product.getName(), product.getPriceAmount());
    }
}
