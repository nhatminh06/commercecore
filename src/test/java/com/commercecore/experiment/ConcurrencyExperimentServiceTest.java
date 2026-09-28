package com.commercecore.experiment;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercecore.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles({"local-provider", "dev"})
class ConcurrencyExperimentServiceTest extends AbstractIntegrationTest {
    @Autowired private ConcurrencyExperimentService experiments;

    @Test
    void inventoryContentionUsesRealReservationsAndReadsFinalInventory() {
        InventoryExperimentResult result = experiments.inventory(new InventoryExperimentRequest(10, 100, 1));
        assertThat(result.complete()).isTrue();
        assertThat(result.successful()).isEqualTo(10);
        assertThat(result.rejected()).isEqualTo(90);
        assertThat(result.finalInventory()).isZero();
        assertThat(result.invariantPreserved()).isTrue();
    }

    @Test
    void checkoutRacePersistsOneMappingAndOneLogicalOrder() {
        CheckoutExperimentResult result = experiments.checkout(new WorkerExperimentRequest(20));
        assertThat(result.complete()).isTrue();
        assertThat(result.successfulResponses()).isEqualTo(20);
        assertThat(result.distinctResponseOrderIds()).hasSize(1);
        assertThat(result.persistedMappingsForCart()).isEqualTo(1);
        assertThat(result.invariantPreserved()).isTrue();
    }

    @Test
    void duplicateReceiptAttemptsPersistOneReceipt() {
        DuplicateEventExperimentResult result = experiments.duplicateEvent(new DuplicateEventExperimentRequest(20));
        assertThat(result.complete()).isTrue();
        assertThat(result.newlyPersistedReceipts()).isEqualTo(1);
        assertThat(result.authoritativeReceiptCount()).isEqualTo(1);
        assertThat(result.invariantPreserved()).isTrue();
    }
}
