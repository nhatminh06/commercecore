package com.commercecore.consistencylab;

import com.commercecore.shared.BusinessRuleViolation;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Test-only operation recorder. It observes service calls; it does not check their correctness. */
final class M18Workload {
    private static final ObjectMapper JSON = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

    record Planned(String operationId, int actorId, int step, String type,
                   String logicalRequestId, int quantity) { }
    record Plan(int schemaVersion, String scenarioId, String runId, long seed,
                int initialStock, int actorCount, int operationsPerActor,
                long timeoutMillis, String startConstraint, List<Planned> operations) { }
    record Observation(String operationId, String state, String outcome, String resultId,
                       String rejectionCode, String exceptionType, Long invokedNanos,
                       Long completedNanos, String invokedAt, String completedAt) { }
    record History(int schemaVersion, Plan plan, String provenance, String clockSource,
                   String startedAt, String finishedAt, boolean quiescent, boolean complete,
                   String quiescenceEvidence, int plannedCount, int invokedCount,
                   int completedCount, List<Observation> observations) { }

    @FunctionalInterface interface Operation {
        String call(Planned planned) throws Exception;
    }

    static Plan plan(String scenarioId, String runId, long seed, int stock,
                     int actors, int perActor, long timeoutMillis) {
        if (!List.of("inventory_reserve", "checkout_retry").contains(scenarioId)
            || actors < 2 || actors > 8 || perActor < 1 || perActor > 4
            || actors * perActor > 32 || stock < 0 || stock > 32
            || timeoutMillis < 100 || timeoutMillis > 60_000) {
            throw new IllegalArgumentException("unsupported or unbounded M18 plan");
        }
        UUID.fromString(runId);
        List<Integer> actorOrder = new ArrayList<>();
        for (int actor = 0; actor < actors; actor++) actorOrder.add(actor);
        java.util.Collections.shuffle(actorOrder, new Random(seed));
        List<Planned> operations = new ArrayList<>();
        for (int actor : actorOrder) {
            for (int step = 0; step < perActor; step++) {
                String id = scenarioId + "/actor-" + actor + "/step-" + step;
                operations.add(new Planned(id, actor, step, scenarioId,
                    scenarioId.equals("checkout_retry") ? "shared-checkout-key" : id, 1));
            }
        }
        return new Plan(1, scenarioId, runId, seed, stock, actors, perActor,
            timeoutMillis, "first operation starts after all actors are ready; each actor is sequential",
            List.copyOf(operations));
    }

