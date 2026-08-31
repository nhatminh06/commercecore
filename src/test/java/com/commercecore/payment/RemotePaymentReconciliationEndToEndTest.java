package com.commercecore.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercecore.cart.CartService;
import com.commercecore.catalog.ProductService;
import com.commercecore.checkout.CheckoutService;
import com.commercecore.checkout.OrderRepository;
import com.commercecore.checkout.OrderStatus;
import com.commercecore.inventory.InventoryService;
import com.commercecore.kafka.AbstractKafkaIntegrationTest;
import com.commercecore.outbox.OutboxEvent;
import com.commercecore.outbox.OutboxEventRepository;
import com.commercecore.outbox.OutboxEventType;
import com.commercecore.outbox.OutboxPublisher;
import com.commercecore.payment.proto.AuthorizationOutcome;
import com.commercecore.payment.proto.AuthorizePaymentRequest;
import com.commercecore.payment.proto.AuthorizePaymentResponse;
import com.commercecore.payment.proto.LookupOutcome;
import com.commercecore.payment.proto.LookupPaymentRequest;
import com.commercecore.payment.proto.LookupPaymentResponse;
import com.commercecore.payment.proto.PaymentProviderServiceGrpc;
import com.commercecore.reservation.ReservationService;
import com.commercecore.reservation.ReservationStatus;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@ActiveProfiles(profiles = "kafka", inheritProfiles = false)
class RemotePaymentReconciliationEndToEndTest extends AbstractKafkaIntegrationTest {

    private static final RemoteProvider PROVIDER = new RemoteProvider();
    private static final Server GRPC_SERVER;

