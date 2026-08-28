package com.commercecore.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercecore.AbstractIntegrationTest;
import com.commercecore.cart.CartService;
import com.commercecore.catalog.ProductService;
import com.commercecore.checkout.CheckoutService;
import com.commercecore.checkout.Order;
import com.commercecore.checkout.OrderRepository;
import com.commercecore.checkout.OrderStatus;
import com.commercecore.inventory.InventoryService;
import com.commercecore.outbox.OutboxEvent;
import com.commercecore.outbox.OutboxEventRepository;
import com.commercecore.outbox.OutboxEventType;
import com.commercecore.reservation.InventoryReservation;
import com.commercecore.reservation.ReservationService;
import com.commercecore.reservation.ReservationStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Direct, Kafka-free tests of {@link PaymentReconciliationService}/{@link PaymentReconciliationApplier}
 * against real PostgreSQL. End-to-end Kafka recovery (the mandatory safe/unsafe scenarios) lives
 * in {@code PaymentReconciliationEndToEndTest} instead.
 */
class PaymentReconciliationServiceTest extends AbstractIntegrationTest {

    @Autowired
    private ProductService productService;

    @Autowired
    private CartService cartService;

    @Autowired
    private CheckoutService checkoutService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentWebhookService paymentWebhookService;

    @Autowired
    private FakePaymentProvider fakePaymentProvider;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private PaymentReconciliationCaseRepository reconciliationCaseRepository;

    @Autowired
    private PaymentReconciliationService reconciliationService;

    @Autowired
    private PaymentReconciliationApplier reconciliationApplier;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

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

    private long eventsFor(UUID paymentId, OutboxEventType type) {
        return outboxEventRepository.findAll().stream()
            .filter(e -> e.getEventType() == type && e.getAggregateId().equals(paymentId))
            .count();
    }

    /** Genuinely unknown to the fake provider: never routed through {@code authorize()} at all. */
    private Payment newUnknownPaymentNeverSeenByProvider(UUID orderId, BigDecimal amount) {
        UUID paymentId = UUID.randomUUID();
        Instant now = Instant.now();
        paymentRepository.tryCreatePayment(paymentId, orderId, amount, now);
        paymentRepository.markUnknown(paymentId, now);
        return paymentRepository.findById(paymentId).orElseThrow();
    }

    // --- Basic UNKNOWN -> AUTHORIZED / FAILED (tests 32-33) ----------------------------------

