package com.commercecore.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.commercecore.CommerceCoreApplication;
import com.commercecore.cart.CartService;
import com.commercecore.catalog.ProductService;
import com.commercecore.checkout.CheckoutService;
import com.commercecore.checkout.OrderRepository;
import com.commercecore.checkout.OrderStatus;
import com.commercecore.inventory.InventoryService;
import com.commercecore.paymentservice.NextOutcome;
import com.commercecore.paymentservice.OutcomeControl;
import com.commercecore.paymentservice.PaymentProviderGrpcService;
import com.commercecore.paymentservice.ProviderLedger;
import com.commercecore.paymentservice.ProviderPayment;
import com.commercecore.reservation.ReservationService;
import com.commercecore.reservation.ReservationStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/** Opt-in real gRPC network experiments; never attached to application Compose. */
@EnabledIfEnvironmentVariable(named = "LAB_NETWORK_TEST", matches = "1")
@SpringBootTest(classes = CommerceCoreApplication.class)
@ActiveProfiles("remote-provider")
class NetworkFaultIntegrationTest {
    private static final String IMAGE = "ghcr.io/shopify/toxiproxy:2.9.0";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private static final PostgreSQLContainer<?> COMMERCE = new PostgreSQLContainer<>("postgres:16")
        .withDatabaseName("commercecore_lab_m17");
    private static final PostgreSQLContainer<?> PROVIDER = new PostgreSQLContainer<>("postgres:16")
        .withDatabaseName("payment_provider_lab_m17");
    private static final String CONTAINER = "commercecore-m17-" + UUID.randomUUID().toString().substring(0, 8);
    private static Server providerServer;
    private static ProviderLedger ledger;
    private static OutcomeControl outcomes;
    private static int proxyPort;
    private static int apiPort;
    private static boolean readersReady;

