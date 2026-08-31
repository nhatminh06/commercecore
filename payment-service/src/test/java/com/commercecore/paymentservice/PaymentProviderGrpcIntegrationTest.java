package com.commercecore.paymentservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercecore.payment.proto.AuthorizationOutcome;
import com.commercecore.payment.proto.AuthorizePaymentRequest;
import com.commercecore.payment.proto.AuthorizePaymentResponse;
import com.commercecore.payment.proto.LookupOutcome;
import com.commercecore.payment.proto.LookupPaymentRequest;
import com.commercecore.payment.proto.PaymentProviderServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest(properties = {
    "payment-service.grpc.port=0",
    "payment-service.simulated-response-delay-ms=600"
})
class PaymentProviderGrpcIntegrationTest {

    private static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private PaymentProviderGrpcService service;

    @Autowired
    private OutcomeControl outcomes;

    @Autowired
    private ProviderLedger ledger;

    private Server server;
    private ManagedChannel channel;
    private PaymentProviderServiceGrpc.PaymentProviderServiceBlockingStub stub;

    @BeforeEach
    void startTcpServer() throws IOException {
        server = NettyServerBuilder.forPort(0).addService(service).build().start();
        channel = ManagedChannelBuilder.forAddress("localhost", server.getPort()).usePlaintext().build();
        stub = PaymentProviderServiceGrpc.newBlockingStub(channel);
        outcomes.setNext(NextOutcome.SUCCESS);
    }

    @AfterEach
    void stopTcpServer() throws InterruptedException {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        server.shutdownNow().awaitTermination();
    }

    @Test
    void successPreservesThirtyDollarsAndFiftyCentsExactly() {
        UUID id = UUID.randomUUID();

        AuthorizePaymentResponse response = authorize(id, 3050);

        assertThat(response.getOutcome()).isEqualTo(AuthorizationOutcome.AUTHORIZED);
        ProviderPayment stored = ledger.lookup(id).orElseThrow();
        assertThat(stored.amount()).isEqualByComparingTo(new BigDecimal("30.50"));
        assertThat(stored.providerReference()).isEqualTo(response.getProviderReference());
    }

    @Test
    void declineIsANormalSuccessfulRpcResponse() {
        outcomes.setNext(NextOutcome.DECLINED);
        UUID id = UUID.randomUUID();

        AuthorizePaymentResponse response = authorize(id, 1000);

        assertThat(response.getOutcome()).isEqualTo(AuthorizationOutcome.DECLINED);
        assertThat(ledger.lookup(id).orElseThrow().status()).isEqualTo(ProviderPayment.Status.DECLINED);
    }