    @Test
    void basicUnknownToAuthorizedReconciliationRepairsPaymentWithoutReauthorizing() {
        UUID orderId = checkout(newProductWithStock(5), 2);
        fakePaymentProvider.nextOutcome(FakePaymentProvider.NextOutcome.TIMEOUT_AFTER_PROCESSING);
        Payment initiated = paymentService.initiatePayment(orderId);
        assertThat(initiated.getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(reservationService.getReservationsForOrder(orderId))
            .allMatch(r -> r.getStatus() == ReservationStatus.ACTIVE);

        int authCallsBefore = fakePaymentProvider.callCount();

        Optional<PaymentReconciliationCase> result = reconciliationService.reconcile(initiated.getId());

        assertThat(fakePaymentProvider.callCount()).isEqualTo(authCallsBefore);
        Payment resolved = paymentRepository.findById(initiated.getId()).orElseThrow();
        assertThat(resolved.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(resolved.getProviderReference()).isNotNull();
        assertThat(eventsFor(initiated.getId(), OutboxEventType.PAYMENT_AUTHORIZED)).isEqualTo(1);
        assertThat(result).isPresent();
        assertThat(result.get().getStatus()).isEqualTo(ReconciliationStatus.RESOLVED);
    }

    @Test
    void unknownToDeclinedReconciliationRepairsPaymentToFailed() {
        UUID orderId = checkout(newProductWithStock(5), 2);
        fakePaymentProvider.nextOutcome(FakePaymentProvider.NextOutcome.TIMEOUT_AFTER_DECLINE);
        Payment initiated = paymentService.initiatePayment(orderId);
        assertThat(initiated.getStatus()).isEqualTo(PaymentStatus.UNKNOWN);

        Optional<PaymentReconciliationCase> result = reconciliationService.reconcile(initiated.getId());

        assertThat(paymentRepository.findById(initiated.getId()).orElseThrow().getStatus())
            .isEqualTo(PaymentStatus.FAILED);
        assertThat(eventsFor(initiated.getId(), OutboxEventType.PAYMENT_FAILED)).isEqualTo(1);
        assertThat(result).isPresent();
        assertThat(result.get().getStatus()).isEqualTo(ReconciliationStatus.RESOLVED);
    }

    // --- NOT_FOUND leaves payment unresolved (test 34) ---------------------------------------

    @Test
    void notFoundLeavesPaymentUnknownAndCaseOpen() {
        UUID orderId = checkout(newProductWithStock(5), 2);
        Payment payment = newUnknownPaymentNeverSeenByProvider(orderId, new BigDecimal("20.00"));

        Optional<PaymentReconciliationCase> result = reconciliationService.reconcile(payment.getId());

        assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getStatus())
            .isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(eventsFor(payment.getId(), OutboxEventType.PAYMENT_AUTHORIZED)).isZero();
        assertThat(eventsFor(payment.getId(), OutboxEventType.PAYMENT_FAILED)).isZero();
        assertThat(result).isPresent();
        assertThat(result.get().getStatus()).isEqualTo(ReconciliationStatus.OPEN);
        assertThat(result.get().getLastProviderStatus()).isEqualTo(ProviderPaymentStatus.NOT_FOUND);
        assertThat(result.get().getReason()).isEqualTo(ReconciliationReason.PROVIDER_NOT_FOUND);
        assertThat(result.get().getAttemptCount()).isEqualTo(1);
    }

    // --- Repeated reconciliation is idempotent (test 35, includes mandatory count proof 38) --

    @Test
    void repeatedReconciliationAfterRepairStaysIdempotent() {
        UUID orderId = checkout(newProductWithStock(5), 2);
        fakePaymentProvider.nextOutcome(FakePaymentProvider.NextOutcome.TIMEOUT_AFTER_PROCESSING);
        Payment initiated = paymentService.initiatePayment(orderId);

        int authCallsBefore = fakePaymentProvider.callCount();
        int lookupCallsBefore = fakePaymentProvider.lookupCallCount();

        for (int i = 0; i < 20; i++) {
            reconciliationService.reconcile(initiated.getId());
        }

        assertThat(fakePaymentProvider.callCount()).as("authorization call count must never increase")
            .isEqualTo(authCallsBefore);
        assertThat(fakePaymentProvider.lookupCallCount()).as("lookup calls are safe reads, expected to increase")
            .isGreaterThan(lookupCallsBefore);
        assertThat(eventsFor(initiated.getId(), OutboxEventType.PAYMENT_AUTHORIZED)).isEqualTo(1);
        assertThat(paymentRepository.findById(initiated.getId()).orElseThrow().getStatus())
            .isEqualTo(PaymentStatus.AUTHORIZED);
        PaymentReconciliationCase finalCase = reconciliationCaseRepository.findByPaymentId(initiated.getId())
            .orElseThrow();
        assertThat(finalCase.getAttemptCount()).isEqualTo(20);
    }

    // --- Concurrent reconciliation produces one transition (test 36) ------------------------

    @Test
    void concurrentReconciliationProducesOnePaymentTransition() throws Exception {
        UUID orderId = checkout(newProductWithStock(5), 2);
        fakePaymentProvider.nextOutcome(FakePaymentProvider.NextOutcome.TIMEOUT_AFTER_PROCESSING);
        Payment initiated = paymentService.initiatePayment(orderId);
        int authCallsBefore = fakePaymentProvider.callCount();

        int workers = 20;
        CyclicBarrier barrier = new CyclicBarrier(workers);
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch done = new CountDownLatch(workers);
        try {
            for (int i = 0; i < workers; i++) {
                executor.submit(() -> {
                    try {
                        barrier.await();
                        reconciliationService.reconcile(initiated.getId());
                    } catch (Exception ignored) {
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdown();
        }

        assertThat(fakePaymentProvider.callCount()).isEqualTo(authCallsBefore);
        assertThat(paymentRepository.findById(initiated.getId()).orElseThrow().getStatus())
            .isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(eventsFor(initiated.getId(), OutboxEventType.PAYMENT_AUTHORIZED)).isEqualTo(1);
    }

    // --- Authorized-after-expiry: mandatory unsafe-recovery evidence (test 39) --------------

    @Test
    void authorizedAfterReservationExpiryRecordsRequiresReviewWithoutOverselling() {
        String sku = newProductWithStock(5);
        UUID orderId = checkout(sku, 2);
        assertThat(inventoryService.getBySku(sku).getAvailableQuantity()).isEqualTo(3);
        fakePaymentProvider.nextOutcome(FakePaymentProvider.NextOutcome.TIMEOUT_AFTER_PROCESSING);
        Payment initiated = paymentService.initiatePayment(orderId);

        InventoryReservation reservation = reservationService.getReservationsForOrder(orderId).get(0);
        reservationService.expireDueReservations(reservation.getExpiresAt());
        assertThat(inventoryService.getBySku(sku).getAvailableQuantity()).isEqualTo(5);

        Optional<PaymentReconciliationCase> result = reconciliationService.reconcile(initiated.getId());

        assertThat(paymentRepository.findById(initiated.getId()).orElseThrow().getStatus())
            .isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(result).isPresent();
        assertThat(result.get().getStatus()).isEqualTo(ReconciliationStatus.REQUIRES_REVIEW);
        assertThat(result.get().getReason()).isEqualTo(ReconciliationReason.AUTHORIZED_WITHOUT_RESERVED_STOCK);

        Order order = orderRepository.findById(orderId).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(reservationService.getReservation(reservation.getId()).getStatus())
            .isEqualTo(ReservationStatus.EXPIRED);
        assertThat(inventoryService.getBySku(sku).getAvailableQuantity()).isEqualTo(5);
    }

    // --- Terminal local/provider contradictions never reverse local state (test 40) ---------

    @Test
    void localAuthorizedWithProviderDeclinedIsRequiresReviewNotReversed() {
        UUID orderId = checkout(newProductWithStock(5), 2);
        fakePaymentProvider.nextOutcome(FakePaymentProvider.NextOutcome.SUCCESS);
        Payment authorized = paymentService.initiatePayment(orderId);
        assertThat(authorized.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
        String originalReference = authorized.getProviderReference();

        Optional<PaymentReconciliationCase> result =
            reconciliationApplier.apply(authorized.getId(), new ProviderLookupResult(ProviderPaymentStatus.DECLINED, null));

        Payment after = paymentRepository.findById(authorized.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(after.getProviderReference()).isEqualTo(originalReference);
        assertThat(eventsFor(authorized.getId(), OutboxEventType.PAYMENT_FAILED)).isZero();
        assertThat(result).isPresent();
        assertThat(result.get().getStatus()).isEqualTo(ReconciliationStatus.REQUIRES_REVIEW);
        assertThat(result.get().getReason()).isEqualTo(ReconciliationReason.LOCAL_AUTHORIZED_PROVIDER_DECLINED);
    }

    @Test
    void localFailedWithProviderAuthorizedIsRequiresReviewNotReversed() {
        UUID orderId = checkout(newProductWithStock(5), 2);
        fakePaymentProvider.nextOutcome(FakePaymentProvider.NextOutcome.DECLINED);
        Payment failed = paymentService.initiatePayment(orderId);
        assertThat(failed.getStatus()).isEqualTo(PaymentStatus.FAILED);

        Optional<PaymentReconciliationCase> result = reconciliationApplier.apply(failed.getId(),
            new ProviderLookupResult(ProviderPaymentStatus.AUTHORIZED, "pay_" + UUID.randomUUID()));

        Payment after = paymentRepository.findById(failed.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(eventsFor(failed.getId(), OutboxEventType.PAYMENT_AUTHORIZED)).isZero();
        assertThat(result).isPresent();
        assertThat(result.get().getStatus()).isEqualTo(ReconciliationStatus.REQUIRES_REVIEW);
        assertThat(result.get().getReason()).isEqualTo(ReconciliationReason.LOCAL_FAILED_PROVIDER_AUTHORIZED);
    }

    // --- Provider-reference mismatch (test 41) -----------------------------------------------

    @Test
    void providerReferenceMismatchIsRequiresReviewAndDoesNotOverwriteReference() {
        UUID orderId = checkout(newProductWithStock(5), 2);
        fakePaymentProvider.nextOutcome(FakePaymentProvider.NextOutcome.SUCCESS);
        Payment authorized = paymentService.initiatePayment(orderId);
        String originalReference = authorized.getProviderReference();

        Optional<PaymentReconciliationCase> result = reconciliationApplier.apply(authorized.getId(),
            new ProviderLookupResult(ProviderPaymentStatus.AUTHORIZED, "pay_completely_different"));

        Payment after = paymentRepository.findById(authorized.getId()).orElseThrow();
        assertThat(after.getProviderReference()).isEqualTo(originalReference);
        assertThat(result).isPresent();
        assertThat(result.get().getStatus()).isEqualTo(ReconciliationStatus.REQUIRES_REVIEW);
        assertThat(result.get().getReason()).isEqualTo(ReconciliationReason.PROVIDER_REFERENCE_MISMATCH);
    }

    // --- Matching provider truth for an already-terminal payment is a safe no-op (tests 15-16) --

    @Test
    void matchingAuthorizedProviderTruthResolvesWithoutANewOutboxEvent() {
        UUID orderId = checkout(newProductWithStock(5), 2);
        fakePaymentProvider.nextOutcome(FakePaymentProvider.NextOutcome.SUCCESS);
        Payment authorized = paymentService.initiatePayment(orderId);
        int authCallsBefore = fakePaymentProvider.callCount();

        Optional<PaymentReconciliationCase> result = reconciliationService.reconcile(authorized.getId());

        assertThat(fakePaymentProvider.callCount()).isEqualTo(authCallsBefore);
        assertThat(eventsFor(authorized.getId(), OutboxEventType.PAYMENT_AUTHORIZED)).isEqualTo(1);
        assertThat(result).isPresent();
        assertThat(result.get().getStatus()).isEqualTo(ReconciliationStatus.RESOLVED);
    }

    @Test
    void matchingDeclinedProviderTruthResolvesWithoutANewOutboxEvent() {
        UUID orderId = checkout(newProductWithStock(5), 2);
        fakePaymentProvider.nextOutcome(FakePaymentProvider.NextOutcome.DECLINED);
        Payment failed = paymentService.initiatePayment(orderId);

        Optional<PaymentReconciliationCase> result = reconciliationService.reconcile(failed.getId());

        assertThat(eventsFor(failed.getId(), OutboxEventType.PAYMENT_FAILED)).isEqualTo(1);
        assertThat(result).isPresent();
        assertThat(result.get().getStatus()).isEqualTo(ReconciliationStatus.RESOLVED);
    }

    // --- Batch reconciliation isolates failures per-payment (test 42) -----------------------

    @Test
    void batchReconciliationResolvesEachPaymentIndependently() {
        UUID orderA = checkout(newProductWithStock(5), 1);
        fakePaymentProvider.nextOutcome(FakePaymentProvider.NextOutcome.TIMEOUT_AFTER_PROCESSING);
        Payment paymentA = paymentService.initiatePayment(orderA);

        UUID orderB = checkout(newProductWithStock(5), 1);
        fakePaymentProvider.nextOutcome(FakePaymentProvider.NextOutcome.TIMEOUT_AFTER_DECLINE);
        Payment paymentB = paymentService.initiatePayment(orderB);

        UUID orderC = checkout(newProductWithStock(5), 1);
        Payment paymentC = newUnknownPaymentNeverSeenByProvider(orderC, new BigDecimal("10.00"));

        PaymentReconciliationService.BatchResult batch = reconciliationService.reconcileUnknownBatch(25);

        assertThat(batch.attempted()).contains(paymentA.getId(), paymentB.getId(), paymentC.getId());

        assertThat(paymentRepository.findById(paymentA.getId()).orElseThrow().getStatus())
            .isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(paymentRepository.findById(paymentB.getId()).orElseThrow().getStatus())
            .isEqualTo(PaymentStatus.FAILED);
        assertThat(paymentRepository.findById(paymentC.getId()).orElseThrow().getStatus())
            .isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(reconciliationCaseRepository.findByPaymentId(paymentC.getId()).orElseThrow().getStatus())
            .isEqualTo(ReconciliationStatus.OPEN);
    }

    // --- Persistence (test 43) -----------------------------------------------------------------

    @Test
    void reconciliationCasePersistsInPostgresNotJvmMemory() {
        UUID orderId = checkout(newProductWithStock(5), 2);
        Payment payment = newUnknownPaymentNeverSeenByProvider(orderId, new BigDecimal("10.00"));

        reconciliationService.reconcile(payment.getId());

        // Re-fetched from a freshly autowired repository call, not from the service's own return
        // value — proving the state genuinely lives in PostgreSQL.
        PaymentReconciliationCase persisted = reconciliationCaseRepository.findByPaymentId(payment.getId())
            .orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(ReconciliationStatus.OPEN);
    }

    // --- Webhook vs. reconciliation race (test 37) -------------------------------------------

    @Test
    void webhookAndReconciliationRaceProduceExactlyOnePaymentOutcomeEvent() throws Exception {
        UUID orderId = checkout(newProductWithStock(5), 2);
        fakePaymentProvider.nextOutcome(FakePaymentProvider.NextOutcome.TIMEOUT_AFTER_PROCESSING);
        Payment initiated = paymentService.initiatePayment(orderId);
        String providerReference = fakePaymentProvider.getProviderSideReference(initiated.getId());

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch done = new CountDownLatch(2);
        Set<Exception> errors = ConcurrentHashMap.newKeySet();
        try {
            executor.submit(() -> {
                try {
                    barrier.await();
                    paymentWebhookService.processWebhook("evt-" + UUID.randomUUID(), initiated.getId().toString(),
                        "PAYMENT_AUTHORIZED", providerReference);
                } catch (Exception e) {
                    errors.add(e);
                } finally {
                    done.countDown();
                }
            });
            executor.submit(() -> {
                try {
                    barrier.await();
                    reconciliationService.reconcile(initiated.getId());
                } catch (Exception e) {
                    errors.add(e);
                } finally {
                    done.countDown();
                }
            });
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdown();
        }

        assertThat(errors).as("neither path should ever throw during a legitimate race").isEmpty();
        Payment after = paymentRepository.findById(initiated.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(after.getProviderReference()).isEqualTo(providerReference);
        assertThat(eventsFor(initiated.getId(), OutboxEventType.PAYMENT_AUTHORIZED)).isEqualTo(1);
    }
}
