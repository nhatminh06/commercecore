package com.commercecore.outbox;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercecore.AbstractIntegrationTest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * An unsupported event/aggregate type is impossible to write, not merely unlikely — enforced by
 * PostgreSQL's own CHECK constraints, not just the Java enum.
 */
class OutboxConstraintTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void unsupportedEventTypeIsRejectedByTheDatabase() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
            INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload, created_at, attempt_count)
            VALUES (?, 'ORDER', ?, 'SOMETHING_UNSUPPORTED', '{}'::jsonb, ?, 0)
            """, UUID.randomUUID(), UUID.randomUUID(), java.sql.Timestamp.from(Instant.now())))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void unsupportedAggregateTypeIsRejectedByTheDatabase() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
            INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload, created_at, attempt_count)
            VALUES (?, 'SOMETHING_UNSUPPORTED', ?, 'ORDER_CREATED', '{}'::jsonb, ?, 0)
            """, UUID.randomUUID(), UUID.randomUUID(), java.sql.Timestamp.from(Instant.now())))
            .isInstanceOf(DataIntegrityViolationException.class);
    }
}
