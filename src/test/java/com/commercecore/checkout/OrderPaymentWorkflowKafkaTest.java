package com.commercecore.checkout;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercecore.cart.CartService;
import com.commercecore.catalog.ProductService;
import com.commercecore.inventory.InventoryService;
import com.commercecore.kafka.AbstractKafkaIntegrationTest;
import com.commercecore.kafka.EventEnvelope;
import com.commercecore.kafka.KafkaEventReceiptRepository;
import com.commercecore.kafka.KafkaTopics;
import com.commercecore.outbox.OutboxEvent;
import com.commercecore.outbox.OutboxEventRepository;
import com.commercecore.outbox.OutboxEventType;
import com.commercecore.outbox.OutboxPublisher;
import com.commercecore.payment.FakePaymentProvider;
import com.commercecore.payment.Payment;
import com.commercecore.payment.PaymentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Real Kafka broker (Testcontainers). Proves the order workflow's redelivery/consumer-group
 * properties against real physical duplicate deliveries, not just direct repeated service calls
 * (those live in {@link OrderPaymentWorkflowServiceTest}, which covers the bulk of the business
 * transaction semantics without needing a broker).
 */
class OrderPaymentWorkflowKafkaTest extends AbstractKafkaIntegrationTest {

    @Autowired
    private ProductService productService;

    @Autowired
    private CartService cartService;

    @Autowired
    private CheckoutService checkoutService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private FakePaymentProvider fakePaymentProvider;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private OrderWorkflowEventReceiptRepository workflowReceiptRepository;

    @Autowired
    private KafkaEventReceiptRepository proofReceiptRepository;

    @Autowired
    private OrderPaymentWorkflowConsumer workflowConsumer;

    @Autowired
    private OutboxPublisher outboxPublisher;

    @Autowired
    private KafkaTemplate<String, EventEnvelope> kafkaTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @AfterEach
    void resetFailureHook() {
        workflowConsumer.failAfterCommitBeforeAcknowledge(false);
    }

    private String newProductWithStock(int stock) {
        String sku = "SKU-" + UUID.randomUUID();
        productService.createProduct(sku, "Widget", new BigDecimal("10.00"), stock);
        return sku;
    }

    private UUID checkout(String sku, int quantity) {
        UUID cartId = cartService.createCart().getId();
        cartService.setItemQuantity(cartId, sku, quantity);
        return checkoutService.checkoutIdempotently(cartId, "key-" + UUID.randomUUID()).order().getId();
    }

    private OutboxEvent paymentEventFor(UUID paymentId, OutboxEventType type) {
        return outboxEventRepository.findAll().stream()
            .filter(e -> e.getEventType() == type && e.getAggregateId().equals(paymentId))
            .findFirst().orElseThrow();
    }

    private EventEnvelope envelopeFrom(OutboxEvent event) throws Exception {
        return new EventEnvelope(event.getId(), event.getEventType().name(), event.getAggregateType().name(),
            event.getAggregateId(), event.getCreatedAt(), objectMapper.readTree(event.getPayload()));
    }

    private void send(EventEnvelope envelope) throws Exception {
        kafkaTemplate.send(KafkaTopics.COMMERCE_EVENTS, envelope.aggregateId().toString(), envelope).get();
    }

    private long orderEventCount(UUID orderId, OutboxEventType type) {
        return outboxEventRepository.findAll().stream()
            .filter(e -> e.getEventType() == type && e.getAggregateId().equals(orderId))
            .count();
    }

    private long workflowReceiptCount(UUID eventId) {
        return workflowReceiptRepository.findAll().stream().filter(r -> r.getEventId().equals(eventId)).count();
    }

    // --- Mandatory physical duplicate delivery (tests 33-34) --------------------------------

    @Test
    void duplicatePhysicalAuthorizedDeliveriesConfirmOrderExactlyOnce() throws Exception {
        String sku = newProductWithStock(5);
        UUID orderId = checkout(sku, 2);
        fakePaymentProvider.nextOutcome(FakePaymentProvider.NextOutcome.SUCCESS);
        Payment payment = paymentService.initiatePayment(orderId);
        OutboxEvent outboxEvent = paymentEventFor(payment.getId(), OutboxEventType.PAYMENT_AUTHORIZED);
        EventEnvelope envelope = envelopeFrom(outboxEvent);

        for (int i = 0; i < 20; i++) {
            send(envelope);
        }

        awaitTrue(Duration.ofSeconds(30),
            () -> orderRepository.findById(orderId).map(o -> o.getStatus() == OrderStatus.CONFIRMED).orElse(false));
        Thread.sleep(3000); // let any redundant physical deliveries be (safely) reprocessed

        assertThat(workflowReceiptCount(outboxEvent.getId())).isEqualTo(1);
        assertThat(orderEventCount(orderId, OutboxEventType.ORDER_CONFIRMED)).isEqualTo(1);
        assertThat(inventoryService.getBySku(sku).getAvailableQuantity()).isEqualTo(3);
    }

