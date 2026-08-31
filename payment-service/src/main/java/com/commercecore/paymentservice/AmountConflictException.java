package com.commercecore.paymentservice;

public class AmountConflictException extends RuntimeException {

    public AmountConflictException(String message) {
        super(message);
    }
}
