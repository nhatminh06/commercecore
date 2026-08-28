package com.commercecore.kafka;

/**
 * One topic, deliberately. {@code order-events}/{@code payment-events}/etc. would need a real
 * reason (different retention, different consumers, different scaling) that doesn't exist yet —
 * see {@code docs/kafka.md}.
 */
public final class KafkaTopics {

    public static final String COMMERCE_EVENTS = "commerce.events";

    private KafkaTopics() {
    }
}