    static {
        try {
            COMMERCE.start();
            PROVIDER.start();
            DriverManagerDataSource data = new DriverManagerDataSource(PROVIDER.getJdbcUrl(),
                PROVIDER.getUsername(), PROVIDER.getPassword());
            String migration = Files.readString(Path.of("payment-service/src/main/resources/db/migration/V1__create_provider_payments.sql"));
            try (Connection connection = data.getConnection(); var statement = connection.createStatement()) {
                statement.execute(migration);
            }
            outcomes = new OutcomeControl();
            ledger = new ProviderLedger(new JdbcTemplate(data),
                new TransactionTemplate(new DataSourceTransactionManager(data)), outcomes);
            providerServer = NettyServerBuilder.forPort(0)
                .addService(new PaymentProviderGrpcService(ledger, 1000)).build().start();
            command("docker", "image", "inspect", IMAGE);
            command("docker", "run", "--pull=never", "--rm", "-d", "--name", CONTAINER,
                "--add-host", "host.docker.internal:host-gateway", "-p", "127.0.0.1::8474",
                "-p", "127.0.0.1::8666", IMAGE);
            apiPort = mappedPort(8474);
            proxyPort = mappedPort(8666);
            waitForApi();
            api("POST", "/proxies", "{\"name\":\"payment\",\"listen\":\"0.0.0.0:8666\",\"upstream\":\"host.docker.internal:"
                + providerServer.getPort() + "\"}");
        } catch (Exception e) {
            cleanup();
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", COMMERCE::getJdbcUrl);
        registry.add("spring.datasource.username", COMMERCE::getUsername);
        registry.add("spring.datasource.password", COMMERCE::getPassword);
        registry.add("spring.flyway.locations", () -> "filesystem:src/main/resources/db/migration");
        registry.add("commercecore.payment.grpc.host", () -> "127.0.0.1");
        registry.add("commercecore.payment.grpc.port", () -> proxyPort);
        registry.add("commercecore.payment.grpc.deadline-ms", () -> 2500);
    }

    @Autowired private ProductService products;
    @Autowired private CartService carts;
    @Autowired private CheckoutService checkout;
    @Autowired private PaymentService payments;
    @Autowired private PaymentProvider providerClient;
    @Autowired private PaymentRepository paymentRows;
    @Autowired private PaymentReconciliationService reconciliation;
    @Autowired private OrderRepository orders;
    @Autowired private ReservationService reservations;
    @Autowired private InventoryService inventory;

    @BeforeEach
    void ready() throws Exception {
        if (!readersReady) {
            reader(COMMERCE, "lab_reader_m17", "inventory,orders,inventory_reservations,checkout_idempotency");
            reader(PROVIDER, "lab_provider_reader_m17", "provider_payments");
            readersReady = true;
        }
        outcomes.setNext(NextOutcome.SUCCESS);
        api("POST", "/reset", "{}");
        JsonNode state = api("GET", "/proxies/payment", null);
        assertThat(state.path("enabled").asBoolean()).isTrue();
        assertThat(state.path("toxics").size()).isZero();
        awaitProxyReady();
    }

    @AfterEach
    void restore() throws Exception {
        api("POST", "/reset", "{}");
        JsonNode state = api("GET", "/proxies/payment", null);
        assertThat(state.path("enabled").asBoolean()).isTrue();
        assertThat(state.path("toxics").size()).isZero();
        System.out.println("M17 cleanup=verified at=" + Instant.now());
    }

    @AfterAll
    static void stop() {
        cleanup();
    }

    @Test
    void unavailableBeforeAuthorizationLeavesDurableUnknownAndNoProviderRow() throws Exception {
        UUID order = newOrder();
        JsonNode disabled = api("POST", "/proxies/payment", "{\"enabled\":false}");
        assertThat(disabled.path("enabled").asBoolean()).isFalse();
        System.out.println("M17 scenario=unavailable configured_at=" + Instant.now());
        assertThat(probeCode()).isEqualTo(Status.Code.UNAVAILABLE);
        Payment first = payments.initiatePayment(order);
        assertThat(first.getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(payments.initiatePayment(order).getId()).isEqualTo(first.getId());
        assertThat(paymentRows.findById(first.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(ledger.lookup(first.getId())).isEmpty();
        assertPendingStock(order);
        System.out.println("M17 scenario=unavailable payment=" + first.getId() + " local=UNKNOWN provider=NOT_FOUND observed_at=" + Instant.now());
        api("POST", "/proxies/payment", "{\"enabled\":true}");
        System.out.println("M17 scenario=unavailable fault_removed_at=" + Instant.now());
        assertThat(ledger.lookup(first.getId())).isEmpty();
        awaitProxyReady();
        reconciliation.reconcile(first.getId()); // LookupPayment through the restored proxy.
        assertThat(paymentRows.findById(first.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
    }

    @Test
    void downstreamLatencyExceedsDeadlineButProviderAuthorized() throws Exception {
        UUID order = newOrder();
        api("POST", "/proxies/payment/toxics", "{\"name\":\"lag\",\"type\":\"latency\",\"stream\":\"downstream\",\"attributes\":{\"latency\":3000,\"jitter\":0}}");
        assertThat(api("GET", "/proxies/payment/toxics/lag", null).path("type").asText()).isEqualTo("latency");
        System.out.println("M17 scenario=latency configured_ms=3000 deadline_ms=2500 configured_at=" + Instant.now());
        assertThat(probeCode()).isEqualTo(Status.Code.DEADLINE_EXCEEDED);
        Payment first = payments.initiatePayment(order);
        assertThat(first.getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(ledger.lookup(first.getId()).orElseThrow().status()).isEqualTo(ProviderPayment.Status.AUTHORIZED);
        long rows = ledger.count();
        assertThat(payments.initiatePayment(order).getId()).isEqualTo(first.getId());
        assertThat(ledger.count()).isEqualTo(rows);
        assertPendingStock(order);
        System.out.println("M17 scenario=latency payment=" + first.getId() + " local=UNKNOWN provider=AUTHORIZED observed_at=" + Instant.now());
        captureEvidence(first.getId());
        api("DELETE", "/proxies/payment/toxics/lag", null);
        System.out.println("M17 scenario=latency fault_removed_at=" + Instant.now());
        awaitProxyReady();
        reconciliation.reconcile(first.getId());
        assertThat(paymentRows.findById(first.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(ledger.count()).isEqualTo(rows);
    }

    @Test
    void interruptAfterProviderCommitThenRestoreLookup() throws Exception {
        UUID order = newOrder();
        outcomes.setNext(NextOutcome.TIMEOUT_AFTER_PROCESSING); // Provider commits before its test-only response delay.
        System.out.println("M17 scenario=interruption started_at=" + Instant.now());
        CompletableFuture<Payment> call = CompletableFuture.supplyAsync(() -> payments.initiatePayment(order));
        UUID paymentId = awaitPaymentAndProviderCommit(order);
        Instant providerCommitObservedAt = Instant.now();
        api("POST", "/proxies/payment/toxics", "{\"name\":\"cut\",\"type\":\"timeout\",\"stream\":\"downstream\",\"attributes\":{\"timeout\":0}}");
        Instant toxicConfiguredAt = Instant.now();
        assertThat(api("GET", "/proxies/payment/toxics/cut", null).path("type").asText()).isEqualTo("timeout");
        System.out.println("M17 scenario=interruption payment=" + paymentId + " provider_commit_observed_at=" + providerCommitObservedAt + " toxic_configured_at=" + toxicConfiguredAt);
        Payment local = call.get(5, TimeUnit.SECONDS);
        if (local.getStatus() != PaymentStatus.UNKNOWN) {
            throw new AssertionError("UNVERIFIED injection: response arrived despite configured downstream cut");
        }
        assertThat(local.getId()).isEqualTo(paymentId);
        assertThat(ledger.lookup(paymentId).orElseThrow().status()).isEqualTo(ProviderPayment.Status.AUTHORIZED);
        long rows = ledger.count();
        api("DELETE", "/proxies/payment/toxics/cut", null);
        System.out.println("M17 scenario=interruption fault_removed_at=" + Instant.now());
        awaitProxyReady();
        reconciliation.reconcile(paymentId);
        assertThat(paymentRows.findById(paymentId).orElseThrow().getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(ledger.count()).isEqualTo(rows);
        System.out.println("M17 scenario=interruption local_recovered=AUTHORIZED observed_at=" + Instant.now());
    }

    @Test
    void ineffectiveFaultIsReportedUnverified() throws Exception {
        UUID order = newOrder();
        Payment payment = payments.initiatePayment(order); // No toxic is active.
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(injectionVerdict(payment)).isEqualTo("UNVERIFIED");
        System.out.println("M17 scenario=no_effect injection=UNVERIFIED payment=" + payment.getId());
    }

    @Test
    void providerDelayAloneCompletesWithinClientDeadline() throws Exception {
        UUID order = newOrder();
        outcomes.setNext(NextOutcome.TIMEOUT_AFTER_PROCESSING);
        Payment payment = payments.initiatePayment(order);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(ledger.lookup(payment.getId()).orElseThrow().status())
            .isEqualTo(ProviderPayment.Status.AUTHORIZED);
        System.out.println("M17 scenario=delay_control local=AUTHORIZED provider=AUTHORIZED");
    }

    @Test
    void failedAssertionStillRemovesFault() throws Exception {
        api("POST", "/proxies/payment/toxics", "{\"name\":\"cleanup_probe\",\"type\":\"latency\",\"attributes\":{\"latency\":100}}");
        try {
            throw new AssertionError("deliberate test assertion failure");
        } catch (AssertionError expected) {
            assertThat(expected).hasMessage("deliberate test assertion failure");
        } finally {
            api("POST", "/reset", "{}");
        }
        assertThat(api("GET", "/proxies/payment", null).path("toxics").size()).isZero();
    }

    private static String injectionVerdict(Payment payment) {
        return payment.getStatus() == PaymentStatus.UNKNOWN ? "OBSERVED_TIMEOUT" : "UNVERIFIED";
    }

    private Status.Code probeCode() {
        Throwable failure = catchThrowable(() -> providerClient.authorize(UUID.randomUUID(), BigDecimal.TEN));
        assertThat(failure).isInstanceOf(PaymentProviderTimeoutException.class);
        assertThat(failure.getCause()).isInstanceOf(StatusRuntimeException.class);
        Status.Code code = ((StatusRuntimeException) failure.getCause()).getStatus().getCode();
        System.out.println("M17 client_grpc_code=" + code + " observed_at=" + Instant.now());
        return code;
    }

    private UUID newOrder() {
        String sku = "M17-" + UUID.randomUUID();
        products.createProduct(sku, "Network test product", new BigDecimal("10.00"), 3);
        UUID cart = carts.createCart().getId();
        carts.setItemQuantity(cart, sku, 2);
        return checkout.checkoutIdempotently(cart, "m17-" + UUID.randomUUID()).order().getId();
    }

    private void assertPendingStock(UUID order) {
        assertThat(orders.findById(order).orElseThrow().getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(reservations.getReservationsForOrder(order)).singleElement()
            .satisfies(r -> assertThat(r.getStatus()).isEqualTo(ReservationStatus.ACTIVE));
        String sku = reservations.getReservationsForOrder(order).getFirst().getSku();
        assertThat(inventory.getBySku(sku).getAvailableQuantity()).isEqualTo(1);
    }

    private UUID awaitPaymentAndProviderCommit(UUID order) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < end) {
            var payment = paymentRows.findByOrderId(order);
            if (payment.isPresent() && ledger.lookup(payment.get().getId()).isPresent()) {
                return payment.get().getId();
            }
            Thread.sleep(20);
        }
        throw new AssertionError("UNVERIFIED injection: provider commit was not observed before deadline");
    }

    private static void reader(PostgreSQLContainer<?> db, String role, String tables) throws Exception {
        DriverManagerDataSource data = new DriverManagerDataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword());
        try (Connection connection = data.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE ROLE " + role + " LOGIN PASSWORD 'm17-reader-test-password'");
            statement.execute("GRANT CONNECT ON DATABASE " + db.getDatabaseName() + " TO " + role);
            statement.execute("GRANT USAGE ON SCHEMA public TO " + role);
            statement.execute("GRANT SELECT ON " + tables + " TO " + role);
        }
    }

    private void captureEvidence(UUID paymentId) throws Exception {
        UUID run = UUID.randomUUID();
        JsonNode commerce = recorded(COMMERCE, "commercecore", "lab_reader_m17", run);
        JsonNode provider = recorded(PROVIDER, "provider", "lab_provider_reader_m17", run);
        assertThat(commerce.path("results").size()).isEqualTo(3);
        commerce.path("results").forEach(result -> assertThat(result.path("verdict").asText()).isEqualTo("PASS"));
        assertThat(provider.path("results").get(0).path("verdict").asText()).isEqualTo("PASS");
        assertThat(provider.path("results").get(1).path("verdict").asText()).isEqualTo("INCONCLUSIVE");
        System.out.println("M17 evidence run=" + run + " payment=" + paymentId
            + " commerce_snapshot=PASS provider_observation=PASS cross_store=INCONCLUSIVE");
    }

    private static JsonNode recorded(PostgreSQLContainer<?> db, String store, String role, UUID run) throws Exception {
        Path wrapper = Files.createTempDirectory("m17-psql-");
        Path evidence = Files.createTempFile("m17-evidence-", ".json");
        try {
            Path psql = wrapper.resolve("psql");
            Files.writeString(psql, "#!/bin/sh\nexec docker exec -i -e \"PGPASSWORD=$PGPASSWORD\" "
                + db.getContainerId() + " psql \"$@\"\n");
            psql.toFile().setExecutable(true, true);
            ProcessBuilder capture = new ProcessBuilder("python3", "tools/consistency_lab/recorder.py",
                "--store", store, "--run-id", run.toString(), "--host", "127.0.0.1", "--port", "5432",
                "--database", db.getDatabaseName(), "--role", role);
            capture.environment().put("PATH", wrapper + ":" + System.getenv("PATH"));
            capture.environment().put("LAB_DB_PASSWORD", "m17-reader-test-password");
            String bundle = process(capture);
            assertThat(bundle).doesNotContain("m17-reader-test-password", "jdbc:postgresql", "\"idempotency_key\":\"m17-");
            Files.writeString(evidence, bundle);
            return JSON.readTree(process(new ProcessBuilder("python3", "tools/consistency_lab/oracle.py", evidence.toString())));
        } finally {
            Files.deleteIfExists(evidence);
            Files.deleteIfExists(wrapper.resolve("psql"));
            Files.deleteIfExists(wrapper);
        }
    }

    private static String process(ProcessBuilder command) throws Exception {
        Process process = command.redirectErrorStream(true).start();
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("evidence subprocess timed out");
        }
        String output = new String(process.getInputStream().readAllBytes());
        if (process.exitValue() != 0) throw new IllegalStateException("evidence subprocess failed: " + output);
        return output;
    }

    private void awaitProxyReady() throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < end) {
            try {
                assertThat(providerClient.lookup(UUID.randomUUID()).status())
                    .isEqualTo(ProviderPaymentStatus.NOT_FOUND);
                return;
            } catch (PaymentProviderUnavailableException ignored) {
                Thread.sleep(100);
            }
        }
        throw new AssertionError("restored gRPC path did not become ready");
    }

    private static JsonNode api(String method, String path, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + apiPort + path))
            .timeout(Duration.ofSeconds(3));
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) request.header("Content-Type", "application/json");
        HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Toxiproxy API " + path + " returned " + response.statusCode());
        }
        return response.body().isBlank() ? JSON.createObjectNode() : JSON.readTree(response.body());
    }

    private static void waitForApi() throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < end) {
            try {
                api("GET", "/version", null);
                return;
            } catch (Exception ignored) {
                Thread.sleep(100);
            }
        }
        throw new IllegalStateException("Toxiproxy control API did not become ready");
    }

    private static int mappedPort(int port) throws Exception {
        String output = command("docker", "port", CONTAINER, port + "/tcp").trim();
        return Integer.parseInt(output.substring(output.lastIndexOf(':') + 1));
    }

    private static String command(String... args) throws Exception {
        Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("Docker command timed out");
        }
        String output = new String(process.getInputStream().readAllBytes());
        if (process.exitValue() != 0) throw new IllegalStateException("Docker command failed: " + output);
        return output;
    }

    private static void cleanup() {
        try { command("docker", "rm", "-f", CONTAINER); } catch (Exception ignored) { }
        if (providerServer != null) providerServer.shutdownNow();
        PROVIDER.stop();
        COMMERCE.stop();
    }
}