    static History execute(Plan plan, Operation operation) throws InterruptedException {
        validatePlan(plan);
        long origin = System.nanoTime();
        String startedAt = Instant.now().toString();
        CountDownLatch ready = new CountDownLatch(plan.actorCount());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(plan.actorCount());
        Observation[] observed = new Observation[plan.operations().size()];
        AtomicLong lastEvent = new AtomicLong();
        List<Future<?>> futures = new ArrayList<>();
        boolean terminated;
        try {
            for (int actor = 0; actor < plan.actorCount(); actor++) {
                int actorId = actor;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        for (int index = 0; index < plan.operations().size(); index++) {
                            Planned planned = plan.operations().get(index);
                            if (planned.actorId() != actorId || Thread.currentThread().isInterrupted()) continue;
                            long invoked = monotonic(origin, lastEvent);
                            String invokedAt = Instant.now().toString();
                            observed[index] = new Observation(planned.operationId(), "UNRESOLVED", null,
                                null, null, null, invoked, null, invokedAt, null);
                            try {
                                String result = operation.call(planned);
                                observed[index] = new Observation(planned.operationId(), "COMPLETED", "SUCCESS",
                                    result, null, null, invoked, monotonic(origin, lastEvent), invokedAt,
                                    Instant.now().toString());
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                break;
                            } catch (BusinessRuleViolation e) {
                                observed[index] = new Observation(planned.operationId(), "COMPLETED", "REJECTED",
                                    null, e.getCode(), null, invoked, monotonic(origin, lastEvent), invokedAt,
                                    Instant.now().toString());
                            } catch (Exception e) {
                                observed[index] = new Observation(planned.operationId(), "COMPLETED", "EXCEPTION",
                                    null, null, e.getClass().getName(), invoked, monotonic(origin, lastEvent),
                                    invokedAt, Instant.now().toString());
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }));
            }
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(plan.timeoutMillis());
            if (ready.await(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) start.countDown();
            for (Future<?> future : futures) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                try { future.get(remaining, TimeUnit.NANOSECONDS); }
                catch (java.util.concurrent.TimeoutException e) { break; }
                catch (java.util.concurrent.ExecutionException e) { throw new IllegalStateException(e); }
            }
        } finally {
            start.countDown();
            pool.shutdownNow();
            terminated = pool.awaitTermination(2, TimeUnit.SECONDS);
        }
        List<Observation> snapshot = new ArrayList<>();
        for (int i = 0; i < observed.length; i++) {
            Observation value = observed[i];
            snapshot.add(value == null ? new Observation(plan.operations().get(i).operationId(),
                "NOT_INVOKED", null, null, null, null, null, null, null, null) : value);
        }
        int invoked = (int) snapshot.stream().filter(o -> o.invokedNanos() != null).count();
        int completed = (int) snapshot.stream().filter(o -> o.completedNanos() != null).count();
        History history = new History(1, plan, "test-jvm/spring-service/disposable-postgresql",
            "System.nanoTime monotonic within test JVM", startedAt, Instant.now().toString(), terminated,
            terminated && completed == snapshot.size(),
            terminated ? "all actor tasks terminated before history freeze" : "actor termination unconfirmed",
            snapshot.size(), invoked, completed, List.copyOf(snapshot));
        validate(history);
        return history;
    }

    private static long monotonic(long origin, AtomicLong last) {
        return last.updateAndGet(previous -> Math.max(previous + 1, System.nanoTime() - origin));
    }

    static String json(History history) throws IOException {
        validate(history);
        return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(history) + "\n";
    }

    static History parse(String json) throws IOException {
        History history = JSON.readValue(json, History.class);
        validate(history);
        return history;
    }

    private static void validatePlan(Plan plan) {
        if (plan == null || plan.schemaVersion() != 1 || plan.operations() == null
            || plan.operations().size() != plan.actorCount() * plan.operationsPerActor())
            throw new IllegalArgumentException("invalid M18 plan coverage");
        Plan expected = plan(plan.scenarioId(), plan.runId(), plan.seed(), plan.initialStock(),
            plan.actorCount(), plan.operationsPerActor(), plan.timeoutMillis());
        if (!plan.equals(expected)) throw new IllegalArgumentException("plan does not match seed and constraints");
        HashSet<String> ids = new HashSet<>();
        for (Planned item : plan.operations()) {
            if (item == null || !ids.add(item.operationId()) || item.actorId() < 0
                || item.actorId() >= plan.actorCount() || item.step() < 0
                || item.step() >= plan.operationsPerActor() || !item.type().equals(plan.scenarioId())
                || item.quantity() != 1 || item.logicalRequestId() == null)
                throw new IllegalArgumentException("invalid M18 planned operation");
        }
    }

    static void validate(History history) {
        if (history == null || history.schemaVersion() != 1) throw new IllegalArgumentException("invalid history version");
        validatePlan(history.plan());
        if (history.observations() == null || history.plannedCount() != history.plan().operations().size()
            || history.observations().size() != history.plannedCount()
            || history.invokedCount() < history.completedCount()
            || history.invokedCount() > history.plannedCount()
            || history.complete() != (history.quiescent() && history.completedCount() == history.plannedCount())
            || history.provenance() == null || history.clockSource() == null
            || history.startedAt() == null || history.finishedAt() == null)
            throw new IllegalArgumentException("invalid history coverage");
        int invoked = 0, completed = 0;
        for (int i = 0; i < history.plannedCount(); i++) {
            Observation o = history.observations().get(i);
            if (o == null || !o.operationId().equals(history.plan().operations().get(i).operationId()))
                throw new IllegalArgumentException("observation identity mismatch");
            switch (o.state()) {
                case "NOT_INVOKED" -> {
                    if (o.invokedNanos() != null || o.completedNanos() != null || o.outcome() != null
                        || o.resultId() != null || o.rejectionCode() != null || o.exceptionType() != null
                        || o.invokedAt() != null || o.completedAt() != null)
                        throw new IllegalArgumentException("fabricated invocation");
                }
                case "UNRESOLVED" -> {
                    invoked++;
                    if (o.invokedNanos() == null || o.invokedAt() == null || o.completedNanos() != null
                        || o.completedAt() != null || o.outcome() != null || o.resultId() != null
                        || o.rejectionCode() != null || o.exceptionType() != null)
                        throw new IllegalArgumentException("fabricated completion");
                }
                case "COMPLETED" -> {
                    invoked++; completed++;
                    if (o.invokedNanos() == null || o.completedNanos() == null
                        || o.invokedAt() == null || o.completedAt() == null
                        || o.completedNanos() < o.invokedNanos() || o.outcome() == null
                        || !List.of("SUCCESS", "REJECTED", "EXCEPTION").contains(o.outcome()))
                        throw new IllegalArgumentException("invalid completion");
                    if ((o.outcome().equals("SUCCESS") && (o.resultId() == null || o.rejectionCode() != null || o.exceptionType() != null))
                        || (o.outcome().equals("REJECTED") && (o.resultId() != null || o.rejectionCode() == null || o.exceptionType() != null))
                        || (o.outcome().equals("EXCEPTION") && (o.resultId() != null || o.rejectionCode() != null || o.exceptionType() == null)))
                        throw new IllegalArgumentException("invalid result fields");
                }
                default -> throw new IllegalArgumentException("invalid observation state");
            }
        }
        if (invoked != history.invokedCount() || completed != history.completedCount())
            throw new IllegalArgumentException("history count mismatch");
    }

    private M18Workload() { }
}
