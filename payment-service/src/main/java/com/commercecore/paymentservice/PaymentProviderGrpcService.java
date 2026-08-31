package com.commercecore.paymentservice;

import com.commercecore.payment.proto.AuthorizationOutcome;
import com.commercecore.payment.proto.AuthorizePaymentRequest;
import com.commercecore.payment.proto.AuthorizePaymentResponse;
import com.commercecore.payment.proto.LookupOutcome;
import com.commercecore.payment.proto.LookupPaymentRequest;
import com.commercecore.payment.proto.LookupPaymentResponse;
import com.commercecore.payment.proto.PaymentProviderServiceGrpc;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class PaymentProviderGrpcService extends PaymentProviderServiceGrpc.PaymentProviderServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(PaymentProviderGrpcService.class);

    private final ProviderLedger ledger;
    private final long simulatedDelayMs;

    public PaymentProviderGrpcService(ProviderLedger ledger,
        @Value("${payment-service.simulated-response-delay-ms:2000}") long simulatedDelayMs) {
        this.ledger = ledger;
        this.simulatedDelayMs = simulatedDelayMs;
    }

    @Override
    public void authorizePayment(AuthorizePaymentRequest request,
        StreamObserver<AuthorizePaymentResponse> observer) {
        UUID requestId;
        try {
            requestId = UUID.fromString(request.getProviderRequestId());
            if (request.getAmountMinorUnits() < 0) {
                throw new IllegalArgumentException("amount must not be negative");
            }
        } catch (IllegalArgumentException e) {
            observer.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
            return;
        }

        try {
            ProviderPayment payment = ledger.authorize(requestId,
                BigDecimal.valueOf(request.getAmountMinorUnits(), 2));
            if (payment.delayResponse()) {
                delayAfterCommit();
            }
            AuthorizePaymentResponse.Builder response = AuthorizePaymentResponse.newBuilder();
            if (payment.status() == ProviderPayment.Status.AUTHORIZED) {
                response.setOutcome(AuthorizationOutcome.AUTHORIZED)
                    .setProviderReference(payment.providerReference());
            } else {
                response.setOutcome(AuthorizationOutcome.DECLINED)
                    .setDeclineReason("simulated_decline");
            }
            observer.onNext(response.build());
            observer.onCompleted();
        } catch (AmountConflictException e) {
            observer.onError(Status.FAILED_PRECONDITION.withDescription(e.getMessage()).asRuntimeException());
        } catch (RuntimeException e) {
            log.error("Provider ledger failed while authorizing request {}", requestId, e);
            observer.onError(Status.INTERNAL.withDescription("provider ledger failure").withCause(e)
                .asRuntimeException());
        }
    }

    @Override
    public void lookupPayment(LookupPaymentRequest request, StreamObserver<LookupPaymentResponse> observer) {
        UUID requestId;
        try {
            requestId = UUID.fromString(request.getProviderRequestId());
        } catch (IllegalArgumentException e) {
            observer.onError(Status.INVALID_ARGUMENT.withDescription("invalid provider request ID")
                .asRuntimeException());
            return;
        }

        LookupPaymentResponse response = ledger.lookup(requestId)
            .map(payment -> payment.status() == ProviderPayment.Status.AUTHORIZED
                ? LookupPaymentResponse.newBuilder().setOutcome(LookupOutcome.LOOKUP_AUTHORIZED)
                    .setProviderReference(payment.providerReference()).build()
                : LookupPaymentResponse.newBuilder().setOutcome(LookupOutcome.LOOKUP_DECLINED).build())
            .orElseGet(() -> LookupPaymentResponse.newBuilder().setOutcome(LookupOutcome.NOT_FOUND).build());
        observer.onNext(response);
        observer.onCompleted();
    }

    private void delayAfterCommit() {
        try {
            Thread.sleep(simulatedDelayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
