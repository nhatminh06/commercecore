package com.commercecore.outbox;

import java.time.Instant;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@Profile("portfolio & kafka")
public class PortfolioOutboxPublishingSchedule {
    private final OutboxPublisher publisher;

    public PortfolioOutboxPublishingSchedule(OutboxPublisher publisher) {
        this.publisher = publisher;
    }

    @Scheduled(fixedDelayString = "${commercecore.outbox.publish-delay-ms:1000}")
    void publishCommittedEvents() {
        publisher.publishBatch(Instant.now());
    }
}
