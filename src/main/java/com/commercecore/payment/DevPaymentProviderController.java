package com.commercecore.payment;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Development-only control surface for the fake payment provider — not a real production API.
 * Exists solely so {@link FakePaymentProvider}'s next outcome can be set over HTTP for manual
 * demonstration (curl), the same way tests set it directly by autowiring the bean.
 */
@RestController
@RequestMapping("/api/dev/payment-provider")
public class DevPaymentProviderController {

    private final FakePaymentProvider fakePaymentProvider;

    public DevPaymentProviderController(FakePaymentProvider fakePaymentProvider) {
        this.fakePaymentProvider = fakePaymentProvider;
    }

    public record NextOutcomeRequest(FakePaymentProvider.NextOutcome outcome) {
    }

    @PostMapping("/next-outcome")
    public void setNextOutcome(@RequestBody NextOutcomeRequest request) {
        fakePaymentProvider.nextOutcome(request.outcome());
    }
}
