package com.commercecore.kafka;

import java.util.Map;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

/**
 * An explicit {@code ProducerFactory}/{@code KafkaTemplate} pair, rather than relying on Spring
 * Boot's auto-configured generic {@code KafkaTemplate<Object, Object>} bean — this fixes the
 * value type to {@link EventEnvelope} unambiguously, reading {@code spring.kafka.producer.*}
 * (bootstrap servers, {@code acks}, etc.) from the same {@link KafkaProperties} Spring Boot
 * itself binds, so configuration still lives in one place ({@code application.yml}).
 */
@Configuration
@Profile("kafka")
public class KafkaProducerConfig {

    @Bean
    public ProducerFactory<String, EventEnvelope> eventProducerFactory(KafkaProperties kafkaProperties) {
        Map<String, Object> props = kafkaProperties.buildProducerProperties(null);
        return new DefaultKafkaProducerFactory<>(props, new StringSerializer(), new JsonSerializer<>());
    }

    @Bean
    public KafkaTemplate<String, EventEnvelope> eventKafkaTemplate(
        ProducerFactory<String, EventEnvelope> eventProducerFactory) {
        return new KafkaTemplate<>(eventProducerFactory);
    }
}
