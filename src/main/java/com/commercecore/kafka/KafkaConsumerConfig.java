package com.commercecore.kafka;

import java.util.Map;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Explicit, narrow Kafka wiring — deliberately not Spring Boot's generic auto-configured
 * consumer, for the same reason as {@link KafkaProducerConfig}: fixing the value type to
 * {@link EventEnvelope} unambiguously. Two decisions worth reading closely:
 *
 * <ul>
 *   <li>{@code AckMode.MANUAL_IMMEDIATE} — the listener container never auto-commits an offset;
 *       {@link KafkaEventReceiptConsumer} must call {@code Acknowledgment.acknowledge()} itself,
 *       and only after the receipt row has committed. See {@code docs/kafka.md}.
 *   <li>A short, bounded {@link FixedBackOff} on the error handler — a listener exception
 *       redelivers the same record quickly and deterministically (200ms, up to 20 attempts)
 *       rather than Spring Kafka's longer default, so tests proving "failure then later success"
 *       converge fast without arbitrary sleeps.
 * </ul>
 *
 * <p>The {@link NewTopic} bean creates {@code commerce.events} deterministically for local/test
 * environments with a small partition count and replication factor 1 (a single local broker
 * cannot offer more — see {@code docs/kafka.md}).
 */
@Configuration
@Profile("kafka")
public class KafkaConsumerConfig {

    @Bean
    public NewTopic commerceEventsTopic() {
        return TopicBuilder.name(KafkaTopics.COMMERCE_EVENTS)
            .partitions(3)
            .replicas(1)
            .build();
    }

    @Bean
    public ConsumerFactory<String, EventEnvelope> eventConsumerFactory(KafkaProperties kafkaProperties) {
        Map<String, Object> props = kafkaProperties.buildConsumerProperties(null);
        JsonDeserializer<EventEnvelope> valueDeserializer = new JsonDeserializer<>(EventEnvelope.class, false);
        valueDeserializer.addTrustedPackages("com.commercecore.kafka");
        return new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), valueDeserializer);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, EventEnvelope> kafkaListenerContainerFactory(
        ConsumerFactory<String, EventEnvelope> eventConsumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, EventEnvelope> factory =
            new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(eventConsumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(200L, 20L)));
        return factory;
    }
}
