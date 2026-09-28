package com.commercecore.bootstrap;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.commercecore.catalog.Product;
import com.commercecore.catalog.ProductService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

class PortfolioDemoDataTest {
    @Test
    void createsOnlyMissingStableDemoSkus() throws Exception {
        ProductService products = mock(ProductService.class);
        when(products.getAll()).thenReturn(List.of(new Product("DEMO-MUG", "Developer Mug", new BigDecimal("18.00"))));

        new PortfolioDemoData(products).run(new DefaultApplicationArguments(new String[0]));

        verify(products, never()).createProduct(eq("DEMO-MUG"), any(), any(), any(Integer.class));
        verify(products).createProduct("DEMO-KEYBOARD", "Mechanical Keyboard", new BigDecimal("129.00"), 25);
        verify(products).createProduct("DEMO-DOCK", "USB-C Dock", new BigDecimal("89.00"), 30);
    }
}
