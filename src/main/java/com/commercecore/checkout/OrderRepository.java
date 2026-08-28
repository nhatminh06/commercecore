package com.commercecore.checkout;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    /**
     * The order row is the main serialization point for competing workflow events targeting the
     * same order (e.g. two different payment event IDs both reporting AUTHORIZED). Same pattern
     * as {@code ReservationRepository.findByIdForUpdate}/{@code PaymentRepository.findByIdForUpdate}:
     * {@code SELECT ... FOR UPDATE} holds the row lock for the rest of the caller's transaction,
     * so a second concurrent workflow transaction against the same order blocks until the first
     * commits (or rolls back), then re-reads the post-transition status rather than a stale one.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Order o WHERE o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") UUID id);
}
