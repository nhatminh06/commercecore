package com.commercecore.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercecore.AbstractIntegrationTest;
import com.commercecore.catalog.ProductService;
import com.commercecore.inventory.InventoryService;
import com.commercecore.shared.ApiError;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class ReservationApiTest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ProductService productService;

    @Autowired
    private InventoryService inventoryService;

    private String newProductWithStock(int quantity) {
        String sku = "SKU-" + UUID.randomUUID();
        productService.createProduct(sku, "Widget", new BigDecimal("9.99"), quantity);
        return sku;
    }

    private ResponseEntity<ReservationResponse> reserve(String sku, int quantity) {
        return restTemplate.postForEntity("/api/reservations",
            new CreateReservationRequest(sku, quantity), ReservationResponse.class);
    }

    @Test
    void createsActiveReservationAndDecrementsInventory() {
        String sku = newProductWithStock(5);

        ResponseEntity<ReservationResponse> response = reserve(sku, 2);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ReservationResponse reservation = response.getBody();
        assertThat(reservation.sku()).isEqualTo(sku);
        assertThat(reservation.quantity()).isEqualTo(2);
        assertThat(reservation.status()).isEqualTo(ReservationStatus.ACTIVE);
        assertThat(reservation.expiresAt()).isNotNull();
        assertThat(inventoryService.getBySku(sku).getAvailableQuantity()).isEqualTo(3);
    }

    @Test
    void insufficientStockCreatesNoReservation() {
        String sku = newProductWithStock(2);

        ResponseEntity<ApiError> response =
            restTemplate.postForEntity("/api/reservations", new CreateReservationRequest(sku, 3), ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().code()).isEqualTo("insufficient_stock");
        assertThat(inventoryService.getBySku(sku).getAvailableQuantity()).isEqualTo(2);
    }

    @Test
    void unknownSkuIsRejected() {
        ResponseEntity<ApiError> response = restTemplate.postForEntity("/api/reservations",
            new CreateReservationRequest("does-not-exist", 1), ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().code()).isEqualTo("unknown_sku");
    }

    @Test
    void zeroQuantityIsRejected() {
        String sku = newProductWithStock(5);

        ResponseEntity<ApiError> response =
            restTemplate.postForEntity("/api/reservations", new CreateReservationRequest(sku, 0), ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo("invalid_quantity");
        assertThat(inventoryService.getBySku(sku).getAvailableQuantity()).isEqualTo(5);
    }

    @Test
    void negativeQuantityIsRejected() {
        String sku = newProductWithStock(5);

        ResponseEntity<ApiError> response =
            restTemplate.postForEntity("/api/reservations", new CreateReservationRequest(sku, -1), ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo("invalid_quantity");
    }

    @Test
    void unknownReservationReturnsNotFound() {
        ResponseEntity<ApiError> response =
            restTemplate.getForEntity("/api/reservations/{id}", ApiError.class, UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().code()).isEqualTo("reservation_not_found");
    }

    @Test
    void releaseRestoresInventoryAndDoubleReleaseIsIdempotent() {
        String sku = newProductWithStock(5);
        UUID reservationId = reserve(sku, 2).getBody().id();
        assertThat(inventoryService.getBySku(sku).getAvailableQuantity()).isEqualTo(3);

        ResponseEntity<ReservationResponse> first =
            restTemplate.postForEntity("/api/reservations/{id}/release", null, ReservationResponse.class, reservationId);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody().status()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(inventoryService.getBySku(sku).getAvailableQuantity()).isEqualTo(5);

        ResponseEntity<ReservationResponse> second =
            restTemplate.postForEntity("/api/reservations/{id}/release", null, ReservationResponse.class, reservationId);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody().status()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(inventoryService.getBySku(sku).getAvailableQuantity()).isEqualTo(5);
    }

    @Test
    void confirmKeepsInventoryConsumedAndIsIdempotent() {
        String sku = newProductWithStock(5);
        UUID reservationId = reserve(sku, 2).getBody().id();
        assertThat(inventoryService.getBySku(sku).getAvailableQuantity()).isEqualTo(3);

        ResponseEntity<ReservationResponse> first =
            restTemplate.postForEntity("/api/reservations/{id}/confirm", null, ReservationResponse.class, reservationId);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody().status()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(inventoryService.getBySku(sku).getAvailableQuantity()).isEqualTo(3);

        ResponseEntity<ReservationResponse> second =
            restTemplate.postForEntity("/api/reservations/{id}/confirm", null, ReservationResponse.class, reservationId);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody().status()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(inventoryService.getBySku(sku).getAvailableQuantity()).isEqualTo(3);
    }

    @Test
    void releaseAfterConfirmIsRejected() {
        String sku = newProductWithStock(5);
        UUID reservationId = reserve(sku, 2).getBody().id();
        restTemplate.postForEntity("/api/reservations/{id}/confirm", null, ReservationResponse.class, reservationId);

        ResponseEntity<ApiError> response =
            restTemplate.postForEntity("/api/reservations/{id}/release", null, ApiError.class, reservationId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().code()).isEqualTo("invalid_reservation_transition");
        assertThat(inventoryService.getBySku(sku).getAvailableQuantity()).isEqualTo(3);
    }

    @Test
    void confirmAfterReleaseIsRejected() {
        String sku = newProductWithStock(5);
        UUID reservationId = reserve(sku, 2).getBody().id();
        restTemplate.postForEntity("/api/reservations/{id}/release", null, ReservationResponse.class, reservationId);

        ResponseEntity<ApiError> response =
            restTemplate.postForEntity("/api/reservations/{id}/confirm", null, ApiError.class, reservationId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().code()).isEqualTo("invalid_reservation_transition");
        assertThat(inventoryService.getBySku(sku).getAvailableQuantity()).isEqualTo(5);
    }
}
