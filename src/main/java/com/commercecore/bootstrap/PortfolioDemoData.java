package com.commercecore.bootstrap;

import com.commercecore.catalog.ProductService;
import java.math.BigDecimal;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("portfolio")
public class PortfolioDemoData implements ApplicationRunner {
    private final ProductService products;

    public PortfolioDemoData(ProductService products) { this.products = products; }

    @Override
    public void run(ApplicationArguments args) {
        Set<String> existing = products.getAll().stream().map(product -> product.getSku()).collect(Collectors.toSet());
        seed(existing, "DEMO-KEYBOARD", "Mechanical Keyboard", "129.00", 25);
        seed(existing, "DEMO-DOCK", "USB-C Dock", "89.00", 30);
        seed(existing, "DEMO-MUG", "Developer Mug", "18.00", 50);
    }

    private void seed(Set<String> existing, String sku, String name, String price, int quantity) {
        if (!existing.contains(sku)) products.createProduct(sku, name, new BigDecimal(price), quantity);
    }
}
