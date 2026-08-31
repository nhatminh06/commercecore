package com.commercecore.paymentservice;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Profile("dev")
@RestController
@RequestMapping("/api/dev/provider")
public class DevProviderController {

    private final OutcomeControl outcomes;

    public DevProviderController(OutcomeControl outcomes) {
        this.outcomes = outcomes;
    }

    public record NextOutcomeRequest(NextOutcome outcome) {
    }

    @PostMapping("/next-outcome")
    public void setNextOutcome(@RequestBody NextOutcomeRequest request) {
        outcomes.setNext(request.outcome());
    }
}
