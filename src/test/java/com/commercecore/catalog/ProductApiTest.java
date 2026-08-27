package com.commercecore.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercecore.AbstractIntegrationTest;
import com.commercecore.inventory.InventoryResponse;
import com.commercecore.shared.ApiError;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class ProductApiTest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    private String uniqueSku() {
        return "SKU-" + UUID.randomUUID();
    }

    @Test
    void createsAndReadsProduct() {
        String sku = uniqueSku();
        CreateProductRequest request = new CreateProductRequest(sku, "Widget", new BigDecimal("9.99"), 5);

        ResponseEntity<ProductResponse> createResponse =
            restTemplate.postForEntity("/api/products", request, ProductResponse.class);

        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(createResponse.getBody()).isNotNull();
        assertThat(createResponse.getBody().sku()).isEqualTo(sku);
        assertThat(createResponse.getBody().price()).isEqualByComparingTo("9.99");

        ResponseEntity<ProductResponse> getResponse =
            restTemplate.getForEntity("/api/products/{sku}", ProductResponse.class, sku);

        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getResponse.getBody().name()).isEqualTo("Widget");
    }

    @Test
    void rejectsDuplicateSku() {
        String sku = uniqueSku();
        CreateProductRequest request = new CreateProductRequest(sku, "Widget", new BigDecimal("9.99"), 5);

        restTemplate.postForEntity("/api/products", request, ProductResponse.class);
        ResponseEntity<ApiError> duplicateResponse =
            restTemplate.postForEntity("/api/products", request, ApiError.class);

        assertThat(duplicateResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(duplicateResponse.getBody().code()).isEqualTo("duplicate_sku");
    }

    @Test
    void storesInitialInventoryOnCreate() {
        String sku = uniqueSku();
        CreateProductRequest request = new CreateProductRequest(sku, "Widget", new BigDecimal("9.99"), 7);
        restTemplate.postForEntity("/api/products", request, ProductResponse.class);

        ResponseEntity<InventoryResponse> inventoryResponse =
            restTemplate.getForEntity("/api/products/{sku}/inventory", InventoryResponse.class, sku);

        assertThat(inventoryResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(inventoryResponse.getBody().availableQuantity()).isEqualTo(7);
    }

    @Test
    void unknownSkuReturnsNotFound() {
        ResponseEntity<ApiError> response =
            restTemplate.getForEntity("/api/products/{sku}", ApiError.class, "does-not-exist");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().code()).isEqualTo("unknown_sku");
    }
}
