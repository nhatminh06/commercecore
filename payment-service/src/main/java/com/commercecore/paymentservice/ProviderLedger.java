package com.commercecore.paymentservice;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class ProviderLedger {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final OutcomeControl outcomes;

    public ProviderLedger(JdbcTemplate jdbc, TransactionTemplate transactions, OutcomeControl outcomes) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.outcomes = outcomes;
    }

    public ProviderPayment authorize(UUID requestId, BigDecimal amount) {
        return transactions.execute(status -> {
            // The transaction-scoped PostgreSQL advisory lock serializes this request identity
            // across threads and service instances. The primary key remains the final invariant.
            jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                rs -> { }, requestId.toString());

            Optional<ProviderPayment> existing = lookup(requestId);
            if (existing.isPresent()) {
                if (existing.get().amount().compareTo(amount) != 0) {
                    throw new AmountConflictException(
                        "provider request " + requestId + " was already used with a different amount");
                }
                return existing.get();
            }

            NextOutcome outcome = outcomes.takeNext();
            boolean authorized = outcome == NextOutcome.SUCCESS
                || outcome == NextOutcome.TIMEOUT_AFTER_PROCESSING;
            boolean delayed = outcome == NextOutcome.TIMEOUT_AFTER_PROCESSING
                || outcome == NextOutcome.TIMEOUT_AFTER_DECLINE;
            String reference = authorized ? "pay_" + UUID.randomUUID() : null;
            ProviderPayment.Status providerStatus = authorized
                ? ProviderPayment.Status.AUTHORIZED : ProviderPayment.Status.DECLINED;

            jdbc.update("""
                INSERT INTO provider_payments
                    (provider_request_id, amount, status, provider_reference, created_at)
                VALUES (?, ?, ?, ?, ?)
                """, requestId, amount, providerStatus.name(), reference, Timestamp.from(Instant.now()));
            return new ProviderPayment(requestId, amount, providerStatus, reference, delayed);
        });
    }

    public Optional<ProviderPayment> lookup(UUID requestId) {
        return jdbc.query("""
                SELECT provider_request_id, amount, status, provider_reference
                FROM provider_payments WHERE provider_request_id = ?
                """, this::map, requestId).stream().findFirst();
    }

    public long count() {
        return jdbc.queryForObject("SELECT count(*) FROM provider_payments", Long.class);
    }

    private ProviderPayment map(ResultSet rs, int row) throws SQLException {
        return new ProviderPayment(
            rs.getObject("provider_request_id", UUID.class),
            rs.getBigDecimal("amount"),
            ProviderPayment.Status.valueOf(rs.getString("status")),
            rs.getString("provider_reference"),
            false);
    }
}
