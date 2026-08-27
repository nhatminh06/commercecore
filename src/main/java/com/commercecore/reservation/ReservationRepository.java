package com.commercecore.reservation;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReservationRepository extends JpaRepository<InventoryReservation, UUID> {

    /**
     * A state transition (confirm/release/expire) is check-then-act across more than one
     * statement — read the current status, decide, write the new status, maybe restore
     * inventory. {@code SELECT ... FOR UPDATE} holds the row lock for the rest of the
     * transaction, so a second concurrent transition on the same reservation blocks until the
     * first commits, then re-reads the *post-transition* status rather than the stale one it
     * would have seen without the lock. That's what makes double-release (and confirm-vs-release)
     * safe: the loser always sees the winner's already-terminal status.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM InventoryReservation r WHERE r.id = :id")
    Optional<InventoryReservation> findByIdForUpdate(@Param("id") UUID id);

    List<InventoryReservation> findByStatusAndExpiresAtLessThanEqual(ReservationStatus status, Instant now);

    List<InventoryReservation> findByOrderId(UUID orderId);
}
