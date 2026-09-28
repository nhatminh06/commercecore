package com.commercecore.checkout;

import com.commercecore.payment.Payment;
import com.commercecore.payment.PaymentReconciliationCase;
import com.commercecore.payment.PaymentStatus;
import com.commercecore.payment.ProviderPaymentStatus;
import com.commercecore.payment.ReconciliationReason;
import com.commercecore.payment.ReconciliationStatus;
import com.commercecore.reservation.InventoryReservation;
import com.commercecore.reservation.ReservationStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderInspectionResponse(
    OrderResponse order,
    Instant orderCreatedAt,
    PaymentInspection payment,
    List<ReservationInspection> reservations,
    ReconciliationInspection reconciliation
) {
    public record PaymentInspection(UUID paymentId, UUID orderId, BigDecimal amount, PaymentStatus status,
                                    String providerReference, Instant createdAt, Instant updatedAt) {
        static PaymentInspection from(Payment payment) {
            return new PaymentInspection(payment.getId(), payment.getOrderId(), payment.getAmount(),
                payment.getStatus(), payment.getProviderReference(), payment.getCreatedAt(), payment.getUpdatedAt());
        }
    }

    public record ReservationInspection(UUID id, String sku, int quantity, ReservationStatus status,
                                        Instant expiresAt) {
        static ReservationInspection from(InventoryReservation reservation) {
            return new ReservationInspection(reservation.getId(), reservation.getSku(), reservation.getQuantity(),
                reservation.getStatus(), reservation.getExpiresAt());
        }
    }

    public record ReconciliationInspection(UUID id, UUID paymentId, ReconciliationStatus status,
                                           ProviderPaymentStatus lastProviderStatus, ReconciliationReason reason,
                                           int attemptCount, Instant lastCheckedAt) {
        static ReconciliationInspection from(PaymentReconciliationCase reconciliationCase) {
            return new ReconciliationInspection(reconciliationCase.getId(), reconciliationCase.getPaymentId(),
                reconciliationCase.getStatus(), reconciliationCase.getLastProviderStatus(),
                reconciliationCase.getReason(), reconciliationCase.getAttemptCount(),
                reconciliationCase.getLastCheckedAt());
        }
    }
}
