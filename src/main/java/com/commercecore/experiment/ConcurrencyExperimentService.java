package com.commercecore.experiment;

import com.commercecore.cart.Cart;
import com.commercecore.cart.CartService;
import com.commercecore.catalog.ProductService;
import com.commercecore.checkout.CheckoutIdempotencyRepository;
import com.commercecore.checkout.CheckoutResult;
import com.commercecore.checkout.CheckoutService;
import com.commercecore.inventory.InventoryRepository;
import com.commercecore.kafka.KafkaEventReceiptRepository;
import com.commercecore.payment.AuthorizationResult;
import com.commercecore.payment.PaymentProvider;
import com.commercecore.payment.ProviderLookupResult;
import com.commercecore.reservation.ReservationService;
import com.commercecore.shared.BusinessRuleViolation;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@Profile("dev")
public class ConcurrencyExperimentService {
    static final int MIN_WORKERS = 2;
    static final int MAX_WORKERS = 200;
    private static final long TIMEOUT_SECONDS = 30;
    private final ProductService products;
    private final ReservationService reservations;
    private final InventoryRepository inventory;
    private final CartService carts;
    private final CheckoutService checkout;
    private final CheckoutIdempotencyRepository idempotency;
    private final PaymentProvider paymentProvider;
    private final KafkaEventReceiptRepository receipts;

    public ConcurrencyExperimentService(ProductService products, ReservationService reservations,
        InventoryRepository inventory, CartService carts, CheckoutService checkout,
        CheckoutIdempotencyRepository idempotency, PaymentProvider paymentProvider,
        KafkaEventReceiptRepository receipts) {
        this.products = products;
        this.reservations = reservations;
        this.inventory = inventory;
        this.carts = carts;
        this.checkout = checkout;
        this.idempotency = idempotency;
        this.paymentProvider = paymentProvider;
        this.receipts = receipts;
    }

    public InventoryExperimentResult inventory(InventoryExperimentRequest request) {
        validateWorkers(request.workers());
        if (request.initialInventory() < 0 || request.initialInventory() > 10_000) invalid("initialInventory must be between 0 and 10000");
        if (request.quantityPerWorker() < 1 || request.quantityPerWorker() > 100) invalid("quantityPerWorker must be between 1 and 100");
        String experimentId = id();
        String sku = "EXP-INV-" + experimentId.substring(4).toUpperCase();
        products.createProduct(sku, "Concurrency inventory experiment", new BigDecimal("1.00"), request.initialInventory());
        long started = System.nanoTime();
        List<Outcome<Void>> outcomes = race(request.workers(), () -> { reservations.reserve(sku, request.quantityPerWorker()); return null; });
        int finalInventory = inventory.findById(sku).orElseThrow().getAvailableQuantity();
        int successful = successes(outcomes);
        boolean complete = complete(outcomes);
        boolean invariant = complete && finalInventory >= 0
            && successful * request.quantityPerWorker() + finalInventory == request.initialInventory();
        return new InventoryExperimentResult(experimentId, sku, request.workers(), request.initialInventory(),
            request.quantityPerWorker(), successful, outcomes.size() - successful, finalInventory,
            complete, invariant, elapsed(started));
    }

    public CheckoutExperimentResult checkout(WorkerExperimentRequest request) {
        validateWorkers(request.workers());
        String experimentId = id();
        String sku = "EXP-CHECKOUT-" + experimentId.substring(4).toUpperCase();
        products.createProduct(sku, "Concurrency checkout experiment", new BigDecimal("1.00"), 1);
        Cart cart = carts.createCart();
        carts.setItemQuantity(cart.getId(), sku, 1);
        String key = experimentId + "-checkout-key";
        long started = System.nanoTime();
        List<Outcome<CheckoutResult>> outcomes = race(request.workers(), () -> checkout.checkoutIdempotently(cart.getId(), key));
        List<UUID> orderIds = outcomes.stream().filter(Outcome::success).map(o -> o.value().order().getId()).distinct().toList();
        long mappings = idempotency.countByCartId(cart.getId());
        boolean complete = complete(outcomes);
        boolean invariant = complete && mappings == 1 && orderIds.size() == 1;
        return new CheckoutExperimentResult(experimentId, request.workers(), cart.getId(), key,
            successes(outcomes), outcomes.size() - successes(outcomes), orderIds, mappings,
            complete, invariant, elapsed(started));
    }

