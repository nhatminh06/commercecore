package com.commercecore.outbox;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class PortfolioOutboxPublishingScheduleTest {
    @Test
    void publishesCommittedEventsWithoutADevelopmentEndpoint() {
        OutboxPublisher publisher = mock(OutboxPublisher.class);

        new PortfolioOutboxPublishingSchedule(publisher).publishCommittedEvents();

        verify(publisher).publishBatch(any(Instant.class));
    }
}
