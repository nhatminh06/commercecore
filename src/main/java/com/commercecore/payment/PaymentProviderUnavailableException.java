package com.commercecore.payment;

public class PaymentProviderUnavailableException extends RuntimeException {

    public PaymentProviderUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
