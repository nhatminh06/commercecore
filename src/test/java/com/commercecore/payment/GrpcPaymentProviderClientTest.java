package com.commercecore.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercecore.payment.proto.AuthorizationOutcome;
import com.commercecore.payment.proto.AuthorizePaymentRequest;
import com.commercecore.payment.proto.AuthorizePaymentResponse;
import com.commercecore.payment.proto.LookupOutcome;
import com.commercecore.payment.proto.LookupPaymentRequest;
import com.commercecore.payment.proto.LookupPaymentResponse;
import com.commercecore.payment.proto.PaymentProviderServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GrpcPaymentProviderClientTest {

    private Server server;
    private GrpcPaymentProviderClient client;

    @AfterEach
    void close() throws Exception {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.shutdownNow().awaitTermination();
        }
    }

    @Test
    void moneyConversionIsExactAndRejectsHiddenFractionalCents() {
        assertThat(GrpcPaymentProviderClient.toMinorUnits(new BigDecimal("30.50"))).isEqualTo(3050);
        assertThatThrownBy(() -> GrpcPaymentProviderClient.toMinorUnits(new BigDecimal("30.501")))
            .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void mapsActualTcpResponseToDomainResult() throws Exception {
        start(new PaymentProviderServiceGrpc.PaymentProviderServiceImplBase() {
            @Override
            public void authorizePayment(AuthorizePaymentRequest request,
                StreamObserver<AuthorizePaymentResponse> observer) {
                assertThat(request.getAmountMinorUnits()).isEqualTo(3050);
                observer.onNext(AuthorizePaymentResponse.newBuilder()
                    .setOutcome(AuthorizationOutcome.AUTHORIZED)
                    .setProviderReference("pay_stable").build());
                observer.onCompleted();
            }
        }, 500);

        assertThat(client.authorize(UUID.randomUUID(), new BigDecimal("30.50")))
            .isEqualTo(new AuthorizationResult.Authorized("pay_stable"));
    }

    @Test
    void actualDeadlineExceededIsAmbiguousForAuthorize() throws Exception {
        start(new PaymentProviderServiceGrpc.PaymentProviderServiceImplBase() {
            @Override
            public void authorizePayment(AuthorizePaymentRequest request,
                StreamObserver<AuthorizePaymentResponse> observer) {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, 40);

        assertThatThrownBy(() -> client.authorize(UUID.randomUUID(), new BigDecimal("10.00")))
            .isInstanceOf(PaymentProviderTimeoutException.class)
            .hasMessageContaining("DEADLINE_EXCEEDED");
    }

    @Test
    void unavailableAuthorizeIsAmbiguousButUnavailableLookupIsNotNotFound() throws Exception {
        int unusedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            unusedPort = socket.getLocalPort();
        }
        ManagedChannel channel = ManagedChannelBuilder.forAddress("localhost", unusedPort)
            .usePlaintext().build();
        client = new GrpcPaymentProviderClient(channel, 100);

        assertThatThrownBy(() -> client.authorize(UUID.randomUUID(), new BigDecimal("10.00")))
            .isInstanceOf(PaymentProviderTimeoutException.class)
            .hasMessageContaining("UNAVAILABLE");
        assertThatThrownBy(() -> client.lookup(UUID.randomUUID()))
            .isInstanceOf(PaymentProviderUnavailableException.class)
            .hasMessageContaining("UNAVAILABLE");
    }

    @Test
    void lookupKeepsProviderNotFoundDistinctFromTransportFailure() throws Exception {
        start(new PaymentProviderServiceGrpc.PaymentProviderServiceImplBase() {
            @Override
            public void lookupPayment(LookupPaymentRequest request,
                StreamObserver<LookupPaymentResponse> observer) {
                observer.onNext(LookupPaymentResponse.newBuilder().setOutcome(LookupOutcome.NOT_FOUND).build());
                observer.onCompleted();
            }
        }, 500);

        assertThat(client.lookup(UUID.randomUUID()))
            .isEqualTo(new ProviderLookupResult(ProviderPaymentStatus.NOT_FOUND, null));
    }

    private void start(PaymentProviderServiceGrpc.PaymentProviderServiceImplBase service,
        long deadlineMs) throws Exception {
        server = NettyServerBuilder.forPort(0).addService(service).build().start();
        ManagedChannel channel = ManagedChannelBuilder.forAddress("localhost", server.getPort())
            .usePlaintext().build();
        client = new GrpcPaymentProviderClient(channel, deadlineMs);
    }
}
