package com.commercecore.paymentservice;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

class DevProviderControllerProfileTest {

    private final WebApplicationContextRunner context = new WebApplicationContextRunner()
        .withUserConfiguration(DevProviderController.class)
        .withBean(OutcomeControl.class, OutcomeControl::new);

    @Test
    void controlIsAbsentWithoutDevelopmentProfile() {
        context.run(application -> assertThat(application).doesNotHaveBean(DevProviderController.class));
    }

    @Test
    void controlIsPresentWithDevelopmentProfile() {
        context.withPropertyValues("spring.profiles.active=dev")
            .run(application -> assertThat(application).hasSingleBean(DevProviderController.class));
    }
}
