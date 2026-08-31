package com.commercecore.payment;

public class PaymentProviderConflictException extends RuntimeException {

    public PaymentProviderConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
