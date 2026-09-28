package com.commercecore.checkout;

import com.commercecore.payment.Payment;
import com.commercecore.payment.PaymentReconciliationCaseRepository;
import com.commercecore.payment.PaymentRepository;
import com.commercecore.reservation.InventoryReservation;
import com.commercecore.reservation.ReservationService;
import java.util.Comparator;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Read-only composition for the engineering Order Inspector. It reports stored state exactly as
 * it exists; it does not infer transitions or query/mutate the remote payment provider.
 */
@Service
public class OrderInspectionService {

    private final CheckoutService checkoutService;
    private final PaymentRepository paymentRepository;
    private final ReservationService reservationService;
    private final PaymentReconciliationCaseRepository reconciliationCaseRepository;

    public OrderInspectionService(CheckoutService checkoutService, PaymentRepository paymentRepository,
        ReservationService reservationService, PaymentReconciliationCaseRepository reconciliationCaseRepository) {
        this.checkoutService = checkoutService;
        this.paymentRepository = paymentRepository;
        this.reservationService = reservationService;
        this.reconciliationCaseRepository = reconciliationCaseRepository;
    }

    public OrderInspectionResponse inspect(UUID orderId) {
        Order order = checkoutService.getOrder(orderId);
        OrderResponse orderResponse = OrderResponse.of(order, checkoutService.getItems(orderId));
        Payment payment = paymentRepository.findByOrderId(orderId).orElse(null);

        var reservations = reservationService.getReservationsForOrder(orderId).stream()
            .sorted(Comparator.comparing(InventoryReservation::getSku).thenComparing(InventoryReservation::getId))
            .map(OrderInspectionResponse.ReservationInspection::from)
            .toList();

        var reconciliation = payment == null ? null : reconciliationCaseRepository.findByPaymentId(payment.getId())
            .map(OrderInspectionResponse.ReconciliationInspection::from)
            .orElse(null);

        return new OrderInspectionResponse(orderResponse, order.getCreatedAt(),
            payment == null ? null : OrderInspectionResponse.PaymentInspection.from(payment),
            reservations, reconciliation);
    }
}
