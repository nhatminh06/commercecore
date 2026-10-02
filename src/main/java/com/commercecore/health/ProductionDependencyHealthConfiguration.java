package com.commercecore.health;

import java.net.InetSocketAddress;
import java.net.Socket;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("portfolio")
public class ProductionDependencyHealthConfiguration {

    @Bean
    HealthIndicator paymentServiceHealthIndicator(
        @Value("${commercecore.payment.grpc.host}") String host,
        @Value("${commercecore.payment.grpc.port}") int port
    ) {
        return tcpDependency(host, port);
    }

    @Bean
    HealthIndicator kafkaDependencyHealthIndicator(
        @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers
    ) {
        String firstBroker = bootstrapServers.split(",", 2)[0].trim();
        int separator = firstBroker.lastIndexOf(':');
        if (separator < 1 || separator == firstBroker.length() - 1) {
            return () -> Health.down().withDetail("reason", "invalid bootstrap address").build();
        }
        return tcpDependency(firstBroker.substring(0, separator), Integer.parseInt(firstBroker.substring(separator + 1)));
    }

    private HealthIndicator tcpDependency(String host, int port) {
        return () -> {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), 750);
                return Health.up().build();
            } catch (Exception exception) {
                return Health.down().build();
            }
        };
    }
}
