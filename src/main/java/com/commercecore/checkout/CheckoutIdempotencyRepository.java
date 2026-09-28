package com.commercecore.checkout;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CheckoutIdempotencyRepository extends JpaRepository<CheckoutIdempotency, String> {

    long countByCartId(java.util.UUID cartId);

    /**
     * Serializes every "check this key, and if absent run checkout and record it" sequence
     * against the same idempotency key, across every connection and every application instance —
     * a plain {@code SELECT then INSERT} cannot do this, because two concurrent transactions can
     * both SELECT and see nothing before either has INSERTed; by the time a unique-key violation
     * would catch the duplicate, both transactions could already have committed a full duplicate
     * checkout (order, order items, reservations, inventory decrement) in their own transaction.
     *
     * <p>{@code pg_advisory_xact_lock} blocks the calling transaction until it can acquire the
     * given lock ID, and releases it automatically at COMMIT or ROLLBACK — never held past this
     * transaction's lifetime, and never leaked by a crash. {@code hashtextextended(key, 0)} maps
     * the arbitrary-length key string to a 64-bit lock ID deterministically; two different keys
     * hashing to the same ID would only cost some unnecessary lock contention between unrelated
     * checkouts, never an incorrect result, because the actual key comparison still happens via
     * the table's primary key once the lock is held.
     *
     * <p>With every concurrent request for the same key forced through this lock one at a time,
     * no persisted IN_PROGRESS marker is needed: the first to acquire the lock finds no row,
     * performs the whole checkout, inserts the row, and commits (releasing the lock); every
     * other waiter then acquires the lock in turn, finds the row already exists, and returns it
     * without re-running checkout.
     */
    @Query(value = "SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))", nativeQuery = true)
    void acquireLock(@Param("key") String key);
}
