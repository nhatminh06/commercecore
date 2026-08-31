package com.commercecore.paymentservice;

import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

@Component
public class OutcomeControl {

    private final AtomicReference<NextOutcome> next = new AtomicReference<>(NextOutcome.SUCCESS);

    public void setNext(NextOutcome outcome) {
        next.set(outcome);
    }

    NextOutcome takeNext() {
        return next.getAndSet(NextOutcome.SUCCESS);
    }
}
