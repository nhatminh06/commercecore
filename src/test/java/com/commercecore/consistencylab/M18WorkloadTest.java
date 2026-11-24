package com.commercecore.consistencylab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercecore.AbstractIntegrationTest;
import com.commercecore.cart.CartService;
import com.commercecore.catalog.ProductService;
import com.commercecore.checkout.CheckoutResult;
import com.commercecore.checkout.CheckoutService;
import com.commercecore.reservation.ReservationService;
import com.commercecore.shared.BusinessRuleViolation;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

class M18WorkloadTest extends AbstractIntegrationTest {
    @Autowired ProductService products;
    @Autowired CartService carts;
    @Autowired CheckoutService checkout;
    @Autowired ReservationService reservations;

    @Test
    void sameSeedReproducesInputsAndStableLogicalIds() {
        String id = UUID.randomUUID().toString();
        var first = M18Workload.plan("inventory_reserve", id, 42, 1, 4, 2, 10_000);
        assertThat(M18Workload.plan("inventory_reserve", id, 42, 1, 4, 2, 10_000)).isEqualTo(first);
        assertThat(first.operations()).hasSize(8).extracting(M18Workload.Planned::operationId).doesNotHaveDuplicates();
        assertThatThrownBy(() -> M18Workload.plan("inventory_reserve", id, 42, 1, 9, 4, 10_000))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void competingReservationsRecordSuccessAndBusinessRejection() throws Exception {
        String runId = UUID.randomUUID().toString();
        String sku = "M18-INV-" + runId;
        products.createProduct(sku, "M18 inventory", BigDecimal.ONE, 1);
        var plan = M18Workload.plan("inventory_reserve", runId, 17, 1, 2, 2, 20_000);
        var history = M18Workload.execute(plan, op -> reservations.reserve(sku, op.quantity()).getId().toString());
        assertThat(history.quiescent()).isTrue();
        assertThat(history.complete()).isTrue();
        assertThat(history.invokedCount()).isEqualTo(4);
        assertThat(history.completedCount()).isEqualTo(4);
        assertThat(history.observations()).filteredOn(o -> "SUCCESS".equals(o.outcome())).hasSize(1);
        assertThat(history.observations()).filteredOn(o -> "REJECTED".equals(o.outcome())).hasSize(3)
            .allSatisfy(o -> assertThat(o.rejectionCode()).isEqualTo("insufficient_stock"));
        assertThat(history.observations()).allSatisfy(o -> assertThat(o.completedNanos()).isGreaterThan(o.invokedNanos()));
        writeAndRoundTrip(history);
    }

    @Test
    void concurrentCheckoutRetriesRecordOneOrderAndReplayResponses() throws Exception {
        String runId = UUID.randomUUID().toString();
        String sku = "M18-CHK-" + runId;
        products.createProduct(sku, "M18 checkout", BigDecimal.ONE, 1);
        UUID cartId = carts.createCart().getId();
        carts.setItemQuantity(cartId, sku, 1);
        String key = "m18-" + runId;
        var plan = M18Workload.plan("checkout_retry", runId, 33, 1, 3, 2, 20_000);
        var history = M18Workload.execute(plan, op -> {
            CheckoutResult result = checkout.checkoutIdempotently(cartId, key);
            return result.order().getId().toString();
        });
        assertThat(history.quiescent()).isTrue();
        assertThat(history.complete()).isTrue();
        assertThat(history.completedCount()).isEqualTo(6);
        assertThat(history.observations()).extracting(M18Workload.Observation::outcome).containsOnly("SUCCESS");
        assertThat(history.observations()).extracting(M18Workload.Observation::resultId)
            .containsOnly(history.observations().get(0).resultId());
        assertThat(M18Workload.json(history)).doesNotContain(key);
        writeAndRoundTrip(history);
    }

    @Test
    void timeoutRetainsUnresolvedInvocationAndCleansUpActors() throws Exception {
        String id = UUID.randomUUID().toString();
        var plan = M18Workload.plan("inventory_reserve", id, 1, 1, 2, 2, 150);
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        try {
            var history = M18Workload.execute(plan, op -> {
                entered.countDown();
                release.await();
                return "unexpected";
            });
            assertThat(entered.getCount()).isZero();
            assertThat(history.quiescent()).isTrue();
            assertThat(history.complete()).isFalse();
            assertThat(history.plannedCount()).isEqualTo(4);
            assertThat(history.invokedCount()).isEqualTo(2);
            assertThat(history.completedCount()).isZero();
            assertThat(history.observations()).filteredOn(o -> "UNRESOLVED".equals(o.state())).hasSize(2);
            assertThat(history.observations()).filteredOn(o -> "NOT_INVOKED".equals(o.state())).hasSize(2);
            assertThat(M18Workload.parse(M18Workload.json(history))).isEqualTo(history);
        } finally { release.countDown(); }
    }

    @Test
    void incompleteHistoryCannotClaimCompleteCoverage() throws Exception {
        String id = UUID.randomUUID().toString();
        var plan = M18Workload.plan("inventory_reserve", id, 1, 1, 2, 2, 150);
        var history = M18Workload.execute(plan, op -> {
            throw new BusinessRuleViolation(HttpStatus.CONFLICT, "test_rejection", "test");
        });
        String json = M18Workload.json(history);
        assertThat(M18Workload.parse(json)).isEqualTo(history);
        assertThatThrownBy(() -> M18Workload.parse(json.replace("\"completedCount\" : 4", "\"completedCount\" : 9")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> M18Workload.parse("{\"schemaVersion\":99}"))
            .isInstanceOf(Exception.class);
    }

    private void writeAndRoundTrip(M18Workload.History history) throws Exception {
        String json = M18Workload.json(history);
        assertThat(M18Workload.json(history)).isEqualTo(json);
        assertThat(M18Workload.parse(json)).isEqualTo(history);
        Path output = Path.of("build", "consistency-lab", "m18", history.plan().scenarioId() + ".json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, json);
    }
}