    public ProviderExperimentResult provider(WorkerExperimentRequest request) {
        validateWorkers(request.workers());
        String experimentId = id();
        UUID requestId = UUID.randomUUID();
        long started = System.nanoTime();
        List<Outcome<AuthorizationResult>> outcomes = race(request.workers(),
            () -> paymentProvider.authorize(requestId, new BigDecimal("10.00")));
        List<String> logicalResults = outcomes.stream().filter(Outcome::success)
            .map(o -> logicalResult(o.value())).distinct().toList();
        ProviderLookupResult persisted = paymentProvider.lookup(requestId);
        boolean complete = complete(outcomes);
        boolean invariant = complete && outcomes.stream().allMatch(Outcome::success)
            && logicalResults.size() == 1 && !"NOT_FOUND".equals(persisted.status().name());
        return new ProviderExperimentResult(experimentId, request.workers(), requestId,
            successes(outcomes), outcomes.size() - successes(outcomes), logicalResults,
            persisted.status().name(), persisted.providerReference(), complete, invariant, elapsed(started));
    }

    public DuplicateEventExperimentResult duplicateEvent(DuplicateEventExperimentRequest request) {
        validateWorkers(request.attempts());
        String experimentId = id();
        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        long started = System.nanoTime();
        List<Outcome<Integer>> outcomes = race(request.attempts(), () -> receipts.tryInsert(eventId,
            "ORDER_CREATED", "ORDER", aggregateId, Instant.now()));
        int inserted = outcomes.stream().filter(Outcome::success).mapToInt(o -> o.value()).sum();
        long count = receipts.existsById(eventId) ? 1 : 0;
        boolean complete = complete(outcomes);
        return new DuplicateEventExperimentResult(experimentId, request.attempts(), eventId, inserted,
            count, complete, complete && inserted == 1 && count == 1, elapsed(started));
    }

    private <T> List<Outcome<T>> race(int workers, Callable<T> task) {
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Outcome<T>>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < workers; i++) futures.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                try { return Outcome.ok(task.call()); } catch (Exception e) { return Outcome.failed(); }
            }));
            if (!ready.await(5, TimeUnit.SECONDS)) return timedOut(workers);
            start.countDown();
            List<Outcome<T>> outcomes = new ArrayList<>();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
            for (Future<Outcome<T>> future : futures) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) return timedOut(workers);
                outcomes.add(future.get(remaining, TimeUnit.NANOSECONDS));
            }
            return outcomes;
        } catch (Exception e) {
            return timedOut(workers);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private static <T> List<Outcome<T>> timedOut(int workers) {
        List<Outcome<T>> result = new ArrayList<>();
        for (int i = 0; i < workers; i++) result.add(Outcome.timedOut());
        return result;
    }
    private static int successes(List<? extends Outcome<?>> outcomes) { return (int) outcomes.stream().filter(Outcome::success).count(); }
    private static boolean complete(List<? extends Outcome<?>> outcomes) { return outcomes.stream().noneMatch(Outcome::timeout); }
    private static String logicalResult(AuthorizationResult result) {
        if (result instanceof AuthorizationResult.Authorized authorized) return "AUTHORIZED:" + authorized.providerReference();
        return "DECLINED:" + ((AuthorizationResult.Declined) result).reason();
    }
    private static long elapsed(long started) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started); }
    private static String id() { return "exp-" + UUID.randomUUID(); }
    private static void validateWorkers(int workers) { if (workers < MIN_WORKERS || workers > MAX_WORKERS) invalid("workers/attempts must be between 2 and 200"); }
    private static void invalid(String message) { throw new BusinessRuleViolation(HttpStatus.BAD_REQUEST, "invalid_experiment_configuration", message); }
    private record Outcome<T>(T value, boolean success, boolean timeout) {
        static <T> Outcome<T> ok(T value) { return new Outcome<>(value, true, false); }
        static <T> Outcome<T> failed() { return new Outcome<>(null, false, false); }
        static <T> Outcome<T> timedOut() { return new Outcome<>(null, false, true); }
    }
}