    @Test
    void timeoutAfterProcessingCommitsAuthorizedTruthBeforeRealTcpDeadline() throws Exception {
        outcomes.setNext(NextOutcome.TIMEOUT_AFTER_PROCESSING);
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> stub.withDeadlineAfter(250, TimeUnit.MILLISECONDS)
            .authorizePayment(request(id, 3050)))
            .isInstanceOfSatisfying(StatusRuntimeException.class,
                e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.DEADLINE_EXCEEDED));

        awaitPersisted(id);
        ProviderPayment stored = ledger.lookup(id).orElseThrow();
        assertThat(stored.status()).isEqualTo(ProviderPayment.Status.AUTHORIZED);
        assertThat(stub.lookupPayment(lookup(id)).getOutcome()).isEqualTo(LookupOutcome.LOOKUP_AUTHORIZED);
        assertThat(stub.lookupPayment(lookup(id)).getProviderReference())
            .isEqualTo(stored.providerReference());
    }

    @Test
    void timeoutAfterDeclineCommitsDeclinedTruthBeforeRealTcpDeadline() throws Exception {
        outcomes.setNext(NextOutcome.TIMEOUT_AFTER_DECLINE);
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> stub.withDeadlineAfter(250, TimeUnit.MILLISECONDS)
            .authorizePayment(request(id, 3050)))
            .isInstanceOfSatisfying(StatusRuntimeException.class,
                e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.DEADLINE_EXCEEDED));

        awaitPersisted(id);
        assertThat(ledger.lookup(id).orElseThrow().status()).isEqualTo(ProviderPayment.Status.DECLINED);
        assertThat(stub.lookupPayment(lookup(id)).getOutcome()).isEqualTo(LookupOutcome.LOOKUP_DECLINED);
    }

    @Test
    void retryAfterGrpcServerRestartReturnsSamePersistentReferenceAndOneRow() throws Exception {
        UUID id = UUID.randomUUID();
        long before = ledger.count();
        String reference = authorize(id, 1000).getProviderReference();

        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        server.shutdownNow().awaitTermination();
        server = NettyServerBuilder.forPort(0).addService(service).build().start();
        channel = ManagedChannelBuilder.forAddress("localhost", server.getPort()).usePlaintext().build();
        stub = PaymentProviderServiceGrpc.newBlockingStub(channel);

        assertThat(stub.lookupPayment(lookup(id)).getProviderReference()).isEqualTo(reference);
        assertThat(authorize(id, 1000).getProviderReference()).isEqualTo(reference);
        assertThat(ledger.count()).isEqualTo(before + 1);
    }

    @Test
    void twentyConcurrentSameIdRpcsCreateOneLogicalAuthorization() throws Exception {
        UUID id = UUID.randomUUID();
        long before = ledger.count();
        CyclicBarrier barrier = new CyclicBarrier(20);
        ExecutorService pool = Executors.newFixedThreadPool(20);
        try {
            List<Future<String>> calls = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                calls.add(pool.submit(() -> {
                    barrier.await();
                    return authorize(id, 1000).getProviderReference();
                }));
            }
            List<String> references = new ArrayList<>();
            for (Future<String> call : calls) {
                references.add(call.get(10, TimeUnit.SECONDS));
            }
            assertThat(references).doesNotContainNull().containsOnly(references.getFirst());
            assertThat(ledger.count()).isEqualTo(before + 1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentDifferentAmountsChooseOneAndRejectTheOther() throws Exception {
        UUID id = UUID.randomUUID();
        long before = ledger.count();
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Status.Code> first = pool.submit(() -> callAndReturnStatus(barrier, id, 1000));
            Future<Status.Code> second = pool.submit(() -> callAndReturnStatus(barrier, id, 2000));
            assertThat(List.of(first.get(), second.get()))
                .containsExactlyInAnyOrder(Status.Code.OK, Status.Code.FAILED_PRECONDITION);
            assertThat(ledger.count()).isEqualTo(before + 1);
        } finally {
            pool.shutdownNow();
        }
    }

    private Status.Code callAndReturnStatus(CyclicBarrier barrier, UUID id, long amount) throws Exception {
        barrier.await();
        try {
            authorize(id, amount);
            return Status.Code.OK;
        } catch (StatusRuntimeException e) {
            return e.getStatus().getCode();
        }
    }

    private AuthorizePaymentResponse authorize(UUID id, long minorUnits) {
        return stub.authorizePayment(request(id, minorUnits));
    }

    private AuthorizePaymentRequest request(UUID id, long minorUnits) {
        return AuthorizePaymentRequest.newBuilder().setProviderRequestId(id.toString())
            .setAmountMinorUnits(minorUnits).build();
    }

    private LookupPaymentRequest lookup(UUID id) {
        return LookupPaymentRequest.newBuilder().setProviderRequestId(id.toString()).build();
    }

    private void awaitPersisted(UUID id) throws InterruptedException {
        for (int attempt = 0; attempt < 50 && ledger.lookup(id).isEmpty(); attempt++) {
            Thread.sleep(10);
        }
        assertThat(ledger.lookup(id)).isPresent();
    }
}
