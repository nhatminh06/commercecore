package com.commercecore.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercecore.AbstractIntegrationTest;
import com.commercecore.cart.CartService;
import com.commercecore.catalog.ProductService;
import com.commercecore.checkout.CheckoutService;
import com.commercecore.checkout.OrderRepository;
import com.commercecore.checkout.OrderStatus;
import com.commercecore.inventory.InventoryService;
import com.commercecore.reservation.ReservationService;
import com.commercecore.reservation.ReservationStatus;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@ActiveProfiles(profiles = "remote-provider", inheritProfiles = false)
class RemotePaymentUnavailableIntegrationTest extends AbstractIntegrationTest {

    private static final int UNUSED_PORT = unusedPort();

    @DynamicPropertySource
    static void unavailableProvider(DynamicPropertyRegistry registry) {
        registry.add("commercecore.payment.grpc.host", () -> "localhost");
        registry.add("commercecore.payment.grpc.port", () -> UNUSED_PORT);
        registry.add("commercecore.payment.grpc.deadline-ms", () -> 150);
    }

    @Autowired private ProductService productService;
    @Autowired private CartService cartService;
    @Autowired private CheckoutService checkoutService;
    @Autowired private PaymentService paymentService;
    @Autowired private OrderRepository orderRepository;
    @Autowired private ReservationService reservationService;
    @Autowired private InventoryService inventoryService;

    @Test
    void unavailableServiceLeavesOneUnknownPaymentAndCommerceStateConsistent() {
        String sku = "DOWN-" + UUID.randomUUID();
        productService.createProduct(sku, "Unavailable provider widget", new BigDecimal("10.00"), 3);
        UUID cartId = cartService.createCart().getId();
        cartService.setItemQuantity(cartId, sku, 2);
        UUID orderId = checkoutService.checkoutIdempotently(cartId, "down-" + UUID.randomUUID())
            .order().getId();

        Payment first = paymentService.initiatePayment(orderId);
        Payment repeat = paymentService.initiatePayment(orderId);

        assertThat(first.getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(repeat.getId()).isEqualTo(first.getId());
        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(reservationService.getReservationsForOrder(orderId)).singleElement()
            .satisfies(reservation -> assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.ACTIVE));
        assertThat(inventoryService.getBySku(sku).getAvailableQuantity()).isEqualTo(1);
    }

    private static int unusedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
