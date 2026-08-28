package com.commercecore.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercecore.cart.CartService;
import com.commercecore.catalog.ProductService;
import com.commercecore.checkout.CheckoutService;
import com.commercecore.outbox.AggregateType;
import com.commercecore.outbox.OutboxEvent;
import com.commercecore.outbox.OutboxEventRepository;
import com.commercecore.outbox.OutboxEventType;
import com.commercecore.outbox.OutboxPublisher;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * The full path proven together: checkout writes an {@code ORDER_CREATED} outbox row in the same
 * transaction as the order, {@link OutboxPublisher} claims and sends it to the real Kafka broker,
 * and {@link KafkaEventReceiptConsumer} consumes it into exactly one {@code kafka_event_receipts}
 * row. Then the same logical event is redelivered as a second, distinct Kafka record (same
 * {@code eventId}, forced by publishing the envelope again directly) to prove the receipt table —
 * not Kafka delivery semantics — is what keeps processing idempotent.
 */
class OutboxKafkaEndToEndTest extends AbstractKafkaIntegrationTest {

    @Autowired
    private ProductService productService;

    @Autowired
    private CartService cartService;

    @Autowired
    private CheckoutService checkoutService;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private OutboxPublisher outboxPublisher;

    @Autowired
    private KafkaEventReceiptRepository receiptRepository;

    @Autowired
    private KafkaTemplate<String, EventEnvelope> kafkaTemplate;

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    private String newProductWithStockAndPrice(int stock, String price) {
        String sku = "SKU-" + UUID.randomUUID();
        productService.createProduct(sku, "Widget", new BigDecimal(price), stock);
        return sku;
    }

    @Test
    void checkoutFlowsThroughOutboxKafkaAndConsumerToExactlyOneReceipt() throws Exception {
        String sku = newProductWithStockAndPrice(5, "10.00");
        UUID cartId = cartService.createCart().getId();
        cartService.setItemQuantity(cartId, sku, 2);
        UUID orderId = checkoutService.checkoutIdempotently(cartId, "key-" + UUID.randomUUID()).order().getId();

        OutboxEvent outboxEvent = outboxEventRepository.findAll().stream()
            .filter(e -> e.getEventType() == OutboxEventType.ORDER_CREATED && e.getAggregateId().equals(orderId))
            .findFirst().orElseThrow();
        UUID eventId = outboxEvent.getId();

        publishUntilPublished(outboxPublisher, outboxEventRepository, eventId, Instant.now());

        assertThat(outboxEventRepository.findById(eventId).orElseThrow().getPublishedAt()).isNotNull();

        awaitTrue(Duration.ofSeconds(20), () -> receiptRepository.findById(eventId).isPresent());

        KafkaEventReceipt receipt = receiptRepository.findById(eventId).orElseThrow();
        assertThat(receipt.getEventType()).isEqualTo("ORDER_CREATED");
        assertThat(receipt.getAggregateType()).isEqualTo("ORDER");
        assertThat(receipt.getAggregateId()).isEqualTo(orderId);

        // Force a second, physically distinct Kafka delivery of the exact same logical event
        // (same eventId) - simulating outbox-level or Kafka-level redelivery - and prove the
        // receipt table still holds exactly one row.
        var payloadNode = objectMapper.readTree(outboxEvent.getPayload());
        EventEnvelope duplicateEnvelope = new EventEnvelope(eventId, outboxEvent.getEventType().name(),
            outboxEvent.getAggregateType().name(), orderId, outboxEvent.getCreatedAt(), payloadNode);
        kafkaTemplate.send(KafkaTopics.COMMERCE_EVENTS, orderId.toString(), duplicateEnvelope).get();

        // Give the duplicate delivery time to be (safely) reprocessed.
        Thread.sleep(3000);
        long receiptCount = receiptRepository.findAll().stream()
            .filter(r -> r.getEventId().equals(eventId)).count();
        assertThat(receiptCount).isEqualTo(1);

        long outboxRowCount = outboxEventRepository.findAll().stream()
            .filter(e -> e.getAggregateId().equals(orderId) && e.getEventType() == OutboxEventType.ORDER_CREATED)
            .count();
        assertThat(outboxRowCount).isEqualTo(1);
    }
}
