package com.commercecore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.commercecore.outbox.DevOutboxController;
import com.commercecore.outbox.DevRecordingSinkController;
import com.commercecore.outbox.OutboxPublisher;
import com.commercecore.outbox.RecordingEventSink;
import com.commercecore.payment.DevPaymentProviderController;
import com.commercecore.payment.DevReconciliationController;
import com.commercecore.payment.FakePaymentProvider;
import com.commercecore.payment.PaymentReconciliationService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

class DevelopmentEndpointProfileTest {

    private final WebApplicationContextRunner context = new WebApplicationContextRunner()
        .withUserConfiguration(
            DevOutboxController.class,
            DevRecordingSinkController.class,
            DevReconciliationController.class,
            DevPaymentProviderController.class)
        .withBean(OutboxPublisher.class, () -> mock(OutboxPublisher.class))
        .withBean(RecordingEventSink.class, () -> mock(RecordingEventSink.class))
        .withBean(PaymentReconciliationService.class, () -> mock(PaymentReconciliationService.class))
        .withBean(FakePaymentProvider.class, FakePaymentProvider::new);

    @Test
    void developmentControlsAreAbsentWithoutDevelopmentProfiles() {
        context.run(application -> {
            assertThat(application).doesNotHaveBean(DevOutboxController.class);
            assertThat(application).doesNotHaveBean(DevRecordingSinkController.class);
            assertThat(application).doesNotHaveBean(DevReconciliationController.class);
            assertThat(application).doesNotHaveBean(DevPaymentProviderController.class);
        });
    }

    @Test
    void developmentControlsArePresentUnderExplicitDevelopmentProfiles() {
        context.withPropertyValues("spring.profiles.active=dev,local-provider").run(application -> {
            assertThat(application).hasSingleBean(DevOutboxController.class);
            assertThat(application).hasSingleBean(DevRecordingSinkController.class);
            assertThat(application).hasSingleBean(DevReconciliationController.class);
            assertThat(application).hasSingleBean(DevPaymentProviderController.class);
        });
    }

    @Test
    void recordingSinkControlIsAbsentWhenKafkaProfileIsActive() {
        context.withPropertyValues("spring.profiles.active=dev,local-provider,kafka").run(application -> {
            assertThat(application).hasSingleBean(DevOutboxController.class);
            assertThat(application).doesNotHaveBean(DevRecordingSinkController.class);
            assertThat(application).hasSingleBean(DevReconciliationController.class);
        });
    }
}