    @Test
    void duplicatePhysicalFailedDeliveriesRestoreInventoryExactlyOnce() throws Exception {
        String sku = newProductWithStock(5);
        UUID orderId = checkout(sku, 2);
        fakePaymentProvider.nextOutcome(FakePaymentProvider.NextOutcome.DECLINED);
        Payment payment = paymentService.initiatePayment(orderId);
        OutboxEvent outboxEvent = paymentEventFor(payment.getId(), OutboxEventType.PAYMENT_FAILED);
        EventEnvelope envelope = envelopeFrom(outboxEvent);

        for (int i = 0; i < 20; i++) {
            send(envelope);
        }

        awaitTrue(Duration.ofSeconds(30),
            () -> orderRepository.findById(orderId).map(o -> o.getStatus() == OrderStatus.CANCELLED).orElse(false));
        Thread.sleep(3000);

        assertThat(workflowReceiptCount(outboxEvent.getId())).isEqualTo(1);
        assertThat(orderEventCount(orderId, OutboxEventType.ORDER_CANCELLED)).isEqualTo(1);
        assertThat(inventoryService.getBySku(sku).getAvailableQuantity()).isEqualTo(5);
    }

    // --- Independent consumer groups (test 48) ------------------------------------------------

    @Test
    void bothConsumerGroupsIndependentlyProcessTheSamePaymentEvent() throws Exception {
        String sku = newProductWithStock(5);
        UUID orderId = checkout(sku, 2);
        fakePaymentProvider.nextOutcome(FakePaymentProvider.NextOutcome.SUCCESS);
        Payment payment = paymentService.initiatePayment(orderId);
        OutboxEvent outboxEvent = paymentEventFor(payment.getId(), OutboxEventType.PAYMENT_AUTHORIZED);
        send(envelopeFrom(outboxEvent));

        awaitTrue(Duration.ofSeconds(20), () -> proofReceiptRepository.findById(outboxEvent.getId()).isPresent()
            && workflowReceiptRepository.findById(outboxEvent.getId()).isPresent());

        assertThat(proofReceiptRepository.findById(outboxEvent.getId())).isPresent();
        assertThat(workflowReceiptRepository.findById(outboxEvent.getId())).isPresent();
        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    }

    // --- Consumer failure after commit, before acknowledge (test 45) ------------------------

    @Test
    void consumerFailureAfterCommitBeforeAcknowledgeIsSafeOnRedelivery() throws Exception {
        String sku = newProductWithStock(5);
        UUID orderId = checkout(sku, 2);
        fakePaymentProvider.nextOutcome(FakePaymentProvider.NextOutcome.SUCCESS);
        Payment payment = paymentService.initiatePayment(orderId);
        OutboxEvent outboxEvent = paymentEventFor(payment.getId(), OutboxEventType.PAYMENT_AUTHORIZED);
        EventEnvelope envelope = envelopeFrom(outboxEvent);

        workflowConsumer.failAfterCommitBeforeAcknowledge(true);
        send(envelope);

        // The workflow transaction commits on the very first delivery even though the consumer
        // then throws before acknowledging — the offset stays uncommitted.
        awaitTrue(Duration.ofSeconds(20),
            () -> orderRepository.findById(orderId).map(o -> o.getStatus() == OrderStatus.CONFIRMED).orElse(false));
        assertThat(workflowReceiptRepository.findById(outboxEvent.getId())).isPresent();

        workflowConsumer.failAfterCommitBeforeAcknowledge(false);

        // Redelivery of the same (never-acknowledged) record must not double-confirm the order,
        // double-emit ORDER_CONFIRMED, or add a second receipt.
        Thread.sleep(3000);
        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(workflowReceiptCount(outboxEvent.getId())).isEqualTo(1);
        assertThat(orderEventCount(orderId, OutboxEventType.ORDER_CONFIRMED)).isEqualTo(1);
    }

    // --- End-to-end outbox loop prevention (tests 49, 51, 52) --------------------------------

    @Test
    void authorizedWorkflowEndToEndProducesOrderConfirmedOutboxWithoutTriggeringALoop() throws Exception {
        String sku = newProductWithStock(5);
        UUID orderId = checkout(sku, 2);
        fakePaymentProvider.nextOutcome(FakePaymentProvider.NextOutcome.SUCCESS);
        Payment payment = paymentService.initiatePayment(orderId);
        OutboxEvent paymentEvent = paymentEventFor(payment.getId(), OutboxEventType.PAYMENT_AUTHORIZED);
        send(envelopeFrom(paymentEvent));

        awaitTrue(Duration.ofSeconds(20),
            () -> orderRepository.findById(orderId).map(o -> o.getStatus() == OrderStatus.CONFIRMED).orElse(false));

        OutboxEvent orderConfirmedEvent = outboxEventRepository.findAll().stream()
            .filter(e -> e.getEventType() == OutboxEventType.ORDER_CONFIRMED && e.getAggregateId().equals(orderId))
            .findFirst().orElseThrow();
        assertThat(orderConfirmedEvent.getPublishedAt()).isNull();

        publishUntilPublished(outboxPublisher, outboxEventRepository, orderConfirmedEvent.getId(), Instant.now());
        assertThat(outboxEventRepository.findById(orderConfirmedEvent.getId()).orElseThrow().getPublishedAt())
            .isNotNull();

        // Give the workflow consumer time to receive and (correctly) ignore its own
        // ORDER_CONFIRMED output before asserting nothing further happened.
        Thread.sleep(3000);

        assertThat(orderEventCount(orderId, OutboxEventType.ORDER_CONFIRMED)).isEqualTo(1);
        assertThat(orderEventCount(orderId, OutboxEventType.ORDER_CANCELLED)).isZero();
        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        long workflowReceiptsForOrder =
            workflowReceiptRepository.findAll().stream().filter(r -> r.getOrderId().equals(orderId)).count();
        assertThat(workflowReceiptsForOrder).isEqualTo(1);
    }
}