    static {
        try {
            GRPC_SERVER = NettyServerBuilder.forPort(0).addService(PROVIDER).build().start();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void grpcProperties(DynamicPropertyRegistry registry) {
        registry.add("commercecore.payment.grpc.host", () -> "localhost");
        registry.add("commercecore.payment.grpc.port", GRPC_SERVER::getPort);
        registry.add("commercecore.payment.grpc.deadline-ms", () -> 250);
    }

    @AfterAll
    static void stopGrpc() {
        GRPC_SERVER.shutdownNow();
    }

    @Autowired private ProductService productService;
    @Autowired private CartService cartService;
    @Autowired private CheckoutService checkoutService;
    @Autowired private PaymentService paymentService;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private PaymentReconciliationService reconciliationService;
    @Autowired private OrderRepository orderRepository;
    @Autowired private ReservationService reservationService;
    @Autowired private InventoryService inventoryService;
    @Autowired private OutboxEventRepository outboxEvents;
    @Autowired private OutboxPublisher outboxPublisher;

    @Test
    void timeoutThenRemoteLookupAuthorizedReachesConfirmedWithoutReauthorization() {
        String sku = product(5);
        UUID orderId = checkout(sku, 2);
        PROVIDER.next(RemoteOutcome.TIMEOUT_AFTER_PROCESSING);

        Payment initial = paymentService.initiatePayment(orderId);

        assertThat(initial.getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(PROVIDER.truth(initial.getId()).outcome()).isEqualTo(AuthorizationOutcome.AUTHORIZED);
        int authorizations = PROVIDER.authorizationCount();

        reconciliationService.reconcile(initial.getId());

        assertThat(paymentRepository.findById(initial.getId()).orElseThrow().getStatus())
            .isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(PROVIDER.authorizationCount()).isEqualTo(authorizations);
        publish(paymentEvent(initial.getId(), OutboxEventType.PAYMENT_AUTHORIZED));
        awaitTrue(Duration.ofSeconds(20),
            () -> orderRepository.findById(orderId).orElseThrow().getStatus() == OrderStatus.CONFIRMED);
        assertThat(reservationService.getReservationsForOrder(orderId))
            .allMatch(reservation -> reservation.getStatus() == ReservationStatus.CONFIRMED);
        assertThat(inventoryService.getBySku(sku).getAvailableQuantity()).isEqualTo(3);
    }

    @Test
    void timeoutThenRemoteLookupDeclinedCancelsAndRestoresInventoryExactlyOnce() {
        String sku = product(5);
        UUID orderId = checkout(sku, 2);
        PROVIDER.next(RemoteOutcome.TIMEOUT_AFTER_DECLINE);

        Payment initial = paymentService.initiatePayment(orderId);

        assertThat(initial.getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(PROVIDER.truth(initial.getId()).outcome()).isEqualTo(AuthorizationOutcome.DECLINED);
        int authorizations = PROVIDER.authorizationCount();
        reconciliationService.reconcile(initial.getId());

        assertThat(paymentRepository.findById(initial.getId()).orElseThrow().getStatus())
            .isEqualTo(PaymentStatus.FAILED);
        assertThat(PROVIDER.authorizationCount()).isEqualTo(authorizations);
        publish(paymentEvent(initial.getId(), OutboxEventType.PAYMENT_FAILED));
        awaitTrue(Duration.ofSeconds(20),
            () -> orderRepository.findById(orderId).orElseThrow().getStatus() == OrderStatus.CANCELLED);
        assertThat(reservationService.getReservationsForOrder(orderId))
            .allMatch(reservation -> reservation.getStatus() == ReservationStatus.RELEASED);
        assertThat(inventoryService.getBySku(sku).getAvailableQuantity()).isEqualTo(5);
    }

    private String product(int stock) {
        String sku = "REMOTE-" + UUID.randomUUID();
        productService.createProduct(sku, "Remote payment widget", new BigDecimal("10.00"), stock);
        return sku;
    }

    private UUID checkout(String sku, int quantity) {
        UUID cartId = cartService.createCart().getId();
        cartService.setItemQuantity(cartId, sku, quantity);
        return checkoutService.checkoutIdempotently(cartId, "remote-" + UUID.randomUUID())
            .order().getId();
    }

    private OutboxEvent paymentEvent(UUID paymentId, OutboxEventType type) {
        return outboxEvents.findAll().stream()
            .filter(event -> event.getAggregateId().equals(paymentId) && event.getEventType() == type)
            .findFirst().orElseThrow();
    }

    private void publish(OutboxEvent event) {
        publishUntilPublished(outboxPublisher, outboxEvents, event.getId(), Instant.now());
    }

    private enum RemoteOutcome {
        TIMEOUT_AFTER_PROCESSING,
        TIMEOUT_AFTER_DECLINE
    }

    private record RemoteTruth(AuthorizationOutcome outcome, String reference) {
    }

    private static final class RemoteProvider
        extends PaymentProviderServiceGrpc.PaymentProviderServiceImplBase {

        private final Map<UUID, RemoteTruth> truth = new ConcurrentHashMap<>();
        private final AtomicInteger authorizationCount = new AtomicInteger();
        private volatile RemoteOutcome next = RemoteOutcome.TIMEOUT_AFTER_PROCESSING;

        void next(RemoteOutcome outcome) {
            next = outcome;
        }

        int authorizationCount() {
            return authorizationCount.get();
        }

        RemoteTruth truth(UUID requestId) {
            return truth.get(requestId);
        }

        @Override
        public void authorizePayment(AuthorizePaymentRequest request,
            StreamObserver<AuthorizePaymentResponse> observer) {
            authorizationCount.incrementAndGet();
            UUID id = UUID.fromString(request.getProviderRequestId());
            RemoteOutcome selected = next;
            RemoteTruth stored = selected == RemoteOutcome.TIMEOUT_AFTER_PROCESSING
                ? new RemoteTruth(AuthorizationOutcome.AUTHORIZED, "pay_" + UUID.randomUUID())
                : new RemoteTruth(AuthorizationOutcome.DECLINED, null);
            truth.putIfAbsent(id, stored);
            try {
                Thread.sleep(700);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void lookupPayment(LookupPaymentRequest request,
            StreamObserver<LookupPaymentResponse> observer) {
            RemoteTruth stored = truth.get(UUID.fromString(request.getProviderRequestId()));
            LookupPaymentResponse response;
            if (stored == null) {
                response = LookupPaymentResponse.newBuilder().setOutcome(LookupOutcome.NOT_FOUND).build();
            } else if (stored.outcome() == AuthorizationOutcome.AUTHORIZED) {
                response = LookupPaymentResponse.newBuilder().setOutcome(LookupOutcome.LOOKUP_AUTHORIZED)
                    .setProviderReference(stored.reference()).build();
            } else {
                response = LookupPaymentResponse.newBuilder().setOutcome(LookupOutcome.LOOKUP_DECLINED).build();
            }
            observer.onNext(response);
            observer.onCompleted();
        }
    }
}
