package com.commercecore.paymentservice;

import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class GrpcServerLifecycle {

    private final Server server;

    public GrpcServerLifecycle(PaymentProviderGrpcService service,
        @Value("${payment-service.grpc.port:9090}") int port) throws IOException {
        this.server = NettyServerBuilder.forPort(port).addService(service).build().start();
    }

    @PreDestroy
    public void stop() throws InterruptedException {
        server.shutdownNow().awaitTermination();
    }
}
