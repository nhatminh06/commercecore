package com.commercecore.payment;

import com.commercecore.payment.proto.AuthorizationOutcome;
import com.commercecore.payment.proto.AuthorizePaymentRequest;
import com.commercecore.payment.proto.AuthorizePaymentResponse;
import com.commercecore.payment.proto.LookupOutcome;
import com.commercecore.payment.proto.LookupPaymentRequest;
import com.commercecore.payment.proto.LookupPaymentResponse;
import com.commercecore.payment.proto.PaymentProviderServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!local-provider")
public class GrpcPaymentProviderClient implements PaymentProvider {

    private static final int MONEY_SCALE = 2;

    private final ManagedChannel channel;
    private final PaymentProviderServiceGrpc.PaymentProviderServiceBlockingStub stub;
    private final long deadlineMs;

    @Autowired
    public GrpcPaymentProviderClient(
        @Value("${commercecore.payment.grpc.host:localhost}") String host,
        @Value("${commercecore.payment.grpc.port:9090}") int port,
        @Value("${commercecore.payment.grpc.deadline-ms:500}") long deadlineMs) {
        this(ManagedChannelBuilder.forAddress(host, port).usePlaintext().build(), deadlineMs);
    }

    GrpcPaymentProviderClient(ManagedChannel channel, long deadlineMs) {
        this.channel = channel;
        this.stub = PaymentProviderServiceGrpc.newBlockingStub(channel);
        this.deadlineMs = deadlineMs;
    }

    @Override
    public AuthorizationResult authorize(UUID providerRequestId, BigDecimal amount) {
        AuthorizePaymentRequest request = AuthorizePaymentRequest.newBuilder()
            .setProviderRequestId(providerRequestId.toString())
            .setAmountMinorUnits(toMinorUnits(amount))
            .build();
        try {
            AuthorizePaymentResponse response = withDeadline().authorizePayment(request);
            if (response.getOutcome() == AuthorizationOutcome.AUTHORIZED) {
                return new AuthorizationResult.Authorized(response.getProviderReference());
            }
            if (response.getOutcome() == AuthorizationOutcome.DECLINED) {
                return new AuthorizationResult.Declined(response.getDeclineReason());
            }
            throw new PaymentProviderUnavailableException("provider returned an unspecified outcome", null);
        } catch (StatusRuntimeException e) {
            throw mapAuthorizeFailure(e);
        }
    }

    @Override
    public ProviderLookupResult lookup(UUID providerRequestId) {
        try {
            LookupPaymentResponse response = withDeadline().lookupPayment(LookupPaymentRequest.newBuilder()
                .setProviderRequestId(providerRequestId.toString()).build());
            return switch (response.getOutcome()) {
                case LOOKUP_AUTHORIZED ->
                    new ProviderLookupResult(ProviderPaymentStatus.AUTHORIZED, response.getProviderReference());
                case LOOKUP_DECLINED ->
                    new ProviderLookupResult(ProviderPaymentStatus.DECLINED, null);
                case NOT_FOUND -> new ProviderLookupResult(ProviderPaymentStatus.NOT_FOUND, null);
                default -> throw new PaymentProviderUnavailableException(
                    "provider returned an unspecified lookup outcome", null);
            };
        } catch (StatusRuntimeException e) {
            throw new PaymentProviderUnavailableException(
                "payment lookup RPC failed with " + e.getStatus().getCode(), e);
        }
    }

    static long toMinorUnits(BigDecimal amount) {
        return amount.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY)
            .movePointRight(MONEY_SCALE).longValueExact();
    }

    private PaymentProviderServiceGrpc.PaymentProviderServiceBlockingStub withDeadline() {
        return stub.withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS);
    }

    private RuntimeException mapAuthorizeFailure(StatusRuntimeException failure) {
        Status.Code code = failure.getStatus().getCode();
        if (code == Status.Code.DEADLINE_EXCEEDED || code == Status.Code.UNAVAILABLE
            || code == Status.Code.CANCELLED) {
            return new PaymentProviderTimeoutException(
                "authorization RPC ended ambiguously with " + code, failure);
        }
        if (code == Status.Code.FAILED_PRECONDITION) {
            return new PaymentProviderConflictException(failure.getStatus().getDescription(), failure);
        }
        return new PaymentProviderUnavailableException(
            "authorization RPC failed with " + code, failure);
    }

    @PreDestroy
    public void close() throws InterruptedException {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
}
