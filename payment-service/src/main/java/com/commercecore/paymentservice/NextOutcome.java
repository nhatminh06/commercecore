package com.commercecore.paymentservice;

public enum NextOutcome {
    SUCCESS,
    DECLINED,
    TIMEOUT_AFTER_PROCESSING,
    TIMEOUT_AFTER_DECLINE
}
