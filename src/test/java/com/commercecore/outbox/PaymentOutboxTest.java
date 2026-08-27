package com.commercecore.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercecore.AbstractIntegrationTest;
import com.commercecore.cart.CartService;
import com.commercecore.catalog.ProductService;
import com.commercecore.checkout.CheckoutService;
import com.commercecore.payment.FakePaymentProvider;
import com.commercecore.payment.Payment;
import com.commercecore.payment.PaymentService;
import com.commercecore.payment.PaymentStatus;
import com.commercecore.payment.PaymentWebhookEventRepository;
import com.commercecore.payment.PaymentWebhookService;
import com.commercecore.shared.BusinessRuleViolation;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Proves the payment side of the outbox: an event is written exactly when the payment's status
 * actually changes, never for an idempotent no-op — and, critically, that provider transport
 * events (webhook deliveries) and domain events (business facts) are counted independently. Two
 * different provider event IDs reporting the same authorization are two receipts but one
 * domain event.
 */
class PaymentOutboxTest extends AbstractIntegrationTest {

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
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private PaymentWebhookEventRepository webhookEventRepository;

    private UUID checkoutOneItem(int stock, String price, int quantity) {
        String sku = "SKU-" + UUID.randomUUID();
        productService.createProduct(sku, "Widget", new BigDecimal(price), stock);
        UUID cartId = cartService.createCart().getId();
        cartService.setItemQuantity(cartId, sku, quantity);
        return checkoutService.checkoutIdempotently(cartId, "key-" + UUID.randomUUID()).order().getId();
    }

    private List<OutboxEvent> eventsFor(UUID paymentId, OutboxEventType type) {
        return outboxEventRepository.findAll().stream()
            .filter(e -> e.getEventType() == type && e.getAggregateId().equals(paymentId))
            .toList();
    }

    @Test
    void authorizedPaymentWritesExactlyOnePaymentAuthorizedEvent() {
        fakePaymentProvider.nextOutcome(FakePaymentProvider.NextOutcome.SUCCESS);
        UUID orderId = checkoutOneItem(5, "10.00", 1);

        Payment payment = paymentService.initiatePayment(orderId);

        List<OutboxEvent> events = eventsFor(payment.getId(), OutboxEventType.PAYMENT_AUTHORIZED);
        assertThat(events).hasSize(1);
        OutboxEvent event = events.get(0);
        assertThat(event.getAggregateType()).isEqualTo(AggregateType.PAYMENT);
        assertThat(event.getPayload()).contains(payment.getId().toString())
            .contains(orderId.toString())
            .contains(payment.getProviderReference());
    }

    @Test
    void declinedPaymentWritesExactlyOnePaymentFailedEvent() {
        fakePaymentProvider.nextOutcome(FakePaymentProvider.NextOutcome.DECLINED);
        UUID orderId = checkoutOneItem(5, "10.00", 1);

        Payment payment = paymentService.initiatePayment(orderId);

        assertThat(eventsFor(payment.getId(), OutboxEventType.PAYMENT_FAILED)).hasSize(1);
        assertThat(eventsFor(payment.getId(), OutboxEventType.PAYMENT_AUTHORIZED)).isEmpty();
    }

    @Test
    void unknownPaymentWritesNoFinalOutcomeEvent() {
        fakePaymentProvider.nextOutcome(FakePaymentProvider.NextOutcome.TIMEOUT_AFTER_PROCESSING);
        UUID orderId = checkoutOneItem(5, "10.00", 1);

        Payment payment = paymentService.initiatePayment(orderId);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.UNKNOWN);

        assertThat(eventsFor(payment.getId(), OutboxEventType.PAYMENT_AUTHORIZED)).isEmpty();
        assertThat(eventsFor(payment.getId(), OutboxEventType.PAYMENT_FAILED)).isEmpty();
    }

    @Test
    void unknownResolutionByWebhookWritesExactlyOnePaymentAuthorizedEvent() {
        fakePaymentProvider.nextOutcome(FakePaymentProvider.NextOutcome.TIMEOUT_AFTER_PROCESSING);
        UUID orderId = checkoutOneItem(5, "10.00", 1);
        Payment payment = paymentService.initiatePayment(orderId);
        String reference = fakePaymentProvider.getProviderSideReference(payment.getId());

        paymentWebhookService.processWebhook("evt-" + UUID.randomUUID(), payment.getId().toString(),
            "PAYMENT_AUTHORIZED", reference);

        assertThat(eventsFor(payment.getId(), OutboxEventType.PAYMENT_AUTHORIZED)).hasSize(1);

        // Duplicate deliveries of the same event ID must not add a second domain event.
        for (int i = 0; i < 20; i++) {
            paymentWebhookService.processWebhook("evt-" + UUID.randomUUID(), payment.getId().toString(),
                "PAYMENT_AUTHORIZED", reference);
        }
        assertThat(eventsFor(payment.getId(), OutboxEventType.PAYMENT_AUTHORIZED)).hasSize(1);
    }

    @Test
    void distinctWebhookEventIdsWithSameOutcomeStillWriteOnlyOneDomainEvent() {
        fakePaymentProvider.nextOutcome(FakePaymentProvider.NextOutcome.TIMEOUT_AFTER_PROCESSING);
        UUID orderId = checkoutOneItem(5, "10.00", 1);
        Payment payment = paymentService.initiatePayment(orderId);
        String reference = fakePaymentProvider.getProviderSideReference(payment.getId());

        String evt1 = "evt-" + UUID.randomUUID();
        String evt2 = "evt-" + UUID.randomUUID();
        String evt3 = "evt-" + UUID.randomUUID();
        paymentWebhookService.processWebhook(evt1, payment.getId().toString(), "PAYMENT_AUTHORIZED", reference);
        paymentWebhookService.processWebhook(evt2, payment.getId().toString(), "PAYMENT_AUTHORIZED", reference);
        paymentWebhookService.processWebhook(evt3, payment.getId().toString(), "PAYMENT_AUTHORIZED", reference);

        // Three distinct transport receipts...
        assertThat(webhookEventRepository.findById(evt1)).isPresent();
        assertThat(webhookEventRepository.findById(evt2)).isPresent();
        assertThat(webhookEventRepository.findById(evt3)).isPresent();
        // ...but exactly one domain event, proving transport events != domain events.
        assertThat(eventsFor(payment.getId(), OutboxEventType.PAYMENT_AUTHORIZED)).hasSize(1);
    }

    @Test
    void rejectedTerminalConflictWebhookWritesNoDomainEvent() {
        fakePaymentProvider.nextOutcome(FakePaymentProvider.NextOutcome.SUCCESS);
        UUID orderId = checkoutOneItem(5, "10.00", 1);
        Payment payment = paymentService.initiatePayment(orderId);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);

        try {
            paymentWebhookService.processWebhook("evt-conflict-" + UUID.randomUUID(), payment.getId().toString(),
                "PAYMENT_DECLINED", null);
        } catch (BusinessRuleViolation expected) {
            // conflicting_payment_event — expected
        }

        assertThat(eventsFor(payment.getId(), OutboxEventType.PAYMENT_FAILED)).isEmpty();
        assertThat(eventsFor(payment.getId(), OutboxEventType.PAYMENT_AUTHORIZED)).hasSize(1);
    }
}
