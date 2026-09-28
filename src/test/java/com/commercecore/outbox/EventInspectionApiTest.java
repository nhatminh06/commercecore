package com.commercecore.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercecore.AbstractIntegrationTest;
import com.commercecore.cart.CartService;
import com.commercecore.catalog.ProductService;
import com.commercecore.checkout.CheckoutService;
import com.commercecore.shared.ApiError;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles({"local-provider", "dev"})
class EventInspectionApiTest extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ProductService productService;
    @Autowired private CartService cartService;
    @Autowired private CheckoutService checkoutService;
    @Autowired private OutboxEventRepository outboxRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void exposesPersistedPublicationAndSeparateConsumerEvidenceWithoutMutation() {
        UUID orderId = createOrder();
        OutboxEvent event = outboxRepository.findTop100ByOrderByCreatedAtDescIdDesc().stream()
            .filter(candidate -> candidate.getAggregateId().equals(orderId))
            .findFirst().orElseThrow();
        Instant publishedAt = Instant.parse("2026-01-02T03:04:05Z");
        Instant consumedAt = Instant.parse("2026-01-02T03:05:06Z");
        jdbcTemplate.update("UPDATE outbox_events SET published_at = ? WHERE id = ?", Timestamp.from(publishedAt), event.getId());
        jdbcTemplate.update("""
            INSERT INTO kafka_event_receipts (event_id, event_type, aggregate_type, aggregate_id, consumed_at)
            VALUES (?, ?, ?, ?, ?)
            """, event.getId(), event.getEventType().name(), event.getAggregateType().name(), orderId,
            Timestamp.from(consumedAt));
        long outboxCount = outboxRepository.count();

        ResponseEntity<EventInspectionResponse> detail = restTemplate.getForEntity(
            "/api/dev/events/{eventId}", EventInspectionResponse.class, event.getId());
        ResponseEntity<java.util.List<EventSummaryResponse>> list = restTemplate.exchange(
            "/api/dev/events", HttpMethod.GET, null, new ParameterizedTypeReference<>() {});

        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(detail.getBody()).satisfies(response -> {
            assertThat(response.eventId()).isEqualTo(event.getId());
            assertThat(response.relatedOrderId()).isEqualTo(orderId);
            assertThat(response.published()).isTrue();
            assertThat(response.publishedAt()).isEqualTo(publishedAt);
            assertThat(response.payload().get("orderId").asText()).isEqualTo(orderId.toString());
            assertThat(response.consumers()).singleElement().satisfies(consumer -> {
                assertThat(consumer.name()).isEqualTo(EventInspectionService.PROOF_CONSUMER);
                assertThat(consumer.receiptExists()).isTrue();
                assertThat(consumer.processedAt()).isEqualTo(consumedAt);
            });
        });
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(list.getBody()).extracting(EventSummaryResponse::eventId).contains(event.getId());
        assertThat(outboxRepository.count()).isEqualTo(outboxCount);
    }

    @Test
    void reportsPendingAndMissingReceiptEvidence() {
        UUID orderId = createOrder();
        OutboxEvent event = outboxRepository.findTop100ByOrderByCreatedAtDescIdDesc().stream()
            .filter(candidate -> candidate.getAggregateId().equals(orderId))
            .findFirst().orElseThrow();

        EventInspectionResponse response = restTemplate.getForObject(
            "/api/dev/events/{eventId}", EventInspectionResponse.class, event.getId());

        assertThat(response.published()).isFalse();
        assertThat(response.publishedAt()).isNull();
        assertThat(response.consumers()).allMatch(consumer -> !consumer.receiptExists());
    }

    @Test
    void unknownEventUsesNotFoundContract() {
        ResponseEntity<ApiError> response = restTemplate.getForEntity(
            "/api/dev/events/{eventId}", ApiError.class, UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().code()).isEqualTo("event_not_found");
    }

    private UUID createOrder() {
        String sku = "EVENT-" + UUID.randomUUID();
        productService.createProduct(sku, "Event inspection product", new BigDecimal("12.50"), 3);
        UUID cartId = cartService.createCart().getId();
        cartService.setItemQuantity(cartId, sku, 1);
        return checkoutService.checkoutIdempotently(cartId, "event-inspection-" + UUID.randomUUID()).order().getId();
    }
}
