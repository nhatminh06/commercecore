package com.commercecore.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercecore.cart.CartService;
import com.commercecore.catalog.ProductService;
import com.commercecore.checkout.CheckoutService;
import com.commercecore.checkout.Order;
import com.commercecore.checkout.OrderRepository;
import com.commercecore.checkout.OrderStatus;
import com.commercecore.inventory.InventoryService;
import com.commercecore.kafka.AbstractKafkaIntegrationTest;
import com.commercecore.outbox.OutboxEvent;
import com.commercecore.outbox.OutboxEventRepository;
import com.commercecore.outbox.OutboxEventType;
import com.commercecore.outbox.OutboxPublisher;
import com.commercecore.reservation.InventoryReservation;
import com.commercecore.reservation.ReservationService;
import com.commercecore.reservation.ReservationStatus;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The mandatory end-to-end recovery scenarios, against a real Kafka broker: an ambiguous
 * (UNKNOWN) payment repaired via reconciliation must flow through the existing outbox/Kafka order
 * workflow exactly the same way a normal payment outcome does — reaching a real, final business
 * state, not just a repaired {@code payments} row.
 */
class PaymentReconciliationEndToEndTest extends AbstractKafkaIntegrationTest {

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
    private PaymentRepository paymentRepository;

    @Autowired
    private PaymentReconciliationService reconciliationService;

    @Autowired
    private PaymentReconciliationCaseRepository reconciliationCaseRepository;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private OutboxPublisher outboxPublisher;

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

    // --- Mandatory safe recovery end-to-end (test 53) -----------------------------------------

    @Test
    void safeUnknownToAuthorizedRecoveryReachesConfirmedOrder() throws Exception {
        String sku = newProductWithStock(5);
        UUID orderId = checkout(sku, 2);
        assertThat(inventoryService.getBySku(sku).getAvailableQuantity()).isEqualTo(3);

        fakePaymentProvider.nextOutcome(FakePaymentProvider.NextOutcome.TIMEOUT_AFTER_PROCESSING);
        Payment initiated = paymentService.initiatePayment(orderId);
        assertThat(initiated.getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.PENDING);

        int authCallsBefore = fakePaymentProvider.callCount();
        reconciliationService.reconcile(initiated.getId());
        assertThat(fakePaymentProvider.callCount()).isEqualTo(authCallsBefore);

        Payment repaired = paymentRepository.findById(initiated.getId()).orElseThrow();
        assertThat(repaired.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
        OutboxEvent paymentEvent = paymentEventFor(initiated.getId(), OutboxEventType.PAYMENT_AUTHORIZED);
        assertThat(reconciliationCaseRepository.findByPaymentId(initiated.getId()).orElseThrow().getStatus())
            .isEqualTo(ReconciliationStatus.RESOLVED);

        publishUntilPublished(outboxPublisher, outboxEventRepository, paymentEvent.getId(), Instant.now());

        awaitTrue(Duration.ofSeconds(20),
            () -> orderRepository.findById(orderId).map(o -> o.getStatus() == OrderStatus.CONFIRMED).orElse(false));

        Order confirmedOrder = orderRepository.findById(orderId).orElseThrow();
        assertThat(confirmedOrder.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(reservationService.getReservationsForOrder(orderId))
            .allMatch(r -> r.getStatus() == ReservationStatus.CONFIRMED);
        assertThat(inventoryService.getBySku(sku).getAvailableQuantity()).isEqualTo(3);
    }

    // --- Mandatory unsafe recovery end-to-end (test 54) ---------------------------------------

    @Test
    void unsafeAuthorizedAfterExpiryNeverConfirmsOrOversells() throws Exception {
        String sku = newProductWithStock(5);
        UUID orderId = checkout(sku, 2);

        fakePaymentProvider.nextOutcome(FakePaymentProvider.NextOutcome.TIMEOUT_AFTER_PROCESSING);
        Payment initiated = paymentService.initiatePayment(orderId);

        InventoryReservation reservation = reservationService.getReservationsForOrder(orderId).get(0);
        reservationService.expireDueReservations(reservation.getExpiresAt());
        assertThat(inventoryService.getBySku(sku).getAvailableQuantity()).isEqualTo(5);

        reconciliationService.reconcile(initiated.getId());

        Payment repaired = paymentRepository.findById(initiated.getId()).orElseThrow();
        assertThat(repaired.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(reconciliationCaseRepository.findByPaymentId(initiated.getId()).orElseThrow().getStatus())
            .isEqualTo(ReconciliationStatus.REQUIRES_REVIEW);

        OutboxEvent paymentEvent = paymentEventFor(initiated.getId(), OutboxEventType.PAYMENT_AUTHORIZED);
        publishUntilPublished(outboxPublisher, outboxEventRepository, paymentEvent.getId(), Instant.now());

        // Give the workflow consumer time to receive and (correctly) refuse to confirm.
        Thread.sleep(4000);

        Order order = orderRepository.findById(orderId).orElseThrow();
        assertThat(order.getStatus()).as("order must never be auto-confirmed for lost stock")
            .isEqualTo(OrderStatus.PENDING);
        assertThat(reservationService.getReservation(reservation.getId()).getStatus())
            .as("no automatic re-reservation").isEqualTo(ReservationStatus.EXPIRED);
        assertThat(inventoryService.getBySku(sku).getAvailableQuantity()).as("no overselling").isEqualTo(5);
        assertThat(reconciliationCaseRepository.findByPaymentId(initiated.getId()).orElseThrow().getStatus())
            .as("durable reconciliation evidence remains").isEqualTo(ReconciliationStatus.REQUIRES_REVIEW);
    }
}
